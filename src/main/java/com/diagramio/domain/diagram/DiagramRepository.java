package com.diagramio.domain.diagram;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng truy cập dữ liệu sơ đồ. Mỗi thao tác chỉ đọc và ghi đúng phần nó cần: kiểm tra quyền không đọc data,
 * danh sách không đọc data, sửa là một câu UPDATE có điều kiện, khôi phục chép data ngay trong CSDL.
 */
public interface DiagramRepository {

    /** Thêm sơ đồ mới (id do CSDL sinh). */
    Diagram insert(Diagram diagram);

    Optional<Diagram> findById(UUID id);

    /** Chủ, trạng thái công khai và version: đủ để kiểm tra quyền, không đọc data. */
    Optional<DiagramAccessInfo> findAccessInfo(UUID id);

    /** Như {@link #findAccessInfo} và khóa dòng sơ đồ tới hết transaction hiện tại (SELECT ... FOR UPDATE). */
    Optional<DiagramAccessInfo> lockAccessInfo(UUID id);

    /**
     * Ghi tên, loại, data, người sửa và thời điểm sửa của {@code diagram}, và trạng thái công khai nếu
     * {@code changePrivacy} (không thì giữ giá trị đang có trong CSDL), chỉ khi version trong CSDL vẫn là
     * {@code diagram.getVersion()}; version tăng 1. Trả trạng thái đã lưu. Rỗng nếu version đã đổi hoặc sơ đồ đã bị
     * xóa, khi đó không có gì bị ghi.
     */
    Optional<Diagram> updateIfVersion(Diagram diagram, boolean changePrivacy);

    /**
     * Chép data của phiên bản {@code versionId} vào sơ đồ ngay trong CSDL, ghi người và thời điểm sửa, version tăng 1.
     * Rỗng nếu phiên bản không thuộc sơ đồ này.
     */
    Optional<Diagram> restoreContent(UUID diagramId, UUID versionId, UUID changedBy, Instant changedAt);

    /** Sơ đồ của một người, mới sửa trước. diagramType null = mọi loại; title rỗng = không tìm theo tên. */
    DiagramPage findByOwner(UUID ownerId, DiagramType diagramType, String title, int page, int size);

    /** Sơ đồ công khai, mới sửa trước. */
    DiagramPage findPublic(int page, int size);

    void deleteById(UUID id);
}
