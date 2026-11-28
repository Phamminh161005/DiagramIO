package com.diagramio.domain.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.diagramio.domain.TransactionRunner;
import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.NotFoundException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DiagramVersionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final DiagramData CURRENT_DATA = DiagramData.fromStorage("{\"nodes\": [{\"id\": \"current\"}], \"edges\": []}");
    private static final DiagramData OLD_DATA = DiagramData.fromStorage("{\"nodes\": [{\"id\": \"old\"}], \"edges\": []}");

    private final DiagramRepository diagramRepository = mock(DiagramRepository.class);
    private final DiagramVersionRepository versionRepository = mock(DiagramVersionRepository.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final DiagramVersionService service = new DiagramVersionService(diagramRepository, versionRepository,
            transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID ownerId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        stubDiagram(false, 5);
        // Adapter chép data trong CSDL; giả lập lại kết quả nó trả về
        when(versionRepository.snapshot(any(), eq(diagramId), any(), any(), any())).thenAnswer(inv ->
                new DiagramVersion(inv.getArgument(0), diagramId, 4, CURRENT_DATA, inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4)));
        when(versionRepository.snapshotSummary(any(), eq(diagramId), any(), any(), any())).thenAnswer(inv ->
                new DiagramVersionSummary(inv.getArgument(0), 8, inv.getArgument(2), inv.getArgument(3),
                        inv.getArgument(4)));
    }

    // ---------- createVersion ----------

    @Test
    void ownerCreatesSnapshotInsideATransactionAfterLockingTheRow() {
        DiagramVersion created = service.createVersion(diagramId, "Bản thiết kế ERD Pha 1", null, Caller.user(ownerId));

        assertThat(created.diagramId()).isEqualTo(diagramId);
        assertThat(created.data()).isEqualTo(CURRENT_DATA);
        assertThat(created.note()).isEqualTo("Bản thiết kế ERD Pha 1");
        assertThat(created.createdBy()).isEqualTo(ownerId);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.id()).isNotNull();
        assertThat(transactions.count).isEqualTo(1);

        InOrder order = inOrder(diagramRepository, versionRepository);
        order.verify(diagramRepository).lockAccessInfo(diagramId);
        order.verify(versionRepository).snapshot(any(), eq(diagramId), eq("Bản thiết kế ERD Pha 1"), eq(ownerId),
                eq(NOW));
        // không kéo data lên app: không đọc cả sơ đồ, data chép ngay trong CSDL
        verify(diagramRepository, never()).findById(any());
        verify(diagramRepository, never()).findAccessInfo(any());
    }

    @Test
    void snapshotWithMatchingVersionSucceeds() {
        DiagramVersion created = service.createVersion(diagramId, "Bản của An", 5, Caller.user(ownerId));

        assertThat(created.data()).isEqualTo(CURRENT_DATA);
    }

    @Test
    void snapshotRejectsStaleVersionSoItNeverCapturesSomeoneElsesEdit() {
        // client vừa PUT xong và thấy version 4, nhưng người khác đã sửa chen vào (hiện là 5)
        assertThatThrownBy(() -> service.createVersion(diagramId, "Bản của An", 4, Caller.user(ownerId)))
                .isInstanceOf(OptimisticLockConflictException.class);
        verify(versionRepository, never()).snapshot(any(), any(), any(), any(), any());
    }

    @Test
    void editShareCanCreateSnapshotWithoutUser() {
        DiagramVersion created = service.createVersion(diagramId, "edit link", null,
                Caller.shareToken(diagramId, ShareRole.EDIT));

        assertThat(created.createdBy()).isNull();
        verify(versionRepository).snapshot(any(), eq(diagramId), eq("edit link"), isNull(), eq(NOW));
    }

    @Test
    void viewShareCannotCreateSnapshot() {
        assertThatThrownBy(() -> service.createVersion(diagramId, "x", null, Caller.shareToken(diagramId, ShareRole.VIEW)))
                .isInstanceOf(ForbiddenException.class);
        verify(versionRepository, never()).snapshot(any(), any(), any(), any(), any());
    }

    @Test
    void strangerCannotCreateSnapshotEvenOnPublicDiagram() {
        stubDiagram(true, 5);

        assertThatThrownBy(() -> service.createVersion(diagramId, "x", null, Caller.user(UUID.randomUUID())))
                .isInstanceOf(ForbiddenException.class);
        verify(versionRepository, never()).snapshot(any(), any(), any(), any(), any());
    }

    @Test
    void noteLongerThan255IsRejectedBeforeOpeningATransaction() {
        String note = "a".repeat(256);

        assertThatThrownBy(() -> service.createVersion(diagramId, note, null, Caller.user(ownerId)))
                .isInstanceOf(InvalidInputException.class);
        assertThat(transactions.count).isZero();
        verifyNoInteractions(versionRepository);
    }

    @Test
    void createOnMissingDiagramIs404() {
        UUID missing = UUID.randomUUID();
        when(diagramRepository.lockAccessInfo(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createVersion(missing, "x", null, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------- list / get: không transaction, không khóa ----------

    @Test
    void viewerCanListVersionsWithoutTheirData() {
        DiagramVersionSummary v = summary(UUID.randomUUID(), 1);
        when(versionRepository.findSummaries(diagramId)).thenReturn(List.of(v));

        assertThat(service.listVersions(diagramId, Caller.shareToken(diagramId, ShareRole.VIEW))).containsExactly(v);
        assertThat(transactions.count).isZero();
        verify(diagramRepository, never()).lockAccessInfo(any());
    }

    @Test
    void strangerCannotListVersionsOfPublicDiagram() {
        stubDiagram(true, 5);

        assertThatThrownBy(() -> service.listVersions(diagramId, Caller.user(UUID.randomUUID())))
                .isInstanceOf(ForbiddenException.class);
        verify(versionRepository, never()).findSummaries(any());
    }

    @Test
    void getVersionReturnsSnapshot() {
        UUID versionId = UUID.randomUUID();
        DiagramVersion v = version(versionId, 2, OLD_DATA);
        when(versionRepository.findByIdAndDiagramId(versionId, diagramId)).thenReturn(Optional.of(v));

        assertThat(service.getVersion(diagramId, versionId, Caller.user(ownerId))).isEqualTo(v);
    }

    @Test
    void versionOfAnotherDiagramIs404() {
        UUID versionId = UUID.randomUUID();
        when(versionRepository.findByIdAndDiagramId(versionId, diagramId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVersion(diagramId, versionId, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------- restore ----------

    @Test
    void restoreLocksThenBacksUpCurrentStateThenCopiesSnapshotInOneTransaction() {
        UUID versionId = UUID.randomUUID();
        when(versionRepository.findSummary(versionId, diagramId)).thenReturn(Optional.of(summary(versionId, 2)));
        Diagram restored = new Diagram(diagramId, "Shop ERD", DiagramType.ERD, OLD_DATA, 6, false, ownerId, ownerId,
                NOW.minusSeconds(3600), NOW);
        when(diagramRepository.restoreContent(diagramId, versionId, ownerId, NOW)).thenReturn(Optional.of(restored));

        RestoreResult result = service.restore(diagramId, versionId, Caller.user(ownerId));

        InOrder order = inOrder(diagramRepository, versionRepository);
        order.verify(diagramRepository).lockAccessInfo(diagramId);
        order.verify(versionRepository).findSummary(versionId, diagramId);
        order.verify(versionRepository).snapshotSummary(any(), eq(diagramId),
                eq("Auto backup before restoring version 2"), eq(ownerId), eq(NOW));
        order.verify(diagramRepository).restoreContent(diagramId, versionId, ownerId, NOW);
        assertThat(transactions.count).isEqualTo(1);

        assertThat(result.diagram()).isSameAs(restored);
        assertThat(result.backup().versionNumber()).isEqualTo(8);
        assertThat(result.backup().note()).isEqualTo("Auto backup before restoring version 2");
        assertThat(result.backup().createdAt()).isEqualTo(NOW);
        verify(diagramRepository, never()).findById(any());
        verify(versionRepository, never()).findByIdAndDiagramId(any(), any());
    }

    @Test
    void editShareCanRestore() {
        UUID versionId = UUID.randomUUID();
        when(versionRepository.findSummary(versionId, diagramId)).thenReturn(Optional.of(summary(versionId, 1)));
        Diagram restored = new Diagram(diagramId, "Shop ERD", DiagramType.ERD, OLD_DATA, 6, false, ownerId, null,
                null, NOW);
        when(diagramRepository.restoreContent(diagramId, versionId, null, NOW)).thenReturn(Optional.of(restored));

        RestoreResult result = service.restore(diagramId, versionId, Caller.shareToken(diagramId, ShareRole.EDIT));

        assertThat(result.diagram().getUpdatedBy()).isNull();
        assertThat(result.backup().createdBy()).isNull();
    }

    @Test
    void viewerCannotRestoreAndNothingIsWritten() {
        UUID versionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.restore(diagramId, versionId, Caller.shareToken(diagramId, ShareRole.VIEW)))
                .isInstanceOf(ForbiddenException.class);
        verifyNothingWritten();
    }

    @Test
    void restoringMissingVersionIs404AndNothingIsWritten() {
        UUID versionId = UUID.randomUUID();
        when(versionRepository.findSummary(versionId, diagramId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(diagramId, versionId, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class);
        verifyNothingWritten();
    }

    private void verifyNothingWritten() {
        verify(versionRepository, never()).snapshotSummary(any(), any(), any(), any(), any());
        verify(diagramRepository, never()).restoreContent(any(), any(), any(), any());
    }

    private void stubDiagram(boolean isPublic, int version) {
        DiagramAccessInfo info = new DiagramAccessInfo(diagramId, ownerId, isPublic, version);
        when(diagramRepository.findAccessInfo(diagramId)).thenReturn(Optional.of(info));
        when(diagramRepository.lockAccessInfo(diagramId)).thenReturn(Optional.of(info));
    }

    private DiagramVersion version(UUID id, int number, DiagramData data) {
        return new DiagramVersion(id, diagramId, number, data, "note " + number, ownerId, NOW.minusSeconds(600));
    }

    private DiagramVersionSummary summary(UUID id, int number) {
        return new DiagramVersionSummary(id, number, "note " + number, ownerId, NOW.minusSeconds(600));
    }

    /** Chạy ngay trong luồng hiện tại và đếm số transaction đã mở. */
    private static final class RecordingTransactions implements TransactionRunner {
        int count;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            count++;
            return work.get();
        }
    }
}
