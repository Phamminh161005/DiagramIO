package com.diagramio.domain.share;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.NotFoundException;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

    @Mock
    private DiagramShareRepository shareRepository;

    @Mock
    private DiagramRepository diagramRepository;

    private ShareService shareService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();

    private Diagram sampleDiagram;

    @BeforeEach
    void setUp() {
        shareService = new ShareService(shareRepository, diagramRepository);
        sampleDiagram = new Diagram(
                diagramId,
                "Sơ đồ lớp BTL",
                DiagramType.UML,
                DiagramData.fromStorage("{\"nodes\":[], \"edges\":[]}"),
                1,
                false,
                ownerId,
                ownerId,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("Chủ sở hữu có thể lấy danh sách liên kết chia sẻ")
    void getShares_OwnerSuccess() {
        stubOwnerInfo();
        DiagramShare share = new DiagramShare(UUID.randomUUID(), diagramId, "token123", ShareRole.VIEW, Instant.now());
        when(shareRepository.findByDiagramId(diagramId)).thenReturn(List.of(share));

        List<DiagramShare> result = shareService.getShares(diagramId, Caller.user(ownerId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getShareToken()).isEqualTo("token123");
    }

    @Test
    @DisplayName("Người dùng khác cố lấy danh sách chia sẻ sẽ bị 403 Forbidden")
    void getShares_ForbiddenForNonOwner() {
        stubOwnerInfo();

        assertThatThrownBy(() -> shareService.getShares(diagramId, Caller.user(otherUserId)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the diagram owner");
    }

    @Test
    @DisplayName("Người cầm link edit vẫn không được quản lý liên kết (chỉ owner)")
    void createShare_ForbiddenForEditShareToken() {
        stubOwnerInfo();

        assertThatThrownBy(() -> shareService.createShare(diagramId, ShareRole.EDIT,
                Caller.shareToken(diagramId, ShareRole.EDIT)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Sơ đồ không tồn tại sẽ bị 404 NotFound")
    void getShares_DiagramNotFound() {
        when(diagramRepository.findAccessInfo(diagramId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.getShares(diagramId, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Chủ sở hữu tạo liên kết chia sẻ thành công với quyền view")
    void createShare_Success() {
        stubOwnerInfo();
        when(shareRepository.insert(any(DiagramShare.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DiagramShare created = shareService.createShare(diagramId, ShareRole.VIEW, Caller.user(ownerId));

        assertThat(created).isNotNull();
        assertThat(created.getDiagramId()).isEqualTo(diagramId);
        assertThat(created.getRole()).isEqualTo(ShareRole.VIEW);
        assertThat(created.getShareToken()).matches("[0-9a-f]{48}");
        verify(shareRepository).insert(any(DiagramShare.class));
        verify(shareRepository, never()).findByShareToken(any());   // trùng token do ràng buộc UNIQUE bắt
        verify(diagramRepository, never()).findById(any());          // kiểm tra chủ không đọc data
    }

    @Test
    @DisplayName("Token trùng (UNIQUE) thì sinh token khác và thử lại")
    void createShare_RetriesWithANewTokenWhenTaken() {
        stubOwnerInfo();
        ArgumentCaptor<DiagramShare> attempts = ArgumentCaptor.forClass(DiagramShare.class);
        when(shareRepository.insert(attempts.capture()))
                .thenThrow(new ShareTokenTakenException())
                .thenAnswer(invocation -> invocation.getArgument(0));

        DiagramShare created = shareService.createShare(diagramId, ShareRole.EDIT, Caller.user(ownerId));

        assertThat(attempts.getAllValues()).hasSize(2);
        assertThat(attempts.getAllValues().get(0).getShareToken())
                .isNotEqualTo(attempts.getAllValues().get(1).getShareToken());
        assertThat(created).isSameAs(attempts.getAllValues().get(1));
    }

    @Test
    @DisplayName("Trùng token liên tiếp quá số lần cho phép thì báo 409")
    void createShare_GivesUpAfterThreeCollisions() {
        stubOwnerInfo();
        when(shareRepository.insert(any(DiagramShare.class))).thenThrow(new ShareTokenTakenException());

        assertThatThrownBy(() -> shareService.createShare(diagramId, ShareRole.VIEW, Caller.user(ownerId)))
                .isInstanceOf(ShareTokenTakenException.class);
        verify(shareRepository, times(3)).insert(any(DiagramShare.class));
    }


    @Test
    @DisplayName("Cập nhật quyền liên kết chia sẻ thành công (view -> edit)")
    void updateShareRole_Success() {
        UUID shareId = UUID.randomUUID();
        DiagramShare storedAfterUpdate = new DiagramShare(shareId, diagramId, "token123", ShareRole.EDIT, Instant.now());

        stubOwnerInfo();
        when(shareRepository.updateRole(shareId, diagramId, ShareRole.EDIT)).thenReturn(Optional.of(storedAfterUpdate));

        DiagramShare updated = shareService.updateShareRole(diagramId, shareId, ShareRole.EDIT, Caller.user(ownerId));

        assertThat(updated.getRole()).isEqualTo(ShareRole.EDIT);
        assertThat(updated.getShareToken()).isEqualTo("token123");
    }

    @Test
    @DisplayName("Đổi quyền link vừa bị thu hồi trả 404, không tạo lại link")
    void updateShareRole_RevokedLink_NotFoundAndNotRecreated() {
        UUID shareId = UUID.randomUUID();
        stubOwnerInfo();
        when(shareRepository.updateRole(shareId, diagramId, ShareRole.EDIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.updateShareRole(diagramId, shareId, ShareRole.EDIT, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class);
        verify(shareRepository, never()).insert(any(DiagramShare.class));
    }

    @Test
    @DisplayName("Thu hồi liên kết chia sẻ thành công")
    void revokeShare_Success() {
        UUID shareId = UUID.randomUUID();
        stubOwnerInfo();
        when(shareRepository.deleteByIdAndDiagramId(shareId, diagramId)).thenReturn(true);

        shareService.revokeShare(diagramId, shareId, Caller.user(ownerId));

        verify(shareRepository).deleteByIdAndDiagramId(shareId, diagramId);
    }

    @Test
    @DisplayName("Thu hồi link không có hoặc thuộc sơ đồ khác trả 404")
    void revokeShare_UnknownOrOtherDiagramIs404() {
        UUID shareId = UUID.randomUUID();
        stubOwnerInfo();
        when(shareRepository.deleteByIdAndDiagramId(shareId, diagramId)).thenReturn(false);

        assertThatThrownBy(() -> shareService.revokeShare(diagramId, shareId, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Share link not found");
    }

    @Test
    @DisplayName("Link edit không được thu hồi link")
    void revokeShare_ForbiddenForEditShareToken() {
        stubOwnerInfo();

        assertThatThrownBy(() -> shareService.revokeShare(diagramId, UUID.randomUUID(),
                Caller.shareToken(diagramId, ShareRole.EDIT)))
                .isInstanceOf(ForbiddenException.class);
        verify(shareRepository, never()).deleteByIdAndDiagramId(any(), any());
    }

    private void stubOwnerInfo() {
        when(diagramRepository.findAccessInfo(diagramId))
                .thenReturn(Optional.of(new DiagramAccessInfo(diagramId, ownerId, false, 1)));
    }

    @Test
    @DisplayName("Truy xuất cấu trúc sơ đồ qua token hợp lệ (Public)")
    void getDiagramByShareToken_Success() {
        String token = "valid_token_xyz";
        DiagramShare share = new DiagramShare(UUID.randomUUID(), diagramId, token, ShareRole.EDIT, Instant.now());

        when(shareRepository.findByShareToken(token)).thenReturn(Optional.of(share));
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        SharedDiagramView view = shareService.getDiagramByShareToken(token);

        assertThat(view).isNotNull();
        assertThat(view.getDiagramId()).isEqualTo(diagramId);
        assertThat(view.getTitle()).isEqualTo("Sơ đồ lớp BTL");
        assertThat(view.getRole()).isEqualTo(ShareRole.EDIT);
    }

    @Test
    @DisplayName("Truy xuất sơ đồ bằng token không tồn tại sẽ bị 404 NotFound")
    void getDiagramByShareToken_NotFound() {
        when(shareRepository.findByShareToken("invalid_token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.getDiagramByShareToken("invalid_token"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("Token X-Share-Token hợp lệ được đổi thành Caller có quyền trên đúng sơ đồ")
    void resolveShareToken_ValidToken() {
        DiagramShare share = new DiagramShare(UUID.randomUUID(), diagramId, "edit_token", ShareRole.EDIT, Instant.now());
        when(shareRepository.findByShareToken("edit_token")).thenReturn(Optional.of(share));

        assertThat(shareService.resolveShareToken(" edit_token "))
                .contains(Caller.shareToken(diagramId, ShareRole.EDIT));
    }

    @Test
    @DisplayName("Token không tồn tại hoặc đã thu hồi thì không có Caller")
    void resolveShareToken_UnknownOrBlank() {
        when(shareRepository.findByShareToken("revoked")).thenReturn(Optional.empty());

        assertThat(shareService.resolveShareToken("revoked")).isEmpty();
        assertThat(shareService.resolveShareToken("  ")).isEmpty();
        assertThat(shareService.resolveShareToken(null)).isEmpty();
    }
}
