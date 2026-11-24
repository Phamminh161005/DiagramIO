package com.diagramio.domain.auth;

import java.util.Optional;
import java.util.UUID;

/** Cổng truy cập dữ liệu refresh token; tầng infrastructure cài đặt bằng JPA. */
public interface RefreshTokenRepository {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Thêm token mới bằng một câu INSERT. */
    RefreshToken insert(RefreshToken refreshToken);

    /** Thu hồi bằng một câu UPDATE có điều kiện; false nếu token không có hoặc đã bị thu hồi trước đó. */
    boolean revoke(UUID id);
}
