package com.diagramio.api.diagram.dto;

import com.diagramio.api.RawJsonSerializer;
import com.diagramio.domain.diagram.Diagram;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record DiagramResponse(
        UUID id,
        String title,
        String diagramType,
        @JsonSerialize(using = RawJsonSerializer.class)
        @Schema(types = "object", implementation = Object.class, example = "{\"nodes\": [], \"edges\": []}")
        String data,
        Integer version,
        boolean isPublic,
        UUID ownerId,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static DiagramResponse from(Diagram diagram) {
        return new DiagramResponse(diagram.getId(), diagram.getTitle(), diagram.getDiagramType().name(),
                diagram.getData().json(), diagram.getVersion(), diagram.isPublic(), diagram.getOwnerId(),
                diagram.getUpdatedBy(), diagram.getCreatedAt(), diagram.getUpdatedAt());
    }
}
