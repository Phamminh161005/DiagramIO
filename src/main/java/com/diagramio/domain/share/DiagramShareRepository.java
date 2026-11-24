package com.diagramio.domain.share;

import com.diagramio.domain.access.ShareRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface kho lưu trữ cho liên kết chia sẻ sơ đồ.
 * Thuần Java, được implement ở tầng infrastructure.
 */
public interface DiagramShareRepository {

    List<DiagramShare> findByDiagramId(UUID diagramId);

    Optional<DiagramShare> findByShareToken(String shareToken);

    /**
     * Thêm link mới bằng một câu INSERT. Token trùng (ràng buộc UNIQUE) thì ném {@link ShareTokenTakenException}
     * để service sinh token khác, thay vì kiểm tra trước bằng một câu SELECT ở mọi lần tạo link.
     */
    DiagramShare insert(DiagramShare share);

    /**
     * Đổi quyền bằng một câu UPDATE có điều kiện và trả link sau khi đổi. Rỗng nếu link không còn (đã thu hồi)
     * hoặc không thuộc sơ đồ này; không bao giờ tạo lại link đã bị thu hồi.
     */
    Optional<DiagramShare> updateRole(UUID shareId, UUID diagramId, ShareRole role);

    /** Thu hồi bằng một câu DELETE có điều kiện; false nếu link không có hoặc thuộc sơ đồ khác. */
    boolean deleteByIdAndDiagramId(UUID shareId, UUID diagramId);
}
