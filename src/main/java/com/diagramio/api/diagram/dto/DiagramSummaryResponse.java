package com.diagramio.api.diagram.dto;

import com.diagramio.domain.diagram.DiagramSummary;
import java.time.Instant;
import java.util.UUID;

public record DiagramSummaryResponse(
        UUID id,
        String title,
        String diagramType,
        Integer version,
        boolean isPublic,
        UUID ownerId,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static DiagramSummaryResponse from(DiagramSummary diagram) {
        return new DiagramSummaryResponse(diagram.id(), diagram.title(), diagram.diagramType().name(),
                diagram.version(), diagram.isPublic(), diagram.ownerId(), diagram.updatedBy(),
                diagram.createdAt(), diagram.updatedAt());
    }
}
