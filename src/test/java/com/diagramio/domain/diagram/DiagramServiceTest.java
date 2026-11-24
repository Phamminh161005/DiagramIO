package com.diagramio.domain.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.DiagramNotFoundException;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import com.diagramio.domain.exception.UnauthorizedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiagramServiceTest {

    private static final DiagramData NEW_DATA = DiagramData.of("{\"nodes\": [{\"id\": \"n1\"}], \"edges\": []}");

    @Mock
    private DiagramRepository repository;

    private DiagramService service;
    private final UUID id = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DiagramService(repository);
    }

    @Test
    void createDiagramUsesDefaultDataAndPrivateVisibility() {
        when(repository.insert(any(Diagram.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Diagram diagram = new Diagram();
        diagram.setTitle("Orders");
        diagram.setDiagramType(DiagramType.ERD);
        diagram.setData(null);
        diagram.setPublic(true);

        Diagram created = service.createDiagram(diagram);

        assertEquals(DiagramData.EMPTY, created.getData());
        assertFalse(created.isPublic());
        assertEquals(1, created.getVersion());
        verify(repository).insert(diagram);
    }

    // ---- sửa: kiểm tra quyền không đọc data, ghi bằng UPDATE có điều kiện version ----

    @Test
    void updateWritesOnceWithTheCheckedVersionAndReturnsTheStoredState() {
        stubInfo(false, 3);
        Diagram stored = new Diagram(id, "Updated", DiagramType.UML, NEW_DATA, 4, false, ownerId, ownerId, null, null);
        ArgumentCaptor<Diagram> written = ArgumentCaptor.forClass(Diagram.class);
        when(repository.updateIfVersion(written.capture())).thenReturn(Optional.of(stored));

        Diagram updated = service.updateDiagram(id, "Updated", DiagramType.UML, NEW_DATA, false,
                Caller.user(ownerId), 3);

        assertSame(stored, updated);
        Diagram sent = written.getValue();
        assertEquals(3, sent.getVersion());   // version mong đợi trong WHERE; CSDL tăng lên 4
        assertEquals("Updated", sent.getTitle());
        assertEquals(DiagramType.UML, sent.getDiagramType());
        assertEquals(NEW_DATA, sent.getData());
        assertEquals(ownerId, sent.getUpdatedBy());
        assertEquals(ownerId, sent.getOwnerId());
        verify(repository, never()).findById(any());   // không đọc data cũ chỉ để kiểm tra quyền
    }

    @Test
    void updateRejectsStaleVersionWithoutWriting() {
        stubInfo(false, 3);

        OptimisticLockConflictException conflict = assertThrows(OptimisticLockConflictException.class,
                () -> service.updateDiagram(id, "Updated", DiagramType.UML, NEW_DATA, false, Caller.user(ownerId), 2));

        assertEquals(3, conflict.getCurrentVersion());
        verify(repository, never()).updateIfVersion(any());
    }

    @Test
    void updateRequiresVersion() {
        stubInfo(false, 3);

        assertThrows(OptimisticLockConflictException.class,
                () -> service.updateDiagram(id, "Updated", DiagramType.UML, NEW_DATA, false, Caller.user(ownerId),
                        null));
        verify(repository, never()).updateIfVersion(any());
    }

    @Test
    void updateLosingARaceIs409WithTheVersionThatWon() {
        // Cùng thấy version 3, người khác ghi xong trước: câu UPDATE không khớp dòng nào
        when(repository.findAccessInfo(id)).thenReturn(Optional.of(info(false, 3)), Optional.of(info(false, 4)));
        when(repository.updateIfVersion(any())).thenReturn(Optional.empty());

        OptimisticLockConflictException conflict = assertThrows(OptimisticLockConflictException.class,
                () -> service.updateDiagram(id, "Updated", DiagramType.UML, NEW_DATA, false, Caller.user(ownerId), 3));

        assertEquals(3, conflict.getRequestedVersion());
        assertEquals(4, conflict.getCurrentVersion());
    }

    @Test
    void updateOfADiagramDeletedMeanwhileIs404() {
        when(repository.findAccessInfo(id)).thenReturn(Optional.of(info(false, 3)), Optional.empty());
        when(repository.updateIfVersion(any())).thenReturn(Optional.empty());

        assertThrows(DiagramNotFoundException.class,
                () -> service.updateDiagram(id, "Updated", DiagramType.UML, NEW_DATA, false, Caller.user(ownerId), 3));
    }

    // Luật cấu trúc data (nodes/edges, 2 MB) nằm trong DiagramData, xem DiagramDataTest.
    @Test
    void updateDiagramRequiresData() {
        stubInfo(false, 1);

        assertThrows(InvalidInputException.class, () -> service.updateDiagram(
                id, "Orders", DiagramType.ERD, null, false, Caller.user(ownerId), 1));
        verify(repository, never()).updateIfVersion(any());
    }

    @Test
    void updateOfMissingDiagramIs404() {
        when(repository.findAccessInfo(id)).thenReturn(Optional.empty());

        assertThrows(DiagramNotFoundException.class, () -> service.updateDiagram(
                id, "Orders", DiagramType.ERD, NEW_DATA, false, Caller.user(ownerId), 1));
    }

    // ---- danh sách ----

    @Test
    void pageSizeIsLimited() {
        assertThrows(InvalidInputException.class,
                () -> service.listMine(ownerId, null, null, 0, DiagramService.MAX_PAGE_SIZE + 1));
        assertThrows(InvalidInputException.class, () -> service.listPublic(0, 0));
        assertThrows(InvalidInputException.class, () -> service.listPublic(-1, 20));
        verify(repository, never()).findPublic(anyInt(), anyInt());
    }

    @Test
    void listMineTrimsTheTitleFilterAndTreatsBlankAsNoFilter() {
        DiagramPage page = new DiagramPage(List.of(), 0);
        when(repository.findByOwner(ownerId, DiagramType.ERD, "orders", 1, 10)).thenReturn(page);
        when(repository.findByOwner(ownerId, null, "", 0, 20)).thenReturn(page);

        assertSame(page, service.listMine(ownerId, DiagramType.ERD, "  orders ", 1, 10));
        assertSame(page, service.listMine(ownerId, null, "   ", 0, 20));
    }

    @Test
    void listPublicReturnsTheRepositoryPage() {
        DiagramPage page = new DiagramPage(List.of(), 7);
        when(repository.findPublic(2, 5)).thenReturn(page);

        assertSame(page, service.listPublic(2, 5));
    }

    // ---- phân quyền sửa ----

    @Test
    void updateDiagramRejectsStranger() {
        stubInfo(false, 1);

        assertThrows(ForbiddenException.class, () -> service.updateDiagram(
                id, "Hacked", DiagramType.ERD, DiagramData.EMPTY, false, Caller.user(UUID.randomUUID()), 1));

        verify(repository, never()).updateIfVersion(any());
    }

    @Test
    void updateDiagramRejectsViewShareToken() {
        stubInfo(false, 1);

        assertThrows(ForbiddenException.class, () -> service.updateDiagram(
                id, "Updated", DiagramType.ERD, DiagramData.EMPTY, false,
                Caller.shareToken(id, ShareRole.VIEW), 1));

        verify(repository, never()).updateIfVersion(any());
    }

    @Test
    void anonymousUpdateOfPublicDiagramIs401() {
        stubInfo(true, 1);

        assertThrows(UnauthorizedException.class, () -> service.updateDiagram(
                id, "Updated", DiagramType.ERD, DiagramData.EMPTY, false, Caller.anonymous(), 1));
        verify(repository, never()).updateIfVersion(any());
    }

    @Test
    void editShareTokenCanUpdateButCannotChangeVisibility() {
        stubInfo(false, 1);
        when(repository.updateIfVersion(any(Diagram.class))).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        Diagram updated = service.updateDiagram(id, "Updated", DiagramType.ERD, DiagramData.EMPTY, true,
                Caller.shareToken(id, ShareRole.EDIT), 1);

        assertEquals("Updated", updated.getTitle());
        assertFalse(updated.isPublic());       // chỉ owner được công khai sơ đồ
        assertNull(updated.getUpdatedBy());    // sửa qua link, không đăng nhập
    }

    @Test
    void editShareTokenKeepsAPublicDiagramPublic() {
        stubInfo(true, 1);
        when(repository.updateIfVersion(any(Diagram.class))).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        Diagram updated = service.updateDiagram(id, "Updated", DiagramType.ERD, DiagramData.EMPTY, false,
                Caller.shareToken(id, ShareRole.EDIT), 1);

        assertTrue(updated.isPublic());
    }

    @Test
    void loggedInEditorIsRecordedAsUpdatedBy() {
        UUID editorId = UUID.randomUUID();
        stubInfo(false, 1);
        when(repository.updateIfVersion(any(Diagram.class))).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        Diagram updated = service.updateDiagram(id, "Updated", DiagramType.ERD, DiagramData.EMPTY, false,
                new Caller(editorId, id, ShareRole.EDIT), 1);

        assertEquals(editorId, updated.getUpdatedBy());
    }

    @Test
    void ownerCanMakeDiagramPublicAndPrivateAgain() {
        stubInfo(false, 1);
        when(repository.updateIfVersion(any(Diagram.class))).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        Diagram updated = service.updateDiagram(id, "Orders", DiagramType.ERD, DiagramData.EMPTY, true,
                Caller.user(ownerId), 1);

        assertTrue(updated.isPublic());
        assertEquals(ownerId, updated.getUpdatedBy());
    }

    // ---- phân quyền xem ----

    @Test
    void getDiagramByIdRejectsAnonymousForPrivateDiagramWith401() {
        when(repository.findById(id)).thenReturn(Optional.of(diagram(false)));

        // Khách (không tài khoản, không link): đăng nhập có thể đổi kết quả nên là 401, không phải 403
        assertThrows(UnauthorizedException.class,
                () -> service.getDiagramById(id, Caller.anonymous()));
    }

    @Test
    void getDiagramByIdAllowsViewShareTokenAndPublicDiagram() {
        Diagram diagram = diagram(false);
        when(repository.findById(id)).thenReturn(Optional.of(diagram));

        assertSame(diagram, service.getDiagramById(id, Caller.shareToken(id, ShareRole.VIEW)));

        diagram.setPublic(true);
        assertSame(diagram, service.getDiagramById(id, Caller.anonymous()));
    }

    @Test
    void deleteDiagramRejectsNonOwner() {
        stubInfo(false, 1);

        assertThrows(ForbiddenException.class,
                () -> service.deleteDiagram(id, Caller.user(UUID.randomUUID())));
        // link edit được sửa nhưng không được xóa
        assertThrows(ForbiddenException.class,
                () -> service.deleteDiagram(id, Caller.shareToken(id, ShareRole.EDIT)));

        verify(repository, never()).deleteById(id);
    }

    @Test
    void ownerCanDeleteDiagram() {
        stubInfo(false, 1);

        service.deleteDiagram(id, Caller.user(ownerId));

        verify(repository).deleteById(id);
        verify(repository, never()).findById(any());
    }

    private void stubInfo(boolean isPublic, int version) {
        when(repository.findAccessInfo(id)).thenReturn(Optional.of(info(isPublic, version)));
    }

    private DiagramAccessInfo info(boolean isPublic, int version) {
        return new DiagramAccessInfo(id, ownerId, isPublic, version);
    }

    private Diagram diagram(boolean isPublic) {
        return new Diagram(id, "Orders", DiagramType.ERD, DiagramData.EMPTY, 1, isPublic, ownerId, null, null, null);
    }
}
