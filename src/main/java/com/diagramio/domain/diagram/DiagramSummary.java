package com.diagramio.domain.diagram;

import java.time.Instant;
import java.util.UUID;

/** Một dòng trong danh sách sơ đồ: mọi cột trừ data, để danh sách không phải đọc tới 2 MB mỗi sơ đồ. */
public record DiagramSummary(UUID id, String title, DiagramType diagramType, int version, boolean isPublic,
                             UUID ownerId, UUID updatedBy, Instant createdAt, Instant updatedAt) {
}
