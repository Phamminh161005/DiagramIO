package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * B2 qua HTTP, chế độ cache mặc định (local): ETag và 304, đọc được ngay điều mình vừa ghi, đổi quyền và xóa có
 * hiệu lực ngay, 304 không bao giờ thay cho 401/403/404. Hai instance dùng chung Redis: {@code TwoInstanceCacheE2eTest}.
 */
class CacheE2eTest extends E2eTestBase {

    private static final String IF_NONE_MATCH = "If-None-Match";

    // ---------- ETag và 304

    @Test
    void getReturnsAWeakEtagOfTheVersionAndAskClientsToRevalidate() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);

        Res res = get(API + "/diagrams/" + id).as(owner).send();

        assertStatus(res, 200);
        assertThat(res.header("ETag")).isEqualTo("W/\"1\"");
        assertThat(res.header("Cache-Control")).isEqualTo("no-cache, private");
    }

    @Test
    void unchangedDiagramIs304WithoutBody() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        String etag = get(API + "/diagrams/" + id).as(owner).send().header("ETag");

        Res res = get(API + "/diagrams/" + id).as(owner).header(IF_NONE_MATCH, etag).send();

        assertStatus(res, 304);
        assertThat(res.body()).isEmpty();
        assertThat(res.header("ETag")).isEqualTo(etag);
        assertThat(res.header("Cache-Control")).isEqualTo("no-cache, private");
        // Danh sách ETag, ETag mạnh cùng giá trị, và * cũng khớp (so yếu theo RFC 9110)
        assertStatus(get(API + "/diagrams/" + id).as(owner).header(IF_NONE_MATCH, "W/\"9\", \"1\"").send(), 304);
        assertStatus(get(API + "/diagrams/" + id).as(owner).header(IF_NONE_MATCH, "*").send(), 304);
    }

    @Test
    void afterAnUpdateTheOldEtagGetsTheNewContentAndTheWriterReadsItsOwnWrite() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        String oldEtag = get(API + "/diagrams/" + id).as(owner).send().header("ETag");

        Res put = updateDiagram(owner, id, "Renamed", "UML", 1, dataWith("after"), false);
        assertStatus(put, 200);
        assertThat(put.header("ETag")).isEqualTo("W/\"2\"");

        Res res = get(API + "/diagrams/" + id).as(owner).header(IF_NONE_MATCH, oldEtag).send();
        assertStatus(res, 200);
        assertThat(res.header("ETag")).isEqualTo("W/\"2\"");
        assertThat(res.json().path("title").asText()).isEqualTo("Renamed");
        assertThat(res.json().path("data").path("nodes").get(0).path("id").asText()).isEqualTo("after");
        assertStatus(get(API + "/diagrams/" + id).as(owner).header(IF_NONE_MATCH, put.header("ETag")).send(), 304);
    }

    @Test
    void conditionalGetNeverReplacesAnAccessError() {
        Actor owner = registerAndLogin();
        Actor stranger = registerAndLogin();
        String id = createDiagramId(owner);
        String etag = get(API + "/diagrams/" + id).as(owner).send().header("ETag");

        // Người không có quyền không dò được version của sơ đồ riêng tư qua 304
        assertError(get(API + "/diagrams/" + id).header(IF_NONE_MATCH, etag).send(), 401);
        assertError(get(API + "/diagrams/" + id).as(stranger).header(IF_NONE_MATCH, etag).send(), 403);
        assertError(get(API + "/diagrams/" + id).as(stranger).header(IF_NONE_MATCH, "*").send(), 403);
        assertError(get(API + "/diagrams/" + java.util.UUID.randomUUID()).as(owner)
                .header(IF_NONE_MATCH, "*").send(), 404);
    }

    @Test
    void malformedIfNoneMatchIsIgnored() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);

        Res res = get(API + "/diagrams/" + id).as(owner).header(IF_NONE_MATCH, "1").send();

        assertStatus(res, 200);
        assertThat(res.json().path("id").asText()).isEqualTo(id);
    }

    // ---------- quyền và xóa có hiệu lực ngay dù sơ đồ đang nằm trong cache

    @Test
    void makingADiagramPrivateRemovesGuestAccessImmediately() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        setPublic(owner, id, true);
        Res guest = get(API + "/diagrams/" + id).send();
        assertStatus(guest, 200);   // giờ sơ đồ nằm trong cache, kèm cờ công khai

        setPublic(owner, id, false);

        assertError(get(API + "/diagrams/" + id).send(), 401);
        assertError(get(API + "/diagrams/" + id).header(IF_NONE_MATCH, guest.header("ETag")).send(), 401);
        assertError(get(API + "/diagrams/" + id).header(IF_NONE_MATCH, "*").send(), 401);
    }

    @Test
    void deletedDiagramIs404RightAwayEvenWhenItWasCached() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        setPublic(owner, id, true);
        String etag = get(API + "/diagrams/" + id).send().header("ETag");

        assertStatus(delete(API + "/diagrams/" + id).as(owner).send(), 204);

        assertError(get(API + "/diagrams/" + id).as(owner).send(), 404);
        assertError(get(API + "/diagrams/" + id).send(), 404);
        assertError(get(API + "/diagrams/" + id).header(IF_NONE_MATCH, etag).send(), 404);
        assertError(get(API + "/diagrams/" + id + "/versions").as(owner).send(), 404);
    }

    @Test
    void restoreIsVisibleToTheNextRead() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        setData(owner, id, dataWith("first"));
        String versionId = createVersion(owner, id, "first").path("id").asText();
        setData(owner, id, dataWith("second"));
        assertThat(getDiagram(owner, id).path("data").path("nodes").get(0).path("id").asText()).isEqualTo("second");

        Res restored = post(API + "/diagrams/" + id + "/versions/" + versionId + "/restore").as(owner).send();
        assertStatus(restored, 200);

        JsonNode now = getDiagram(owner, id);
        assertThat(now.path("version").asInt()).isEqualTo(restored.json().path("version").asInt());
        assertThat(now.path("data").path("nodes").get(0).path("id").asText()).isEqualTo("first");
    }

    @Test
    void editLinkUpdateIsSeenByTheOwner() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        getDiagram(owner, id);
        Actor editor = Actor.shareOnly(shareToken(owner, id, "edit"));

        assertStatus(updateDiagram(editor, id, "By editor", "UML", 1, dataWith("editor"), false), 200);

        assertThat(getDiagram(owner, id).path("title").asText()).isEqualTo("By editor");
    }

    // ---------- link chia sẻ

    @Test
    void shareLinkEtagCarriesTheRoleAndRevokedLinkIs404() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        JsonNode share = createShare(owner, id, "view");
        String token = share.path("shareToken").asText();
        String url = API + "/shares/" + token;

        Res first = get(url).send();
        assertStatus(first, 200);
        assertThat(first.header("ETag")).isEqualTo("W/\"1-view\"");
        Res same = get(url).header(IF_NONE_MATCH, first.header("ETag")).send();
        assertStatus(same, 304);
        assertThat(same.body()).isEmpty();

        assertStatus(patch(API + "/diagrams/" + id + "/shares/" + share.path("id").asText()).as(owner)
                .json(obj("role", "edit")).send(), 200);
        Res afterRoleChange = get(url).header(IF_NONE_MATCH, first.header("ETag")).send();
        assertStatus(afterRoleChange, 200);
        assertThat(afterRoleChange.header("ETag")).isEqualTo("W/\"1-edit\"");
        assertThat(afterRoleChange.json().path("role").asText()).isEqualTo("edit");

        assertStatus(delete(API + "/diagrams/" + id + "/shares/" + share.path("id").asText()).as(owner).send(), 204);
        assertError(get(url).header(IF_NONE_MATCH, afterRoleChange.header("ETag")).send(), 404);
    }

    // ---------- phiên bản bất biến

    @Test
    void savedVersionIsCacheableForever() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        String versionId = createVersion(owner, id, "v").path("id").asText();

        Res res = get(API + "/diagrams/" + id + "/versions/" + versionId).as(owner).send();

        assertStatus(res, 200);
        assertThat(res.header("Cache-Control")).isEqualTo("max-age=31536000, private, immutable");
        assertThat(res.json().path("data").path("nodes").isArray()).isTrue();
        // Đọc lần hai (từ cache phiên bản) vẫn kiểm tra quyền
        assertError(get(API + "/diagrams/" + id + "/versions/" + versionId).send(), 401);
    }
}
