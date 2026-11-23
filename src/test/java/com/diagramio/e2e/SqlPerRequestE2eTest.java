package com.diagramio.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.diagramio.e2e.JdbcTrafficRecorder.Traffic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Số câu SQL, transaction, dòng và byte giữa app và CSDL của từng endpoint (M0 trong docs/ke-hoach-pha-2.md).
 * Các số này không phụ thuộc phần cứng nên được đo bằng test thay vì trên Kaggle. Bảng đầy đủ được ghi ra
 * {@code target/bench/sql-per-request.md}, bản đã duyệt nằm trong docs/pha-2/danh-gia-hien-trang.md.
 *
 * <p>Đây là test đặc tả hiện trạng: {@code expect(...)} ở mỗi bước là số câu lệnh của mã hiện tại. Cải tiến nào
 * làm đổi số câu lệnh (ví dụ B1) thì sửa con số trong cùng PR, để diff cho thấy đúng thay đổi; con số tăng
 * ngoài dự kiến (ví dụ lỗi N+1) sẽ làm test đỏ.
 *
 * <p>Dùng chung container PostgreSQL với các test E2E khác nhưng chạy một context riêng (DataSource được bọc để
 * ghi lưu lượng). Request được gửi lần lượt nên mọi câu lệnh ghi được giữa hai mốc thuộc về đúng một request.
 */
@Import(SqlPerRequestE2eTest.RecordJdbcTraffic.class)
class SqlPerRequestE2eTest extends E2eTestBase {

