package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Body quá lớn bị chặn trước khi parse (413), cả khi có Content-Length lẫn khi gửi kiểu chunked; body dưới giới hạn
 * vận chuyển nhưng data quá 2 MB vẫn là 400 như trước.
 */
class RequestLimitsE2eTest extends E2eTestBase {

    /** Lớn hơn giới hạn mặc định 4 MB. */
    private static final int OVERSIZED = 5 * 1024 * 1024;

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void oversizedBodyWithContentLengthIsRefusedBeforeItIsRead() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);

        Res res = put(API + "/diagrams/" + id).as(owner).rawJson(diagramBody(OVERSIZED)).send();

        assertError(res, 413);
        assertThat(res.json().path("message").asText()).isEqualTo("Request body must not exceed 4 MB");
        assertThat(getDiagram(owner, id).path("version").asInt()).isEqualTo(1);   // không có gì bị ghi
    }

    @Test
    void publicEndpointsAreProtectedToo() {
        Res res = post(API + "/auth/login").rawJson(diagramBody(OVERSIZED)).send();

        assertError(res, 413);
    }

    @Test
    void oversizedChunkedBodyIsStoppedWhileReading() throws Exception {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        byte[] body = diagramBody(OVERSIZED).getBytes(StandardCharsets.UTF_8);

        // ofInputStream không biết độ dài: client gửi Transfer-Encoding: chunked, không có Content-Length
        HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + API + "/diagrams/" + id))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + owner.accessToken())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).as(res.body()).isEqualTo(413);
        JsonNode error = JSON.readTree(res.body());
        assertThat(error.path("status").asInt()).isEqualTo(413);
        assertThat(error.path("message").asText()).isEqualTo("Request body must not exceed 4 MB");
        assertThat(getDiagram(owner, id).path("version").asInt()).isEqualTo(1);
    }

    @Test
    void bodyUnderTheTransportLimitWithTooMuchDataIsStillA400() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);

        Res res = put(API + "/diagrams/" + id).as(owner).rawJson(diagramBody(3 * 1024 * 1024)).send();

        assertError(res, 400);
        assertThat(res.json().path("message").asText()).isEqualTo("data must not exceed 2 MB");
    }

    /** Body PUT hợp lệ về cấu trúc, có data cỡ {@code bytes}. */
    private static String diagramBody(int bytes) {
        return "{\"title\":\"Big\",\"diagramType\":\"UML\",\"version\":1,\"isPublic\":false,"
                + "\"data\":{\"nodes\":[{\"id\":\"n1\",\"label\":\"" + "x".repeat(bytes) + "\"}],\"edges\":[]}}";
    }
}
