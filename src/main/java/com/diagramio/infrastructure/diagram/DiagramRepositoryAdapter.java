package com.diagramio.infrastructure.diagram;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramSummary;
import com.diagramio.domain.diagram.DiagramType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cài đặt DiagramRepository bằng SQL viết tay, để mỗi thao tác chỉ đọc và ghi đúng cột cần thiết (đo ở M0: kiểm tra
 * quyền, danh sách và lịch sử từng đọc cả cột data tới 2 MB; PUT từng đọc, merge rồi mới ghi).
 *
 * <p>Không dùng JPA cho bảng này: khi JPA và JDBC chạy chung một transaction, entity JPA đã nạp không thấy các câu
 * UPDATE/DELETE của JDBC (persistence context trả lại bản cũ). Mọi thao tác ở đây đọc và ghi thẳng CSDL.
 */
@Repository
public class DiagramRepositoryAdapter implements DiagramRepository {

    static final String SUMMARY_COLUMNS =
            "id, title, diagram_type, version, is_public, owner_id, updated_by, created_at, updated_at";
    private static final String FULL_COLUMNS = SUMMARY_COLUMNS + ", data";

    private final NamedParameterJdbcTemplate jdbc;

    public DiagramRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Một câu INSERT. Id do app sinh nếu sơ đồ chưa có (như trước đây với JPA). */
    @Override
    public Diagram insert(Diagram diagram) {
        UUID id = diagram.getId() != null ? diagram.getId() : UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("title", diagram.getTitle())
                .addValue("type", diagram.getDiagramType().name())
                .addValue("data", diagram.getData().json())
                .addValue("version", diagram.getVersion())
                .addValue("isPublic", diagram.isPublic())
                .addValue("ownerId", diagram.getOwnerId())
                .addValue("updatedBy", diagram.getUpdatedBy())
                .addValue("createdAt", timestamp(diagram.getCreatedAt()))
                .addValue("updatedAt", timestamp(diagram.getUpdatedAt()));
        return jdbc.queryForObject("""
                INSERT INTO diagrams (id, title, diagram_type, data, version, is_public, owner_id, updated_by,
                                      created_at, updated_at)
                VALUES (:id, :title, :type, CAST(:data AS jsonb), :version, :isPublic, :ownerId, :updatedBy,
                        COALESCE(:createdAt, now()), COALESCE(:updatedAt, now()))
                RETURNING created_at, updated_at""", params, (rs, n) -> new Diagram(id, diagram.getTitle(),
                diagram.getDiagramType(), diagram.getData(), diagram.getVersion(), diagram.isPublic(),
                diagram.getOwnerId(), diagram.getUpdatedBy(), instant(rs, "created_at"), instant(rs, "updated_at")));
    }

    @Override
    public Optional<Diagram> findById(UUID id) {
        return jdbc.query("SELECT " + FULL_COLUMNS + " FROM diagrams WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> fullDiagram(rs)).stream().findFirst();
    }

    @Override
    public Optional<DiagramAccessInfo> findAccessInfo(UUID id) {
        return accessInfo("SELECT id, owner_id, is_public, version FROM diagrams WHERE id = :id", id);
    }

    @Override
    public Optional<DiagramAccessInfo> lockAccessInfo(UUID id) {
        return accessInfo("SELECT id, owner_id, is_public, version FROM diagrams WHERE id = :id FOR UPDATE", id);
    }

    /**
     * Một câu UPDATE có điều kiện thay cho đọc rồi merge rồi ghi: data đi xuống CSDL một lần và không quay lên
     * (response dùng chính data vừa ghi). Không có dòng nào khớp nghĩa là version đã đổi hoặc sơ đồ đã bị xóa.
     */
    @Override
    public Optional<Diagram> updateIfVersion(Diagram diagram, boolean changePrivacy) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", diagram.getId())
                .addValue("expected", diagram.getVersion())
                .addValue("title", diagram.getTitle())
                .addValue("type", diagram.getDiagramType().name())
                .addValue("data", diagram.getData().json())
                .addValue("changePrivacy", changePrivacy)
                .addValue("isPublic", diagram.isPublic())
                .addValue("updatedBy", diagram.getUpdatedBy())
                .addValue("updatedAt", timestamp(diagram.getUpdatedAt()));
        List<Diagram> updated = jdbc.query("""
                UPDATE diagrams
                SET title = :title, diagram_type = :type, data = CAST(:data AS jsonb),
                    is_public = CASE WHEN :changePrivacy THEN :isPublic ELSE is_public END,
                    updated_by = :updatedBy, updated_at = :updatedAt, version = version + 1
                WHERE id = :id AND version = :expected
                RETURNING version, is_public, created_at, updated_at""", params, (rs, n) -> new Diagram(
                diagram.getId(), diagram.getTitle(), diagram.getDiagramType(), diagram.getData(), rs.getInt("version"),
                rs.getBoolean("is_public"), diagram.getOwnerId(), diagram.getUpdatedBy(), instant(rs, "created_at"),
                instant(rs, "updated_at")));
        return updated.stream().findFirst();
    }

