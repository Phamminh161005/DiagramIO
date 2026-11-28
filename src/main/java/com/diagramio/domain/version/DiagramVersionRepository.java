package com.diagramio.domain.version;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chỉ thêm và đọc: không có update/delete để giữ snapshot bất biến. Snapshot được chép thẳng từ dòng sơ đồ ngay
 * trong CSDL (INSERT ... SELECT), data không phải đi lên app rồi đi xuống lại.
 *
 * <p>Hai phương thức chụp phải được gọi trong transaction đã khóa dòng sơ đồ ({@code DiagramAccess.requireLocked}),
 * để số thứ tự tăng tuyến tính và snapshot là trạng thái mới nhất.
 */
public interface DiagramVersionRepository {

    /** Chụp data hiện tại của sơ đồ thành phiên bản số (lớn nhất + 1); trả về phiên bản kèm data. */
    DiagramVersion snapshot(UUID versionId, UUID diagramId, String note, UUID createdBy, Instant createdAt);

    /** Như {@link #snapshot} nhưng chỉ trả tóm tắt, khi không cần data (bản sao lưu trước khi khôi phục). */
    DiagramVersionSummary snapshotSummary(UUID versionId, UUID diagramId, String note, UUID createdBy,
                                          Instant createdAt);

    /** Các phiên bản của sơ đồ, mới nhất trước, không kèm data. */
    List<DiagramVersionSummary> findSummaries(UUID diagramId);

    Optional<DiagramVersionSummary> findSummary(UUID id, UUID diagramId);

    Optional<DiagramVersion> findByIdAndDiagramId(UUID id, UUID diagramId);
}
