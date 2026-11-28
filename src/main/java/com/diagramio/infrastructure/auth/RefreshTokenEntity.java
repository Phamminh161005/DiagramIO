package com.diagramio.infrastructure.auth;

import com.diagramio.domain.auth.RefreshToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Ánh xạ bảng refresh_tokens. Giữ user_id dạng UUID, không map quan hệ cho đơn giản.
 * Cờ {@code inserting} cho Spring Data gọi persist (một câu INSERT) thay vì merge khi thêm token mới.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Transient
    private boolean inserting;

    protected RefreshTokenEntity() {
        // JPA cần constructor không tham số
    }

    static RefreshTokenEntity fromDomain(RefreshToken token) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.id = token.id();
        entity.userId = token.userId();
        entity.tokenHash = token.tokenHash();
        entity.expiresAt = token.expiresAt();
        entity.revoked = token.revoked();
        return entity;
    }

    static RefreshTokenEntity forInsert(RefreshToken token) {
        RefreshTokenEntity entity = fromDomain(token);
        entity.inserting = true;
        return entity;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return inserting;
    }

    RefreshToken toDomain() {
        return new RefreshToken(id, userId, tokenHash, expiresAt, revoked);
    }
}
