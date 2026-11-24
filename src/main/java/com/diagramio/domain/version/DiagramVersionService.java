package com.diagramio.domain.version;

import com.diagramio.domain.TransactionRunner;
import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.DiagramAccess;
import com.diagramio.domain.access.Permission;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.NotFoundException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lưu phiên bản (snapshot) và khôi phục sơ đồ về một phiên bản cũ.
 * Lưu/khôi phục khóa dòng sơ đồ trước khi đọc, nên các yêu cầu đồng thời được xếp hàng
 * và xử lý lần lượt: số thứ tự phiên bản tăng tuyến tính, snapshot luôn chụp trạng thái mới nhất.
 * Data được chép ngay trong CSDL (bảng sơ đồ sang bảng phiên bản và ngược lại), không đi qua app.
 */
public class DiagramVersionService {

    public static final int MAX_NOTE_LENGTH = 255;

    private final DiagramRepository diagramRepository;
    private final DiagramAccess access;
    private final DiagramVersionRepository versionRepository;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public DiagramVersionService(DiagramRepository diagramRepository,
                                 DiagramVersionRepository versionRepository,
                                 TransactionRunner transactionRunner,
                                 Clock clock) {
        this.diagramRepository = diagramRepository;
        this.access = new DiagramAccess(diagramRepository);
        this.versionRepository = versionRepository;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    public List<DiagramVersionSummary> listVersions(UUID diagramId, Caller caller) {
        access.require(diagramId, caller, Permission.VIEW_HISTORY);
        return versionRepository.findSummaries(diagramId);
    }

    public DiagramVersion getVersion(UUID diagramId, UUID versionId, Caller caller) {
        access.require(diagramId, caller, Permission.VIEW_HISTORY);
        return versionRepository.findByIdAndDiagramId(versionId, diagramId)
                .orElseThrow(() -> versionNotFound(versionId));
    }

    /**
     * expectedVersion (không bắt buộc) là version sơ đồ mà client đang thấy, ví dụ version vừa nhận
     * sau khi PUT. Nếu người khác đã sửa chen vào giữa hai lần gọi thì trả 409 thay vì chụp nhầm
     * nội dung của họ. Kiểm tra sau khi khóa dòng nên không ai chen được giữa lúc so và lúc chụp.
     */
    public DiagramVersion createVersion(UUID diagramId, String note, Integer expectedVersion, Caller caller) {
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new InvalidInputException("note must not exceed " + MAX_NOTE_LENGTH + " characters");
        }
        return transactionRunner.inTransaction(() -> {
            DiagramAccessInfo diagram = access.requireLocked(diagramId, caller, Permission.EDIT);
            if (expectedVersion != null && expectedVersion != diagram.version()) {
                throw new OptimisticLockConflictException(diagramId, expectedVersion, diagram.version());
            }
            return versionRepository.snapshot(UUID.randomUUID(), diagramId, note, caller.userId(), Instant.now(clock));
        });
    }

    /**
     * Sao lưu trạng thái hiện tại thành một snapshot, rồi chép dữ liệu của phiên bản được chọn
     * vào sơ đồ. Cả hai bước nằm trong cùng một transaction, sau khi đã khóa dòng sơ đồ;
     * version của sơ đồ tăng thêm 1, giống luồng cập nhật sơ đồ.
     */
    public RestoreResult restore(UUID diagramId, UUID versionId, Caller caller) {
        return transactionRunner.inTransaction(() -> {
            access.requireLocked(diagramId, caller, Permission.EDIT);
            DiagramVersionSummary target = versionRepository.findSummary(versionId, diagramId)
                    .orElseThrow(() -> versionNotFound(versionId));

            Instant now = Instant.now(clock);
            DiagramVersionSummary backup = versionRepository.snapshotSummary(UUID.randomUUID(), diagramId,
                    "Auto backup before restoring version " + target.versionNumber(), caller.userId(), now);
            Diagram restored = diagramRepository.restoreContent(diagramId, versionId, caller.userId(), now)
                    .orElseThrow(() -> versionNotFound(versionId));
            return new RestoreResult(restored, backup);
        });
    }

    private static NotFoundException versionNotFound(UUID versionId) {
        return new NotFoundException("Version not found: " + versionId);
    }
}