    /** Kích thước data của sơ đồ dùng để đo (JSON gọn), cỡ một sơ đồ ERD vừa. */
    private static final int DATA_BYTES = 50_000;
    private static final int VERSIONS = 10;
    private static final int PAGE_SIZE = 20;
    private static final Path REPORT = Path.of("target", "bench", "sql-per-request.md");

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordJdbcTraffic {

        @Bean
        JdbcTrafficRecorder jdbcTrafficRecorder() {
            return new JdbcTrafficRecorder();
        }

        @Bean
        static BeanPostProcessor recordJdbcTrafficOfDataSource(ObjectProvider<JdbcTrafficRecorder> recorder) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
                        return recorder.getObject().wrap(dataSource);
                    }
                    return bean;
                }
            };
        }
    }

    /**
     * Kết quả mong đợi của một request, theo thứ tự tham số của {@link #expect}: mã HTTP; số câu SELECT, INSERT,
     * UPDATE, DELETE; số commit (transaction tường minh); số lần mượn kết nối từ pool; số lần chở cột data
     * (null = không kiểm tra, khi kết quả phụ thuộc dữ liệu của test khác).
     *
     * <p>Query method tự sinh của Spring Data (findByX, existsByX) không có @Transactional nên chạy autocommit:
     * mỗi câu mượn một kết nối riêng và không có commit, vì vậy số commit có thể nhỏ hơn số lần mượn kết nối.
     */
    private record Expect(int status, int selects, int inserts, int updates, int deletes, int commits,
                          int connections, Integer dataTrips) {
    }

    private record Measured(String endpoint, String caller, String note, int status, Traffic traffic,
                            Expect expect) {
    }

    @Autowired
    private JdbcTrafficRecorder recorder;

    private final List<Measured> measured = new ArrayList<>();

    @Test
    void sqlPerRequestMatchesTheCurrentBaseline() throws IOException {
        JsonNode data = diagramData(DATA_BYTES);
        Actor owner = registerAndLogin();

        // ---- Hệ thống và tài khoản
        measure("GET /health", "khách", "", expect(200, 1, 0, 0, 0, 0, 1, 0), get(API + "/health"));

        String name = "u" + uniq();
        String email = name + "@sql.test";
        measure("POST /auth/register", "khách", "", expect(201, 3, 1, 0, 0, 1, 3, 0),
                post(API + "/auth/register").json(obj("username", name, "email", email, "password", PASSWORD)));
        Res login = measure("POST /auth/login", "khách", "", expect(200, 2, 1, 0, 0, 1, 2, 0),
                post(API + "/auth/login").json(obj("email", email, "password", PASSWORD)));
        String accessToken = login.json().path("accessToken").asText();
        Res refreshed = measure("POST /auth/refresh-token", "khách", "", expect(200, 3, 1, 1, 0, 1, 1, 0),
                post(API + "/auth/refresh-token").json(obj("refreshToken", login.json().path("refreshToken").asText())));
        measure("POST /auth/logout", "chủ tài khoản", "", expect(204, 2, 0, 1, 0, 1, 2, 0),
                post(API + "/auth/logout").bearer(accessToken)
                        .json(obj("refreshToken", refreshed.json().path("refreshToken").asText())));
        measure("GET /users/me", "chủ tài khoản", "", expect(200, 1, 0, 0, 0, 1, 1, 0),
                get(API + "/users/me").bearer(accessToken));
        measure("PATCH /users/me/password", "chủ tài khoản", "", expect(204, 2, 0, 1, 0, 2, 2, 0),
                patch(API + "/users/me/password").bearer(accessToken)
                        .json(obj("currentPassword", PASSWORD, "newPassword", PASSWORD + "x")));

        // ---- Một sơ đồ 50 KB có 10 phiên bản và hai link chia sẻ
        Res created = measure("POST /diagrams", "chủ sơ đồ", "", expect(201, 0, 1, 0, 0, 1, 1, 0),
                post(API + "/diagrams").as(owner).json(obj("title", "SQL count", "diagramType", "ERD")));
        String id = created.json().path("id").asText();
        int version = setData(owner, id, data).path("version").asInt();
        String viewToken = shareToken(owner, id, "view");
        String editToken = shareToken(owner, id, "edit");
        for (int i = 1; i <= VERSIONS; i++) {
            createVersion(owner, id, "v" + i);
        }

        measure("GET /diagrams/{id}", "chủ sơ đồ", "data 50 KB", expect(200, 1, 0, 0, 0, 1, 1, 1),
                get(API + "/diagrams/" + id).as(owner));
        measure("GET /diagrams/{id}", "link view", "X-Share-Token, không đăng nhập", expect(200, 2, 0, 0, 0, 1, 2, 1),
                get(API + "/diagrams/" + id).share(viewToken));
        String publicId = createDiagramId(owner);
        setData(owner, publicId, data);
        setPublic(owner, publicId, true);
        measure("GET /diagrams/{id}", "khách", "sơ đồ công khai", expect(200, 1, 0, 0, 0, 1, 1, 1),
                get(API + "/diagrams/" + publicId));

        Res put = measure("PUT /diagrams/{id}", "chủ sơ đồ", "data 50 KB", expect(200, 2, 0, 1, 0, 2, 2, 3),
                putRequest(owner, id, version, data));
        version = put.json().path("version").asInt();
        Res putByLink = measure("PUT /diagrams/{id}", "link edit", "X-Share-Token, không đăng nhập",
                expect(200, 3, 0, 1, 0, 2, 3, 3), putRequest(Actor.shareOnly(editToken), id, version, data));
        version = putByLink.json().path("version").asInt();
        measure("PUT /diagrams/{id}", "chủ sơ đồ", "version cũ, trả 409", expect(409, 1, 0, 0, 0, 1, 1, 1),
                putRequest(owner, id, version - 1, data));

        // ---- Lịch sử phiên bản
        Res versions = measure("GET /diagrams/{id}/versions", "chủ sơ đồ", VERSIONS + " phiên bản × 50 KB",
                expect(200, 2, 0, 0, 0, 1, 2, VERSIONS + 1), get(API + "/diagrams/" + id + "/versions").as(owner));
        String versionId = items(versions.json()).get(VERSIONS - 1).path("id").asText();
        measure("GET /diagrams/{id}/versions/{vid}", "chủ sơ đồ", "", expect(200, 2, 0, 0, 0, 1, 2, 2),
                get(API + "/diagrams/" + id + "/versions/" + versionId).as(owner));
        measure("POST /diagrams/{id}/versions", "chủ sơ đồ", "", expect(201, 3, 1, 0, 0, 1, 1, 2),
                post(API + "/diagrams/" + id + "/versions").as(owner).json(obj("note", "measured")));
        measure("POST /diagrams/{id}/versions/{vid}/restore", "chủ sơ đồ", "", expect(200, 4, 1, 1, 0, 1, 1, 4),
                post(API + "/diagrams/" + id + "/versions/" + versionId + "/restore").as(owner));

        // ---- Chia sẻ
        measure("GET /diagrams/{id}/shares", "chủ sơ đồ", "", expect(200, 2, 0, 0, 0, 1, 2, 1),
                get(API + "/diagrams/" + id + "/shares").as(owner));
        Res share = measure("POST /diagrams/{id}/shares", "chủ sơ đồ", "", expect(201, 3, 1, 0, 0, 2, 3, 1),
                post(API + "/diagrams/" + id + "/shares").as(owner).json(obj("role", "view")));
        String shareId = share.json().path("id").asText();
        measure("PATCH /diagrams/{id}/shares/{sid}", "chủ sơ đồ", "", expect(200, 2, 0, 1, 0, 3, 3, 1),
                patch(API + "/diagrams/" + id + "/shares/" + shareId).as(owner).json(obj("role", "edit")));
        measure("GET /shares/{token}", "khách", "", expect(200, 2, 0, 0, 0, 1, 2, 1),
                get(API + "/shares/" + share.json().path("shareToken").asText()));
        measure("DELETE /diagrams/{id}/shares/{sid}", "chủ sơ đồ", "", expect(204, 3, 0, 0, 1, 3, 3, 1),
                delete(API + "/diagrams/" + id + "/shares/" + shareId).as(owner));

        // ---- Danh sách: một người có đủ một trang sơ đồ 50 KB (trang đầy thì Spring Data mới đếm tổng)
        Actor lister = registerAndLogin();
        for (int i = 0; i < PAGE_SIZE; i++) {
            setData(lister, createDiagram(lister, "ERD", "Order " + i).path("id").asText(), data);
        }
        measure("GET /diagrams", "chủ sơ đồ", "trang " + PAGE_SIZE + " sơ đồ × 50 KB", expect(200, 4, 0, 0, 0, 0, 4, PAGE_SIZE + 1),
                get(API + "/diagrams?page=0&size=" + PAGE_SIZE).as(lister));
        measure("GET /diagrams?title=", "chủ sơ đồ", PAGE_SIZE + " sơ đồ khớp tên", expect(200, 4, 0, 0, 0, 0, 4, PAGE_SIZE + 1),
                get(API + "/diagrams?title=order&page=0&size=" + PAGE_SIZE).as(lister));
        measure("GET /diagrams/public", "khách", "size=1 (CSDL test dùng chung)", expect(200, 4, 0, 0, 0, 0, 4, null),
                get(API + "/diagrams/public?page=0&size=1"));

        // ---- Xóa
        String doomed = createDiagramId(owner);
        setData(owner, doomed, data);
        measure("DELETE /diagrams/{id}", "chủ sơ đồ", "data 50 KB", expect(204, 2, 0, 0, 1, 2, 2, 2),
                delete(API + "/diagrams/" + doomed).as(owner));

        writeReport();
        assertMatchesExpectations();
    }

    private Res measure(String endpoint, String caller, String note, Expect expect, Req request) {
        recorder.reset();
        Res res = request.send();
        measured.add(new Measured(endpoint, caller, note, res.status(), recorder.snapshot(), expect));
        return res;
    }

    private static Expect expect(int status, int selects, int inserts, int updates, int deletes, int commits,
                                 int connections, Integer dataTrips) {
        return new Expect(status, selects, inserts, updates, deletes, commits, connections, dataTrips);
    }

    private Req putRequest(Actor actor, String id, int version, JsonNode data) {
        ObjectNode body = obj("title", "SQL count", "diagramType", "ERD", "version", version, "isPublic", false);
        body.set("data", data);
        return put(API + "/diagrams/" + id).as(actor).json(body);
    }

    /** Sơ đồ ERD đủ lớn để thấy rõ cột data: mỗi bảng có 5 cột và một quan hệ tới bảng trước. */
    private static JsonNode diagramData(int targetBytes) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        ArrayNode edges = root.putArray("edges");
        for (int i = 0; root.toString().length() < targetBytes; i++) {
            ObjectNode node = nodes.addObject()
                    .put("id", "n" + i).put("type", "entity").put("label", "Table_" + i)
                    .put("x", (i % 10) * 220).put("y", (i / 10) * 160).put("width", 180).put("height", 120);
            ArrayNode attributes = node.putArray("attributes");
            attributes.addObject().put("name", "id").put("type", "uuid").put("pk", true);
            for (int c = 1; c <= 4; c++) {
                attributes.addObject().put("name", "column_" + c).put("type", "varchar(255)");
            }
            if (i > 0) {
                edges.addObject().put("id", "e" + i).put("source", "n" + (i - 1)).put("target", "n" + i)
                        .put("type", "one-to-many");
            }
        }
        return root;
    }

    private void assertMatchesExpectations() {
        long dataUnit = dataUnit();
        SoftAssertions softly = new SoftAssertions();
        for (Measured m : measured) {
            Traffic t = m.traffic();
            Expect e = m.expect();
            String label = m.endpoint() + " (" + m.caller() + (m.note().isEmpty() ? "" : ", " + m.note()) + ")";
            softly.assertThat(m.status()).as(label + ": HTTP status").isEqualTo(e.status());
            softly.assertThat(new int[]{t.selects(), t.inserts(), t.updates(), t.deletes(), t.others(), t.commits(),
                            t.connections()})
                    .as(label + ": SELECT, INSERT, UPDATE, DELETE, khác, commit, kết nối; SQL = " + t.sql())
                    .containsExactly(e.selects(), e.inserts(), e.updates(), e.deletes(), 0, e.commits(),
                            e.connections());
            softly.assertThat(t.rollbacks()).as(label + ": rollback").isZero();
            if (e.dataTrips() != null) {
                softly.assertThat(dataTrips(t, dataUnit)).as(label + ": số lần chở data").isEqualTo(e.dataTrips());
            }
        }
        softly.assertAll();
    }

    /** Số byte đọc được khi chủ mở sơ đồ đo: một lần chở cột data (dạng text của jsonb) cộng vài cột nhỏ. */
    private long dataUnit() {
        return measured.stream()
                .filter(m -> m.endpoint().equals("GET /diagrams/{id}") && m.caller().equals("chủ sơ đồ"))
                .findFirst().orElseThrow().traffic().bytesRead();
    }

    private static int dataTrips(Traffic traffic, long dataUnit) {
        return Math.toIntExact(Math.round((double) traffic.bytesRead() / dataUnit)
                + Math.round((double) traffic.bytesWritten() / dataUnit));
    }

    /** Bảng Markdown: một dòng mỗi request, sau đó là danh sách câu SQL để giải thích từng con số. */
    private void writeReport() throws IOException {
        long dataUnit = dataUnit();
        StringBuilder md = new StringBuilder();
        md.append("<!-- Sinh tự động bởi SqlPerRequestE2eTest, đừng sửa tay. -->\n\n");
        md.append("| Endpoint | Người gọi | Ghi chú | Mã | SQL | SELECT | INSERT | UPDATE | DELETE "
                + "| Commit | Kết nối | Dòng đọc | KB đọc | KB ghi | Lần chở data |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Measured m : measured) {
            Traffic t = m.traffic();
            md.append(String.format(Locale.ROOT, "| `%s` | %s | %s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %.1f | %.1f | %d |%n",
                    m.endpoint(), m.caller(), m.note(), m.status(), t.statements(), t.selects(), t.inserts(),
                    t.updates(), t.deletes(), t.commits(), t.connections(), t.rows(), t.bytesRead() / 1024.0,
                    t.bytesWritten() / 1024.0, dataTrips(t, dataUnit)));
        }
        md.append(String.format(Locale.ROOT, "%n- Commit: số transaction tường minh. Query method tự sinh của Spring "
                + "Data (findByX, existsByX) chạy autocommit, mỗi câu mượn một kết nối riêng và không commit.%n"
                + "- Kết nối: số lần mượn kết nối từ pool Hikari.%n"
                + "- KB đọc/ghi: kích thước giá trị cột đọc về và tham số gửi đi (chuỗi, JSON theo UTF-8).%n"
                + "- Lần chở data: KB đọc và KB ghi quy ra số lần chở cột `data` của sơ đồ đo (%.1f KB khi đọc "
                + "từ CSDL), làm tròn từng chiều.%n", dataUnit / 1024.0));
        md.append("\n### Câu SQL của từng request\n");
        for (Measured m : measured) {
            md.append("\n**").append(m.endpoint()).append("** (").append(m.caller())
                    .append(m.note().isEmpty() ? "" : ", " + m.note()).append(")\n\n");
            for (String sql : m.traffic().sql()) {
                String oneLine = sql.replaceAll("\\s+", " ").trim();
                md.append("- `").append(oneLine.length() > 220 ? oneLine.substring(0, 217) + "..." : oneLine)
                        .append("`\n");
            }
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, md, UTF_8);
    }
}
