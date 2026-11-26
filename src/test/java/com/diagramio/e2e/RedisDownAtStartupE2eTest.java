package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Chế độ two-level nhưng Redis không có (cổng 1 luôn từ chối kết nối): app vẫn phải khởi động và phục vụ bằng CSDL.
 * Trước đây bean theo dõi Redis đăng ký pub/sub ngay lúc khởi động, Redis chưa lên là app dừng hẳn, nên khi Redis
 * khởi động chậm hơn app hay khởi động lại cùng lúc với app (B4, W7) thì app khởi động lại liên tục.
 */
@TestPropertySource(properties = {"diagramio.cache.mode=two-level", "spring.data.redis.port=1"})
class RedisDownAtStartupE2eTest extends E2eTestBase {

    @Test
    void appServesFromTheDatabaseWhenRedisIsMissing() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);

        JsonNode updated = setData(owner, id, dataWith("no-redis"));
        JsonNode read = getDiagram(owner, id);

        assertThat(read.path("version").asInt()).isEqualTo(updated.path("version").asInt());
        assertThat(read.path("data").path("nodes").get(0).path("id").asText()).isEqualTo("no-redis");
        assertStatus(get(API + "/health").send(), 200);
    }

    @Test
    void shareLinksStillWorkButCannotBeChangedWithoutRedis() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        JsonNode share = createShare(owner, id, "view");
        String token = share.path("shareToken").asText();

        // Tra token đi thẳng CSDL
        assertStatus(get(API + "/shares/" + token).send(), 200);
        assertStatus(get(API + "/diagrams/" + id).as(Actor.shareOnly(token)).send(), 200);
        // Thu hồi không bảo đảm được có hiệu lực ngay ở instance khác: từ chối thay vì báo thành công
        Res revoke = delete(API + "/diagrams/" + id + "/shares/" + share.path("id").asText()).as(owner).send();
        assertError(revoke, 503);
        assertThat(revoke.header("Retry-After")).isEqualTo("1");
        assertStatus(get(API + "/shares/" + token).send(), 200);
    }
}
