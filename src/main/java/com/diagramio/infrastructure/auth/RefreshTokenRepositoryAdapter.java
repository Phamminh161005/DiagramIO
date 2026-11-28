package com.diagramio.infrastructure.auth;

import com.diagramio.domain.auth.RefreshToken;
import com.diagramio.domain.auth.RefreshTokenRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Cài đặt RefreshTokenRepository của domain bằng Spring Data JPA. */
@Repository
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    public RefreshTokenRepositoryAdapter(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(RefreshTokenEntity::toDomain);
    }

    @Override
    public RefreshToken insert(RefreshToken refreshToken) {
        return jpaRepository.save(RefreshTokenEntity.forInsert(refreshToken)).toDomain();
    }

    @Override
    public boolean revoke(UUID id) {
        return jpaRepository.revoke(id) == 1;
    }
}
