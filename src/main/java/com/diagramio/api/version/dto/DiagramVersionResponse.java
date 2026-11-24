package com.diagramio.api.version.dto;

import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.api.RawJsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Chi tiết một phiên bản, kèm cấu trúc JSON của sơ đồ tại thời điểm đó. */
public record DiagramVersionResponse(UUID id, UUID diagramId, int versionNumber, String note,
                                     @JsonSerialize(using = RawJsonSerializer.class)
                                     @Schema(types = "object", implementation = Object.class, example = "{\"nodes\": [], \"edges\": []}")
                                     String data,
                                     UUID createdBy, Instant createdAt) {

    public static DiagramVersionResponse from(DiagramVersion v) {
        return new DiagramVersionResponse(v.id(), v.diagramId(), v.versionNumber(), v.note(), v.data().json(),
                v.createdBy(), v.createdAt());
    }
}
