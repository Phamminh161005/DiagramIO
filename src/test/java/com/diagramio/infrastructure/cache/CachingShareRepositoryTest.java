package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.ServiceUnavailableException;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.share.DiagramShareRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cache token chia sẻ ở chế độ local; phần Redis ở {@code RedisShareTokenStoreTest}. */
class CachingShareRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-26T08:00:00Z");
    private static final String TOKEN = "a".repeat(48);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DiagramShareRepository db = mock(DiagramShareRepository.class);
    private final CachingShareRepository shares = new CachingShareRepository(db,
            new LocalShareTokenStore(1000, Duration.ofMinutes(5), Duration.ofSeconds(30)), () -> true, registry);
    private final UUID shareId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void repeatedLookupsAreServedFromTheCache() {
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.EDIT)));

        shares.findByShareToken(TOKEN);
        DiagramShare cached = shares.findByShareToken(TOKEN).orElseThrow();

        verify(db, times(1)).findByShareToken(TOKEN);
        assertThat(cached.getShareToken()).isEqualTo(TOKEN);
        assertThat(cached.getRole()).isEqualTo(ShareRole.EDIT);
        assertThat(cached.getDiagramId()).isEqualTo(diagramId);
        assertThat(cached.getCreatedAt()).isEqualTo(T0);
        assertThat(counter("hit")).isEqualTo(1);
        assertThat(counter("miss")).isEqualTo(1);
    }

    @Test
    void unknownTokensAreCachedAsAbsent() {
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.empty());

        assertThat(shares.findByShareToken(TOKEN)).isEmpty();
        assertThat(shares.findByShareToken(TOKEN)).isEmpty();

        verify(db, times(1)).findByShareToken(TOKEN);
        assertThat(counter("absent")).isEqualTo(1);
    }

    @Test
    void revokedTokenIsRejectedOnTheVeryNextLookup() {
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        shares.findByShareToken(TOKEN);
        when(db.deleteByIdAndDiagramId(shareId, diagramId)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.empty());

        shares.deleteByIdAndDiagramId(shareId, diagramId);

        assertThat(shares.findByShareToken(TOKEN)).isEmpty();
    }

    @Test
    void roleChangeIsSeenOnTheVeryNextLookupAndNotCachedWhileTheChangeSettles() {
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.EDIT)));
        shares.findByShareToken(TOKEN);
        when(db.updateRole(shareId, diagramId, ShareRole.VIEW)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.VIEW)));

        shares.updateRole(shareId, diagramId, ShareRole.VIEW);

        assertThat(shares.findByShareToken(TOKEN).orElseThrow().getRole()).isEqualTo(ShareRole.VIEW);
        assertThat(shares.findByShareToken(TOKEN).orElseThrow().getRole()).isEqualTo(ShareRole.VIEW);
        verify(db, times(3)).findByShareToken(TOKEN);   // lần đầu, rồi hai lần đi CSDL trong lúc có dấu
    }

    @Test
    void slowLookupThatReadTheLinkBeforeItWasRevokedCannotBringItBack() throws Exception {
        CountDownLatch readTheOldRow = new CountDownLatch(1);
        CountDownLatch revoked = new CountDownLatch(1);
        // Lần tra cứu chậm: đọc CSDL thấy link còn, rồi bị chậm; trong lúc đó chủ thu hồi link
        when(db.findByShareToken(TOKEN)).thenAnswer(invocation -> {
            readTheOldRow.countDown();
            await(revoked);
            return Optional.of(share(ShareRole.EDIT));
        });
        Future<Optional<DiagramShare>> slow = pool.submit(() -> shares.findByShareToken(TOKEN));
        await(readTheOldRow);
        when(db.deleteByIdAndDiagramId(shareId, diagramId)).thenReturn(Optional.of(share(ShareRole.EDIT)));
        shares.deleteByIdAndDiagramId(shareId, diagramId);
        revoked.countDown();
        slow.get(5, TimeUnit.SECONDS);   // nó trả gì cho request của nó thì tùy, nhưng không được nạp vào cache

        when(db.findByShareToken(TOKEN)).thenReturn(Optional.empty());
        assertThat(shares.findByShareToken(TOKEN)).isEmpty();
        assertThat(shares.findByShareToken(TOKEN)).isEmpty();
    }

    @Test
    void slowLookupThatReadTheOldRoleCannotRestoreItAfterADowngrade() throws Exception {
        CountDownLatch readTheOldRow = new CountDownLatch(1);
        CountDownLatch downgraded = new CountDownLatch(1);
        when(db.findByShareToken(TOKEN)).thenAnswer(invocation -> {
            readTheOldRow.countDown();
            await(downgraded);
            return Optional.of(share(ShareRole.EDIT));
        });
        Future<Optional<DiagramShare>> slow = pool.submit(() -> shares.findByShareToken(TOKEN));
        await(readTheOldRow);
        when(db.updateRole(shareId, diagramId, ShareRole.VIEW)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        shares.updateRole(shareId, diagramId, ShareRole.VIEW);
        downgraded.countDown();
        slow.get(5, TimeUnit.SECONDS);

        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        assertThat(shares.findByShareToken(TOKEN).orElseThrow().getRole()).isEqualTo(ShareRole.VIEW);
    }

    @Test
    void newLinkReplacesAnEarlierAbsentEntry() {
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.empty());
        shares.findByShareToken(TOKEN);   // ai đó thử token trước khi link được tạo
        when(db.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        shares.insert(share(ShareRole.VIEW));

        assertThat(shares.findByShareToken(TOKEN)).isPresent();
        verify(db, times(1)).findByShareToken(TOKEN);
    }

    @Test
    void deletingTheDiagramDropsTheTokensOfItsLinks() {
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        shares.findByShareToken(TOKEN);
        when(db.findByDiagramId(diagramId)).thenReturn(List.of(share(ShareRole.VIEW)));

        Runnable afterDelete = shares.beforeDiagramDelete(diagramId);
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.empty());   // khóa ngoại đã xóa link
        afterDelete.run();

        assertThat(shares.findByShareToken(TOKEN)).isEmpty();
        verify(db, times(2)).findByShareToken(TOKEN);
    }

    @Test
    void whenTheChangeMarkerCannotBeWrittenTheLinkIsNotChanged() {
        ShareTokenStore broken = mock(ShareTokenStore.class);
        doThrow(new IllegalStateException("redis down")).when(broken).markChanging(any());
        CachingShareRepository failClosed = new CachingShareRepository(db, broken, () -> true, registry);

        assertThatThrownBy(() -> failClosed.deleteByIdAndDiagramId(shareId, diagramId))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> failClosed.updateRole(shareId, diagramId, ShareRole.VIEW))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(db, never()).deleteByIdAndDiagramId(any(), any());
        verify(db, never()).updateRole(any(), any(), any());
    }

    @Test
    void lookupFallsBackToTheDatabaseWhenTheStoreFails() {
        ShareTokenStore broken = mock(ShareTokenStore.class);
        when(broken.lookup(any())).thenThrow(new IllegalStateException("redis timeout"));
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        CachingShareRepository fallback = new CachingShareRepository(db, broken, () -> true, registry);

        assertThat(fallback.findByShareToken(TOKEN)).isPresent();
        verify(broken, never()).fill(any(), any());
    }

    @Test
    void unusableStoreIsNotEvenAsked() {
        ShareTokenStore store = mock(ShareTokenStore.class);
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.empty());
        CachingShareRepository down = new CachingShareRepository(db, store, () -> false, registry);

        down.findByShareToken(TOKEN);

        verifyNoInteractions(store);
    }

    @Test
    void lookupsInsideATransactionGoToTheDatabase() {
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.of(share(ShareRole.VIEW)));
        shares.findByShareToken(TOKEN);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        shares.findByShareToken(TOKEN);

        verify(db, times(2)).findByShareToken(TOKEN);
    }

    @Test
    void disabledCacheAlwaysAsksTheDatabase() {
        CachingShareRepository disabled = new CachingShareRepository(db, null, () -> true, registry);
        when(db.findByShareToken(TOKEN)).thenReturn(Optional.empty());
        when(db.deleteByIdAndDiagramId(shareId, diagramId)).thenReturn(Optional.empty());

        disabled.findByShareToken(TOKEN);
        disabled.findByShareToken(TOKEN);
        disabled.deleteByIdAndDiagramId(shareId, diagramId);
        disabled.beforeDiagramDelete(diagramId).run();

        verify(db, times(2)).findByShareToken(TOKEN);
        verify(db, never()).findByDiagramId(any());   // tắt cache thì xóa sơ đồ không tốn thêm câu SQL nào
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void cacheKeysAreHashesNeverTheTokenItself() {
        assertThat(ShareTokenStore.hash(TOKEN)).hasSize(64).doesNotContain(TOKEN).isEqualTo(ShareTokenStore.hash(TOKEN))
                .isNotEqualTo(ShareTokenStore.hash("b".repeat(48)));
    }

    // ------------------------------------------------------------------ helpers

    private DiagramShare share(ShareRole role) {
        return new DiagramShare(shareId, diagramId, TOKEN, role, T0);
    }

    private double counter(String result) {
        return registry.get("diagramio.cache.share_tokens").tag("result", result).counter().count();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
