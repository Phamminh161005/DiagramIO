package com.diagramio.domain.user;

import java.util.Optional;
import java.util.UUID;

/** Cổng truy cập dữ liệu người dùng; tầng infrastructure cài đặt bằng JPA. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    /** Thêm người dùng mới bằng một câu INSERT. */
    User insert(User user);

    /** Ghi đè người dùng đã có (đổi mật khẩu). */
    User save(User user);
}
