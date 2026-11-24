package com.diagramio.infrastructure.share;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.share.DiagramShareRepository;
import com.diagramio.domain.share.ShareTokenTakenException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cài đặt DiagramShareRepository: đọc bằng JPA, ghi bằng một câu SQL mỗi thao tác (thêm không qua merge, đổi quyền
 * trả luôn dòng đã đổi, thu hồi có điều kiện thuộc sơ đồ).
 */
@Repository
public class DiagramShareRepositoryAdapter implements DiagramShareRepository {

    private final DiagramShareJpaRepository jpaRepository;
    private final DiagramShareMapper mapper;
    private final NamedParameterJdbcTemplate jdbc;

    public DiagramShareRepositoryAdapter(DiagramShareJpaRepository jpaRepository, DiagramShareMapper mapper,
                                         NamedParameterJdbcTemplate jdbc) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Override
    public List<DiagramShare> findByDiagramId(UUID diagramId) {
        return jpaRepository.findByDiagramId(diagramId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<DiagramShare> findByShareToken(String shareToken) {
        return jpaRepository.findByShareToken(shareToken).map(mapper::toDomain);
    }

    @Override
    public DiagramShare insert(DiagramShare share) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", share.getId())
                .addValue("diagramId", share.getDiagramId())
                .addValue("token", share.getShareToken())
                .addValue("role", share.getRole().value())
                .addValue("createdAt", share.getCreatedAt().atOffset(ZoneOffset.UTC));
        try {
            jdbc.update("INSERT INTO diagram_shares (id, diagram_id, share_token, role, created_at)"
                    + " VALUES (:id, :diagramId, :token, :role, :createdAt)", params);
        } catch (DuplicateKeyException ex) {
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("share_token")) {
                throw new ShareTokenTakenException();
            }
            throw ex;
        }
        return share;
    }

    @Override
    public Optional<DiagramShare> updateRole(UUID shareId, UUID diagramId, ShareRole role) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", shareId)
                .addValue("diagramId", diagramId)
                .addValue("role", role.value());
        return jdbc.query("UPDATE diagram_shares SET role = :role WHERE id = :id AND diagram_id = :diagramId"
                + " RETURNING id, diagram_id, share_token, role, created_at", params, (rs, n) -> new DiagramShare(
                rs.getObject("id", UUID.class), rs.getObject("diagram_id", UUID.class), rs.getString("share_token"),
                ShareRole.from(rs.getString("role")), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst();
    }

    @Override
    public boolean deleteByIdAndDiagramId(UUID shareId, UUID diagramId) {
        return jdbc.update("DELETE FROM diagram_shares WHERE id = :id AND diagram_id = :diagramId",
                new MapSqlParameterSource().addValue("id", shareId).addValue("diagramId", diagramId)) == 1;
    }
}
