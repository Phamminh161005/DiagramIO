package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.DiagramioApplication;
import com.diagramio.TestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;

/**
 * Hai instance của app (hai Spring context, hai cổng) dùng chung PostgreSQL và Redis, cache {@code two-level}, như
 * khi chạy sau load balancer. Ghi ở instance A thì instance B thấy sau độ trễ pub/sub (test cho phép tới 3 giây,
 * thực tế vài ms); Redis treo thì cả hai đọc thẳng CSDL và không bao giờ trả bản cũ.
 */
class TwoInstanceCacheE2eTest {

    private static final String API = "/api/v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    static final GenericContainer<?> redis = new GenericContainer<>(TestDatabase.REDIS_IMAGE).withExposedPorts(6379);

    private static ConfigurableApplicationContext a;
    private static ConfigurableApplicationContext b;
    private static int portA;
    private static int portB;

    @BeforeAll
    static void startTwoInstances() {
        redis.start();
        a = instance();
        b = instance();
        portA = port(a);
        portB = port(b);
    }

    @AfterAll
    static void stopInstances() {
        if (a != null) {
            a.close();
        }
        if (b != null) {
            b.close();
        }
        redis.stop();
    }

    private static ConfigurableApplicationContext instance() {
        var postgres = E2eTestBase.postgres;   // container dùng chung của bộ E2E
        // Tham số dòng lệnh, ưu tiên hơn application.yml (properties() của builder chỉ là giá trị mặc định)
        return new SpringApplicationBuilder(DiagramioApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--diagramio.cache.mode=two-level",
                "--diagramio.cache.monitor-interval=100ms");
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void updateOnOneInstanceIsSeenOnTheOther() {
        String token = login();
        String id = createDiagram(token);
        assertThat(get(portA, "/diagrams/" + id, token).statusCode()).isEqualTo(200);
        assertThat(get(portB, "/diagrams/" + id, token).statusCode()).isEqualTo(200);   // B giữ version 1 trong L1

        assertThat(update(portA, token, id, 1, "Renamed on A", false).statusCode()).isEqualTo(200);

        JsonNode onB = await(() -> json(get(portB, "/diagrams/" + id, token)),
                d -> d.path("version").asInt() == 2, "B returns version 2");
        assertThat(onB.path("title").asText()).isEqualTo("Renamed on A");
    }

    @Test
    void makingADiagramPrivateOnOneInstanceRemovesGuestAccessOnTheOther() {
        String token = login();
        String id = createDiagram(token);
        assertThat(update(portA, token, id, 1, "Public", true).statusCode()).isEqualTo(200);
        assertThat(get(portB, "/diagrams/" + id, null).statusCode()).isEqualTo(200);   // khách, qua cache của B

        assertThat(update(portA, token, id, 2, "Private again", false).statusCode()).isEqualTo(200);

        await(() -> get(portB, "/diagrams/" + id, null).statusCode(), status -> status == 401,
                "guest is refused on B");
    }

    @Test
    void deleteOnOneInstanceIs404OnTheOther() {
        String token = login();
        String id = createDiagram(token);
        get(portB, "/diagrams/" + id, token);

        assertThat(send(portA, "DELETE", "/diagrams/" + id, token, null).statusCode()).isEqualTo(204);

        await(() -> get(portB, "/diagrams/" + id, token).statusCode(), status -> status == 404, "B answers 404");
    }

    @Test
    void whenRedisHangsBothInstancesKeepServingTheLatestStateFromTheDatabase() {
        String token = login();
        String id = createDiagram(token);
        get(portA, "/diagrams/" + id, token);
        get(portB, "/diagrams/" + id, token);

        var docker = redis.getDockerClient();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            assertThat(update(portA, token, id, 1, "Written while Redis hangs", false).statusCode()).isEqualTo(200);
            JsonNode onB = await(() -> json(get(portB, "/diagrams/" + id, token)),
                    d -> d.path("version").asInt() == 2, "B reads version 2 from the database");
            assertThat(onB.path("title").asText()).isEqualTo("Written while Redis hangs");
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }

        // Sau khi Redis trở lại, không instance nào trả lại version 1 (bản cũ còn trên Redis đã bị xóa)
        for (int i = 0; i < 20; i++) {
            assertThat(json(get(portB, "/diagrams/" + id, token)).path("version").asInt()).isEqualTo(2);
            assertThat(json(get(portA, "/diagrams/" + id, token)).path("version").asInt()).isEqualTo(2);
            sleep(100);
        }
    }

    // ------------------------------------------------------------------ HTTP helpers

    private static String login() {
        String user = "u" + UUID.randomUUID().toString().substring(0, 12).replace("-", "");
        String email = user + "@two-instances.test";
        HttpResponse<String> registered = send(portA, "POST", "/auth/register", null,
                obj("username", user, "email", email, "password", "Passw0rd!123"));
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        HttpResponse<String> login = send(portB, "POST", "/auth/login", null,
                obj("email", email, "password", "Passw0rd!123"));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return json(login).path("accessToken").asText();
    }

    private static String createDiagram(String token) {
        HttpResponse<String> created = send(portA, "POST", "/diagrams", token, obj("title", "Shared", "diagramType", "UML"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json(created).path("id").asText();
    }

    private static HttpResponse<String> update(int port, String token, String id, int version, String title,
                                               boolean isPublic) {
        ObjectNode body = obj("title", title, "diagramType", "UML", "version", version, "isPublic", isPublic);
        body.set("data", JSON.createObjectNode().<ObjectNode>set("nodes", JSON.createArrayNode())
                .set("edges", JSON.createArrayNode()));
        return send(port, "PUT", "/diagrams/" + id, token, body);
    }

    private static HttpResponse<String> get(int port, String path, String token) {
        return send(port, "GET", path, token, null);
    }

    private static HttpResponse<String> send(int port, String method, String path, String token, JsonNode body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + API + path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString()));
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(method + " " + path + " failed", e);
        }
    }

    private static ObjectNode obj(Object... kv) {
        ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            node.set((String) kv[i], JSON.valueToTree(kv[i + 1]));
        }
        return node;
    }

    private static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON (status " + response.statusCode() + "): " + response.body(), e);
        }
    }

    private static <T> T await(java.util.function.Supplier<T> probe, Predicate<T> done, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        T last = null;
        while (System.nanoTime() < deadline) {
            last = probe.get();
            if (done.test(last)) {
                return last;
            }
            sleep(20);
        }
        throw new AssertionError("timed out waiting until " + what + ", last: " + last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
