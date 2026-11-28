package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Trạng thái của một sơ đồ trong cache, bất biến. {@link Diagram} của domain là đối tượng sửa được (service gọi
 * {@code updateMetadata}, {@code replaceContent} trên nó), nên cache không bao giờ giữ hay trả chung một
 * {@code Diagram}: mỗi lần đọc nhận một bản mới từ {@link #toDiagram()}. {@link DiagramData} vốn bất biến nên
 * dùng chung được, sao chép rất rẻ.
 *
 * <p>Bia mộ ({@link #tombstone}) đánh dấu sơ đồ vừa bị xóa: có version lớn nhất nên không lần nạp chậm nào (đọc
 * từ CSDL trước khi xóa) ghi đè được, và đọc trúng bia mộ trả 404 mà không cần hỏi CSDL.
 */
public record CachedDiagram(UUID id, String title, DiagramType diagramType, DiagramData data, int version,
                            boolean isPublic, UUID ownerId, UUID updatedBy, Instant createdAt, Instant updatedAt,
                            boolean deleted) {

    /** Version của bia mộ: lớn hơn mọi version thật. */
    public static final int TOMBSTONE_VERSION = Integer.MAX_VALUE;

    public static CachedDiagram of(Diagram d) {
        return new CachedDiagram(d.getId(), d.getTitle(), d.getDiagramType(), d.getData(), d.getVersion(),
                d.isPublic(), d.getOwnerId(), d.getUpdatedBy(), d.getCreatedAt(), d.getUpdatedAt(), false);
    }

    public static CachedDiagram tombstone(UUID id) {
        return new CachedDiagram(id, null, null, null, TOMBSTONE_VERSION, false, null, null, null, null, true);
    }

    /** Một bản {@code Diagram} mới cho người gọi; rỗng nếu là bia mộ. */
    public Optional<Diagram> toDiagram() {
        if (deleted) {
            return Optional.empty();
        }
        return Optional.of(new Diagram(id, title, diagramType, data, version, isPublic, ownerId, updatedBy,
                createdAt, updatedAt));
    }

    /** Thông tin kiểm tra quyền; rỗng nếu là bia mộ. */
    public Optional<DiagramAccessInfo> accessInfo() {
        return deleted ? Optional.empty() : Optional.of(new DiagramAccessInfo(id, ownerId, isPublic, version));
    }

    /** Kích thước xấp xỉ trong heap (byte), để giới hạn cache theo dung lượng thay vì số mục. */
    public int weight() {
        long bytes = 256 + heapBytes(data == null ? null : data.json()) + heapBytes(title);
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    /**
     * Số byte một chuỗi chiếm trong heap. Java (compact strings) lưu 1 byte mỗi ký tự khi mọi ký tự thuộc Latin-1, chỉ
     * cần một ký tự ngoài Latin-1 (như "ư", "đ" của tiếng Việt) là cả chuỗi 2 byte mỗi ký tự. Đếm theo độ dài chuỗi
     * thôi thì cache sơ đồ tiếng Việt có thể chiếm gấp đôi giới hạn đã cấu hình.
     */
    public static long heapBytes(String value) {
        if (value == null) {
            return 0;
        }
        int length = value.length();
        for (int i = 0; i < length; i++) {
            if (value.charAt(i) > 0xFF) {
                return 2L * length;
            }
        }
        return length;
    }

    /** Độ dài của data (ký tự), để bỏ qua không cache sơ đồ quá lớn. */
    public int dataLength() {
        return data == null ? 0 : data.json().length();
    }
}
