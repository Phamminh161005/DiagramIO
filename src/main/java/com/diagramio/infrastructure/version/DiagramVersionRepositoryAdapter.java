package com.diagramio.infrastructure.version;

import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.exception.ConflictException;
import com.diagramio.domain.exception.DiagramNotFoundException;
import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.domain.version.DiagramVersionRepository;
import com.diagramio.domain.version.DiagramVersionSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cài đặt DiagramVersionRepository bằng SQL. Snapshot chép data từ dòng sơ đồ sang bảng phiên bản ngay trong
 * CSDL (INSERT ... SELECT), thay vì đọc data lên app rồi gửi xuống lại; số thứ tự tính trong cùng câu lệnh.
 */
@Repository
public class DiagramVersionRepositoryAdapter implements DiagramVersionRepository {

    private static final String SUMMARY_COLUMNS = "id, version_number, note, created_by, created_at";

    private static final RowMapper<DiagramVersionSummary> SUMMARY = (rs, n) -> new DiagramVersionSummary(
            rs.getObject("id", UUID.class), rs.getInt("version_number"), rs.getString("note"),
            rs.getObject("created_by", UUID.class), instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public DiagramVersionRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DiagramVersion snapshot(UUID versionId, UUID diagramId, String note, UUID createdBy, Instant createdAt) {
        return insertFromDiagram(versionId, diagramId, note, createdBy, createdAt, "version_number, data",
                (rs, n) -> new DiagramVersion(versionId, diagramId, rs.getInt("version_number"),
                        DiagramData.fromStorage(rs.getString("data")), note, createdBy, createdAt));
    }

    @Override
    public DiagramVersionSummary snapshotSummary(UUID versionId, UUID diagramId, String note, UUID createdBy,
                                                 Instant createdAt) {
        return insertFromDiagram(versionId, diagramId, note, createdBy, createdAt, "version_number",
                (rs, n) -> new DiagramVersionSummary(versionId, rs.getInt("version_number"), note, createdBy,
                        createdAt));
    }

    @Override
    public List<DiagramVersionSummary> findSummaries(UUID diagramId) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM diagram_versions WHERE diagram_id = :diagramId"
                + " ORDER BY version_number DESC", new MapSqlParameterSource("diagramId", diagramId), SUMMARY);
    }

    @Override
    public Optional<DiagramVersionSummary> findSummary(UUID id, UUID diagramId) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM diagram_versions WHERE id = :id"
                + " AND diagram_id = :diagramId", ids(id, diagramId), SUMMARY).stream().findFirst();
    }

    @Override
    public Optional<DiagramVersion> findByIdAndDiagramId(UUID id, UUID diagramId) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + ", diagram_id, data FROM diagram_versions WHERE id = :id"
                + " AND diagram_id = :diagramId", ids(id, diagramId), (rs, n) -> new DiagramVersion(
                rs.getObject("id", UUID.class), rs.getObject("diagram_id", UUID.class), rs.getInt("version_number"),
                DiagramData.fromStorage(rs.getString("data")), rs.getString("note"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"))).stream().findFirst();
    }

    private <T> T insertFromDiagram(UUID versionId, UUID diagramId, String note, UUID createdBy, Instant createdAt,
                                    String returning, RowMapper<T> mapper) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", versionId)
                .addValue("diagramId", diagramId)
                .addValue("note", note)
                .addValue("createdBy", createdBy)
                .addValue("createdAt", createdAt.atOffset(ZoneOffset.UTC));
        try {
            return jdbc.query("""
                    INSERT INTO diagram_versions (id, diagram_id, version_number, data, note, created_by, created_at)
                    SELECT :id, d.id,
                           COALESCE((SELECT max(version_number) FROM diagram_versions WHERE diagram_id = d.id), 0) + 1,
                           d.data, :note, :createdBy, :createdAt
                    FROM diagrams d
                    WHERE d.id = :diagramId
                    RETURNING\s""" + returning, params, mapper).stream().findFirst()
                    .orElseThrow(() -> new DiagramNotFoundException("Diagram not found: " + diagramId));
        } catch (DuplicateKeyException ex) {
            // Lưới an toàn: bình thường dòng sơ đồ đã bị khóa nên các yêu cầu xếp hàng, không trùng số thứ tự
            throw new ConflictException("Another version was saved at the same time, please retry");
        }
    }

    private static MapSqlParameterSource ids(UUID id, UUID diagramId) {
        return new MapSqlParameterSource().addValue("id", id).addValue("diagramId", diagramId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
