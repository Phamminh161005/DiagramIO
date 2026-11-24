package com.diagramio.infrastructure.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** Thu hồi có điều kiện: chỉ đổi được token chưa bị thu hồi, nên hai request dùng cùng token chỉ một thắng. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshTokenEntity t set t.revoked = true where t.id = :id and t.revoked = false")
    int revoke(@Param("id") UUID id);
}
