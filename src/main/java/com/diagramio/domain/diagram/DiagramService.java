package com.diagramio.domain.diagram;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.DiagramAccess;
import com.diagramio.domain.access.Permission;
import com.diagramio.domain.exception.DiagramNotFoundException;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import java.time.Instant;
import java.util.UUID;

/** Domain rules for diagram management. This class intentionally has no framework dependency. */
public class DiagramService {

    public static final int MAX_PAGE_SIZE = 100;

    private final DiagramRepository repository;
    private final DiagramAccess access;

    public DiagramService(DiagramRepository repository) {
        this.repository = repository;
        this.access = new DiagramAccess(repository);
    }

    public Diagram createDiagram(Diagram diagram) {
        if (diagram == null) {
            throw new InvalidInputException("Diagram is required");
        }
        requireType(diagram.getDiagramType());
        if (diagram.getData() == null) {
            diagram.setData(DiagramData.EMPTY);
        }

        Instant now = Instant.now();
        diagram.setVersion(1);
        diagram.setPublic(false);
        diagram.setCreatedAt(now);
        diagram.setUpdatedAt(now);
        return repository.insert(diagram);
    }

    /**
     * Owner hoặc người cầm link edit được sửa. Chỉ owner được đổi trạng thái công khai;
     * người sửa qua link gửi isPublic khác thì giữ nguyên giá trị đang có trong CSDL.
     * updatedBy là người đăng nhập, NULL nếu sửa qua link mà không đăng nhập.
     *
     * <p>Kiểm tra quyền chỉ đọc 4 cột (không đọc data), rồi ghi bằng một câu UPDATE có điều kiện version: không cần
     * transaction, vì người khác ghi chen vào giữa thì version đã đổi và câu UPDATE không sửa gì (409).
     *
     * <p>Thông tin đọc lúc kiểm tra quyền có thể chậm hơn CSDL (đọc từ cache, instance khác vừa ghi) nhưng không bao
     * giờ đi trước CSDL. Vì vậy version gửi lên nhỏ hơn version đọc được thì chắc chắn đã cũ (409, không ghi); mọi
     * trường hợp khác do câu UPDATE quyết định, và câu UPDATE không ghi gì lấy từ lần đọc đó ngoài các cột không bao
     * giờ đổi (id, chủ).
     */
    public Diagram updateDiagram(UUID id, String title, DiagramType diagramType, DiagramData data,
                                 boolean isPublic, Caller caller, Integer requestVersion) {
        if (id == null) {
            throw new InvalidInputException("Diagram id is required");
        }
        DiagramAccessInfo current = access.require(id, caller, Permission.EDIT);
        rejectStaleVersion(id, requestVersion, current.version());
        requireType(diagramType);
        if (data == null) {
            throw new InvalidInputException("data must be valid JSON");
        }

        // Chỉ owner đổi được trạng thái công khai; người sửa qua link gửi giá trị khác thì bỏ qua
        boolean changePrivacy = Permission.MANAGE.isGrantedTo(caller, current);
        Diagram diagram = new Diagram(current.id(), null, null, null, requestVersion,
                changePrivacy ? isPublic : current.isPublic(), current.ownerId(), null, null, null);
        Instant now = Instant.now();
        diagram.updateMetadata(title, diagramType, caller.userId(), now);
        diagram.replaceContent(data, caller.userId(), now);
        return repository.updateIfVersion(diagram, changePrivacy).orElseThrow(() -> new OptimisticLockConflictException(
                id, requestVersion, repository.findAccessInfo(id).map(DiagramAccessInfo::version)
                        .orElseThrow(() -> new DiagramNotFoundException("Diagram not found: " + id))));
    }

    public void deleteDiagram(UUID id, Caller caller) {
        access.require(id, caller, Permission.MANAGE);
        repository.deleteById(id);
    }

    public Diagram getDiagramById(UUID id, Caller caller) {
        return access.requireDiagram(id, caller, Permission.VIEW);
    }

    /**
     * Kiểm tra quyền xem như {@link #getDiagramById} nhưng không đọc data; trả version hiện tại. Dùng cho GET có
     * điều kiện: client đã có bản đúng version này thì không cần gửi lại nội dung. Người không có quyền vẫn nhận
     * 401/403/404 như khi GET bình thường, nên không dò được version của sơ đồ riêng tư.
     */
    public DiagramAccessInfo getAccessInfo(UUID id, Caller caller) {
        return access.require(id, caller, Permission.VIEW);
    }

    /** Sơ đồ của tôi, mới sửa trước. diagramType null = mọi loại; title rỗng = không tìm theo tên. */
    public DiagramPage listMine(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        validatePage(page, size);
        return repository.findByOwner(ownerId, diagramType, normalizeFilter(title), page, size);
    }

    /** Sơ đồ công khai, mới sửa trước. */
    public DiagramPage listPublic(int page, int size) {
        validatePage(page, size);
        return repository.findPublic(page, size);
    }

    /** {@code knownVersion} là một cận dưới của version trong CSDL. */
    private void rejectStaleVersion(UUID id, Integer requestedVersion, int knownVersion) {
        if (requestedVersion == null || requestedVersion < knownVersion) {
            throw new OptimisticLockConflictException(id, requestedVersion, knownVersion);
        }
    }

    private void requireType(DiagramType diagramType) {
        if (diagramType == null) {
            throw new InvalidInputException("diagramType must be UML or ERD");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidInputException("page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
