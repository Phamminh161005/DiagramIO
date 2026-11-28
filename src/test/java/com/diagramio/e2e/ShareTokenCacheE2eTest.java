package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * B3 qua HTTP, chế độ cache mặc định (local): đọc qua link khi trúng cache không chạy câu SQL nào, còn thu hồi, đổi
 * quyền và xóa sơ đồ có hiệu lực ngay từ request tiếp theo. Hai instance dùng chung Redis: {@code TwoInstanceCacheE2eTest}.
 */
@Import(SqlPerRequestE2eTest.RecordJdbcTraffic.class)
class ShareTokenCacheE2eTest extends E2eTestBase {

    @Autowired
    private JdbcTrafficRecorder recorder;

    @Test
    void readsThroughACachedLinkRunNoSql() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        setData(owner, id, dataWith("cached"));
        String token = shareToken(owner, id, "view");
        Actor viewer = Actor.shareOnly(token);
        assertStatus(get(API + "/diagrams/" + id).as(viewer).send(), 200);
        assertStatus(get(API + "/shares/" + token).send(), 200);

        JdbcTrafficRecorder.Traffic viaHeader = traffic(get(API + "/diagrams/" + id).as(viewer), 200);
        JdbcTrafficRecorder.Traffic viaLink = traffic(get(API + "/shares/" + token), 200);

        // Không cache thì 2 câu mỗi request (tra token, đọc sơ đồ), xem SqlPerRequestE2eTest
        assertThat(viaHeader.statements()).as("X-Share-Token: %s", viaHeader.sql()).isZero();
        assertThat(viaHeader.connections()).isZero();
        assertThat(viaLink.statements()).as("GET /shares/{token}: %s", viaLink.sql()).isZero();
        assertThat(viaLink.connections()).isZero();
    }

    @Test
    void probingTheSameUnknownTokenAgainRunsNoSql() {
        String unknown = "f".repeat(48);
        assertError(get(API + "/shares/" + unknown).send(), 404);

        Res again = get(API + "/shares/" + unknown).send();
        JdbcTrafficRecorder.Traffic second = traffic(get(API + "/shares/" + unknown), 404);

        assertError(again, 404);
        assertThat(second.statements()).isZero();
    }

    @Test
    void revokedLinkIsRefusedOnTheVeryNextRequest() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        JsonNode share = createShare(owner, id, "edit");
        String token = share.path("shareToken").asText();
        assertStatus(get(API + "/diagrams/" + id).as(Actor.shareOnly(token)).send(), 200);   // token nằm trong cache

        assertStatus(delete(API + "/diagrams/" + id + "/shares/" + share.path("id").asText()).as(owner).send(), 204);

        assertError(get(API + "/diagrams/" + id).as(Actor.shareOnly(token)).send(), 401);
        assertError(updateDiagram(Actor.shareOnly(token), id, "After revoke", "UML", 1, emptyData(), false), 401);
        Res link = get(API + "/shares/" + token).send();
        assertError(link, 404);
        assertThat(link.json().path("message").asText()).isEqualTo("Share token does not exist or has been revoked");
    }

    @Test
    void downgradeFromEditToViewIsEnforcedOnTheVeryNextRequest() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        JsonNode share = createShare(owner, id, "edit");
        Actor editor = Actor.shareOnly(share.path("shareToken").asText());
        assertStatus(updateDiagram(editor, id, "By editor", "UML", 1, emptyData(), false), 200);

        assertStatus(patch(API + "/diagrams/" + id + "/shares/" + share.path("id").asText()).as(owner)
                .json(obj("role", "view")).send(), 200);

        assertError(updateDiagram(editor, id, "Still editing?", "UML", 2, emptyData(), false), 403);
        assertStatus(get(API + "/diagrams/" + id).as(editor).send(), 200);   // vẫn xem được
        assertThat(get(API + "/shares/" + share.path("shareToken").asText()).send().json().path("role").asText())
                .isEqualTo("view");
    }

    @Test
    void upgradeFromViewToEditIsUsableOnTheVeryNextRequest() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        JsonNode share = createShare(owner, id, "view");
        Actor holder = Actor.shareOnly(share.path("shareToken").asText());
        assertError(updateDiagram(holder, id, "Not yet", "UML", 1, emptyData(), false), 403);

        assertStatus(patch(API + "/diagrams/" + id + "/shares/" + share.path("id").asText()).as(owner)
                .json(obj("role", "edit")).send(), 200);

        assertStatus(updateDiagram(holder, id, "Now allowed", "UML", 1, emptyData(), false), 200);
    }

    @Test
    void deletingTheDiagramInvalidatesItsLinksRightAway() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        String token = shareToken(owner, id, "view");
        assertStatus(get(API + "/shares/" + token).send(), 200);

        assertStatus(delete(API + "/diagrams/" + id).as(owner).send(), 204);

        Res link = get(API + "/shares/" + token).send();
        assertError(link, 404);
        assertThat(link.json().path("message").asText()).isEqualTo("Share token does not exist or has been revoked");
        assertError(get(API + "/diagrams/" + id).as(Actor.shareOnly(token)).send(), 404);
    }

    private JdbcTrafficRecorder.Traffic traffic(Req request, int expectedStatus) {
        recorder.reset();
        Res res = request.send();
        JdbcTrafficRecorder.Traffic traffic = recorder.snapshot();
        assertThat(res.status()).as(res.body()).isEqualTo(expectedStatus);
        return traffic;
    }
}
