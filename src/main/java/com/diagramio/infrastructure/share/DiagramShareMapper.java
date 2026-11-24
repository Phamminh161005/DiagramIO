package com.diagramio.infrastructure.share;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.share.DiagramShare;
import org.springframework.stereotype.Component;

@Component
public class DiagramShareMapper {

    public DiagramShare toDomain(DiagramShareEntity entity) {
        if (entity == null) {
            return null;
        }
        return new DiagramShare(
                entity.getId(),
                entity.getDiagramId(),
                entity.getShareToken(),
                ShareRole.from(entity.getRole()),
                entity.getCreatedAt()
        );
    }
}
