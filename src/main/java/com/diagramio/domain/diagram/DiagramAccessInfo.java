package com.diagramio.domain.diagram;

import java.util.UUID;

/**
 * Bốn cột đủ để kiểm tra quyền trên một sơ đồ và chống ghi đè, không có cột data (tới 2 MB).
 * Thao tác chỉ cần biết "có được phép không" (sửa, xóa, chia sẻ, xem lịch sử) đọc record này thay vì cả sơ đồ.
 */
public record DiagramAccessInfo(UUID id, UUID ownerId, boolean isPublic, int version) {

    public boolean isOwner(UUID userId) {
        return ownerId != null && ownerId.equals(userId);
    }
}