    /** Chép data của phiên bản vào sơ đồ ngay trong CSDL; data chỉ đi lên app một lần để trả response. */
    @Override
    public Optional<Diagram> restoreContent(UUID diagramId, UUID versionId, UUID changedBy, Instant changedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", diagramId)
                .addValue("versionId", versionId)
                .addValue("changedBy", changedBy)
                .addValue("changedAt", timestamp(changedAt));
        List<Diagram> restored = jdbc.query("""
                UPDATE diagrams d
                SET data = v.data, version = d.version + 1, updated_by = :changedBy, updated_at = :changedAt
                FROM diagram_versions v
                WHERE d.id = :id AND v.id = :versionId AND v.diagram_id = d.id
                RETURNING d.id, d.title, d.diagram_type, d.data, d.version, d.is_public, d.owner_id, d.updated_by,
                          d.created_at, d.updated_at""", params, (rs, n) -> fullDiagram(rs));
        return restored.stream().findFirst();
    }

    @Override
    public DiagramPage findByOwner(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource("ownerId", ownerId);
        StringBuilder filter = new StringBuilder("owner_id = :ownerId");
        if (diagramType != null) {
            filter.append(" AND diagram_type = :type");
            params.addValue("type", diagramType.name());
        }
        if (title != null && !title.isBlank()) {
            // ILIKE (thay cho upper(title) LIKE upper(...)) để dùng được index trigram; ký tự đại diện của
            // người dùng được thoát để tìm đúng chuỗi con như trước
            filter.append(" AND title ILIKE :title ESCAPE '\\'");
            params.addValue("title", "%" + escapeLike(title) + "%");
        }
        return page(filter.toString(), params, page, size);
    }

    @Override
    public DiagramPage findPublic(int page, int size) {
        return page("is_public", new MapSqlParameterSource(), page, size);
    }

    @Override
    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM diagrams WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /**
     * Một trang (mới sửa trước) và tổng số dòng khớp, trong một câu lệnh: một kết nối, cùng một snapshot. Câu đếm
     * chạy riêng nên vẫn dùng được index (kể cả quét chỉ index); trang rỗng vẫn trả một dòng chứa tổng.
     */
    private DiagramPage page(String filter, MapSqlParameterSource params, int page, int size) {
        params.addValue("limit", size).addValue("offset", (long) page * size);
        long[] total = {0};
        List<DiagramSummary> items = new ArrayList<>();
        jdbc.query("SELECT t.total, p.* FROM (SELECT count(*) AS total FROM diagrams WHERE " + filter + ") t"
                + " LEFT JOIN LATERAL (SELECT " + SUMMARY_COLUMNS + " FROM diagrams WHERE " + filter
                + " ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset) p ON true", params, rs -> {
                    total[0] = rs.getLong("total");
                    if (rs.getObject("id") != null) {
                        items.add(summary(rs));
                    }
                });
        return new DiagramPage(items, total[0]);
    }

    private Optional<DiagramAccessInfo> accessInfo(String sql, UUID id) {
        return jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, n) -> new DiagramAccessInfo(
                rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getBoolean("is_public"),
                rs.getInt("version"))).stream().findFirst();
    }

    static DiagramSummary summary(ResultSet rs) throws SQLException {
        return new DiagramSummary(rs.getObject("id", UUID.class), rs.getString("title"),
                DiagramType.valueOf(rs.getString("diagram_type")), rs.getInt("version"), rs.getBoolean("is_public"),
                rs.getObject("owner_id", UUID.class), rs.getObject("updated_by", UUID.class),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Diagram fullDiagram(ResultSet rs) throws SQLException {
        return new Diagram(rs.getObject("id", UUID.class), rs.getString("title"),
                DiagramType.valueOf(rs.getString("diagram_type")), DiagramData.fromStorage(rs.getString("data")),
                rs.getInt("version"), rs.getBoolean("is_public"), rs.getObject("owner_id", UUID.class),
                rs.getObject("updated_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
