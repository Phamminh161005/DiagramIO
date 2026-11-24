package com.diagramio.domain.access;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.exception.DiagramNotFoundException;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.UnauthorizedException;
import java.util.Optional;
import java.util.UUID;

/**
 * Một chỗ duy nhất trả lời "sơ đồ có tồn tại không và người gọi có quyền không" cho mọi module,
 * để sơ đồ, phiên bản và chia sẻ trả cùng mã lỗi:
 * <ul>
 *   <li>không có sơ đồ: 404;</li>
 *   <li>bị từ chối và người gọi không có tài khoản lẫn link của sơ đồ này: 401 (đăng nhập có thể đổi kết quả);</li>
 *   <li>bị từ chối khi đã biết người gọi (có tài khoản, hoặc link view mà đòi sửa): 403.</li>
 * </ul>
 * Có ba cách đọc, chọn theo việc thao tác cần gì:
 * <ul>
 *   <li>{@link #require}: chỉ đọc 4 cột (chủ, công khai, version), không đọc data. Dùng khi thao tác không cần
 *       nội dung sơ đồ: sửa, xóa, chia sẻ, xem lịch sử;</li>
 *   <li>{@link #requireLocked}: như trên nhưng khóa dòng sơ đồ tới hết transaction, để các yêu cầu lưu hay khôi
 *       phục phiên bản đồng thời xếp hàng. Phải gọi bên trong {@code TransactionRunner};</li>
 *   <li>{@link #requireDiagram}: đọc cả sơ đồ rồi kiểm tra, khi thao tác cần trả nội dung (xem sơ đồ).</li>
 * </ul>
 */
public class DiagramAccess {

    private final DiagramRepository diagramRepository;

    public DiagramAccess(DiagramRepository diagramRepository) {
        this.diagramRepository = diagramRepository;
    }

    /** Kiểm tra quyền mà không đọc data; trả về chủ, trạng thái công khai và version hiện tại. */
    public DiagramAccessInfo require(UUID diagramId, Caller caller, Permission permission) {
        DiagramAccessInfo info = found(diagramId, diagramId == null ? Optional.empty()
                : diagramRepository.findAccessInfo(diagramId));
        check(info, caller, permission);
        return info;
    }

    /** Như {@link #require} nhưng khóa dòng sơ đồ (SELECT ... FOR UPDATE) tới hết transaction hiện tại. */
    public DiagramAccessInfo requireLocked(UUID diagramId, Caller caller, Permission permission) {
        DiagramAccessInfo info = found(diagramId, diagramId == null ? Optional.empty()
                : diagramRepository.lockAccessInfo(diagramId));
        check(info, caller, permission);
        return info;
    }

    /** Đọc cả sơ đồ (kèm data) rồi kiểm tra quyền; trả về sơ đồ nếu được phép. */
    public Diagram requireDiagram(UUID diagramId, Caller caller, Permission permission) {
        Diagram diagram = found(diagramId, diagramId == null ? Optional.empty() : diagramRepository.findById(diagramId));
        check(diagram.accessInfo(), caller, permission);
        return diagram;
    }

    /** Kiểm tra quyền trên thông tin đã đọc sẵn. */
    public void check(DiagramAccessInfo diagram, Caller caller, Permission permission) {
        if (permission.isGrantedTo(caller, diagram)) {
            return;
        }
        if (!caller.hasIdentityFor(diagram)) {
            throw new UnauthorizedException("Authentication required");
        }
        throw new ForbiddenException(permission.deniedMessage());
    }

    private static <T> T found(UUID diagramId, Optional<T> value) {
        if (diagramId == null) {
            throw new DiagramNotFoundException("Diagram not found");
        }
        return value.orElseThrow(() -> new DiagramNotFoundException("Diagram not found: " + diagramId));
    }
}
