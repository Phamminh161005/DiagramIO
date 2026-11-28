package com.diagramio.domain.version;

import java.time.Instant;
import java.util.UUID;

/** Một phiên bản không kèm data: dùng cho danh sách lịch sử và bản sao lưu tự động khi khôi phục. */
public record DiagramVersionSummary(UUID id, int versionNumber, String note, UUID createdBy, Instant createdAt) {
}
