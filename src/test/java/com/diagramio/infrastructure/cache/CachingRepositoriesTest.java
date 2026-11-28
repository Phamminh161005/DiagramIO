package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.domain.version.DiagramVersionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Decorator cache của hai repository: đọc qua cache, ghi CSDL rồi cập nhật cache sau commit. */
class CachingRepositoriesTest {

    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DiagramRepository db = mock(DiagramRepository.class);
    private final DiagramCache cache = new DiagramCache(DiagramCache.Mode.LOCAL,
            new LocalDiagramTier(64 * 1024 * 1024, Duration.ofMinutes(10), Duration.ofSeconds(60)), null,
            2 * 1024 * 1024, registry);
    private final CachingDiagramRepository repository = new CachingDiagramRepository(db, cache, Duration.ZERO,
            registry);
    private final UUID id = UUID.randomUUID();

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void readsAreServedFromTheCacheAfterTheFirstLoad() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")));

        repository.findById(id);
        repository.findById(id);
        assertThat(repository.findAccessInfo(id)).contains(new DiagramAccessInfo(id, OWNER, false, 1));

        verify(db, times(1)).findById(id);
        verify(db, never()).findAccessInfo(any());
    }

    @Test
    void accessInfoOfAnUncachedDiagramComesFromTheDatabaseWithoutLoadingData() {
        when(db.findAccessInfo(id)).thenReturn(Optional.of(new DiagramAccessInfo(id, OWNER, true, 4)));

        assertThat(repository.findAccessInfo(id)).contains(new DiagramAccessInfo(id, OWNER, true, 4));
        verify(db, never()).findById(any());
    }

    @Test
    void successfulUpdateIsWrittenThroughSoTheNextReadSeesIt() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")));
        repository.findById(id);
        when(db.updateIfVersion(any(), anyBoolean())).thenReturn(Optional.of(diagram(2, "v2")));

        Diagram returned = repository.updateIfVersion(diagram(1, "v2"), true).orElseThrow();
        returned.setTitle("mutated by the service afterwards");

        assertThat(repository.findById(id).orElseThrow().getTitle()).isEqualTo("v2");
        verify(db, times(1)).findById(id);
    }

    @Test
    void failedConditionalUpdateFencesTheCacheAtTheVersionTheDatabaseHas() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")), Optional.of(diagram(2, "v2")));
        repository.findById(id);
        when(db.updateIfVersion(any(), anyBoolean())).thenReturn(Optional.empty());
        when(db.findAccessInfo(id)).thenReturn(Optional.of(new DiagramAccessInfo(id, OWNER, false, 2)));

        assertThat(repository.updateIfVersion(diagram(1, "mine"), true)).isEmpty();

        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(2);   // bản 1 đã bị bỏ
        verify(db, times(2)).findById(id);
        verify(db).updateIfVersion(any(), eq(true));
    }

    @Test
    void failedConditionalUpdateOfADiagramDeletedMeanwhileLeavesATombstone() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")));
        repository.findById(id);
        when(db.updateIfVersion(any(), anyBoolean())).thenReturn(Optional.empty());
        when(db.findAccessInfo(id)).thenReturn(Optional.empty());

        repository.updateIfVersion(diagram(1, "mine"), false);

        assertThat(repository.findById(id)).isEmpty();
        verify(db, times(1)).findById(id);
    }

    @Test
    void cacheModeNoneAddsNoQueryToAFailedUpdate() {
        DiagramCache none = new DiagramCache(DiagramCache.Mode.NONE, null, null, 0, registry);
        CachingDiagramRepository passThrough = new CachingDiagramRepository(db, none, Duration.ZERO, registry);
        when(db.updateIfVersion(any(), anyBoolean())).thenReturn(Optional.empty());

        passThrough.updateIfVersion(diagram(1, "mine"), true);

        verify(db, never()).findAccessInfo(any());
    }

    @Test
    void writesInsideATransactionReachTheCacheOnlyAfterCommit() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")));
        repository.findById(id);
        when(db.restoreContent(any(), any(), any(), any())).thenReturn(Optional.of(diagram(2, "restored")));

        beginTransaction();
        repository.restoreContent(id, UUID.randomUUID(), OWNER, T0);
        endTransaction(false);
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(1);   // chưa commit

        beginTransaction();
        repository.restoreContent(id, UUID.randomUUID(), OWNER, T0);
        endTransaction(true);
        assertThat(repository.findById(id).orElseThrow().getTitle()).isEqualTo("restored");
        verify(db, times(1)).findById(id);
    }

    @Test
    void rolledBackDeleteLeavesTheEntryAndCommittedDeleteLeavesATombstone() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")));
        repository.findById(id);

        beginTransaction();
        repository.deleteById(id);
        endTransaction(false);
        assertThat(repository.findById(id)).isPresent();

        repository.deleteById(id);   // không có transaction: câu DELETE đã autocommit
        assertThat(repository.findById(id)).isEmpty();
        assertThat(repository.findAccessInfo(id)).isEmpty();
        verify(db, times(1)).findById(id);
    }

    @Test
    void readsInsideATransactionBypassTheCache() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")));
        when(db.findAccessInfo(id)).thenReturn(Optional.of(new DiagramAccessInfo(id, OWNER, false, 1)));
        repository.findById(id);

        beginTransaction();
        repository.findById(id);
        repository.findAccessInfo(id);
        endTransaction(true);

        verify(db, times(2)).findById(id);
        verify(db, times(1)).findAccessInfo(id);
    }

    @Test
    void lockingAlwaysGoesToTheDatabase() {
        when(db.findById(id)).thenReturn(Optional.of(diagram(1, "v1")));
        when(db.lockAccessInfo(id)).thenReturn(Optional.of(new DiagramAccessInfo(id, OWNER, false, 1)));
        repository.findById(id);

        repository.lockAccessInfo(id);
        repository.lockAccessInfo(id);

        verify(db, times(2)).lockAccessInfo(id);
    }

    @Test
    void ownerListsAreNeverCachedAndPublicListsOnlyWhenEnabled() {
        DiagramPage page = new DiagramPage(List.of(), 0);
        when(db.findByOwner(OWNER, null, "", 0, 20)).thenReturn(page);
        when(db.findPublic(0, 20)).thenReturn(page);

        repository.findByOwner(OWNER, null, "", 0, 20);
        repository.findByOwner(OWNER, null, "", 0, 20);
        repository.findPublic(0, 20);
        repository.findPublic(0, 20);
        verify(db, times(2)).findByOwner(OWNER, null, "", 0, 20);
        verify(db, times(2)).findPublic(0, 20);

        CachingDiagramRepository withMicroCache = new CachingDiagramRepository(db, cache, Duration.ofSeconds(5),
                registry);
        withMicroCache.findPublic(0, 20);
        withMicroCache.findPublic(0, 20);
        withMicroCache.findPublic(1, 20);
        verify(db, times(3)).findPublic(0, 20);
        verify(db, times(1)).findPublic(1, 20);
    }

    @Test
    void insertedDiagramIsCachedForItsCreator() {
        when(db.insert(any())).thenReturn(diagram(1, "new"));

        repository.insert(diagram(1, "new"));

        assertThat(repository.findById(id)).isPresent();
        verify(db, never()).findById(any());
    }

    // ---------- phiên bản

    @Test
    void versionsAreCachedForeverAndOnlyServedForTheirOwnDiagram() {
        DiagramVersionRepository versionDb = mock(DiagramVersionRepository.class);
        CachingDiagramVersionRepository versions = new CachingDiagramVersionRepository(versionDb, 1024 * 1024,
                registry);
        UUID versionId = UUID.randomUUID();
        DiagramVersion v = new DiagramVersion(versionId, id, 1, DiagramData.EMPTY, null, OWNER, T0);
        when(versionDb.findByIdAndDiagramId(versionId, id)).thenReturn(Optional.of(v));

        assertThat(versions.findByIdAndDiagramId(versionId, id)).contains(v);
        assertThat(versions.findByIdAndDiagramId(versionId, id)).contains(v);
        assertThat(versions.findByIdAndDiagramId(versionId, UUID.randomUUID())).isEmpty();

        verify(versionDb, times(1)).findByIdAndDiagramId(any(), any());
    }

    @Test
    void versionReadInsideATransactionIsNotCached() {
        DiagramVersionRepository versionDb = mock(DiagramVersionRepository.class);
        CachingDiagramVersionRepository versions = new CachingDiagramVersionRepository(versionDb, 1024 * 1024,
                registry);
        UUID versionId = UUID.randomUUID();
        when(versionDb.findByIdAndDiagramId(versionId, id)).thenReturn(
                Optional.of(new DiagramVersion(versionId, id, 1, DiagramData.EMPTY, null, OWNER, T0)));

        beginTransaction();
        versions.findByIdAndDiagramId(versionId, id);
        endTransaction(false);
        versions.findByIdAndDiagramId(versionId, id);

        verify(versionDb, times(2)).findByIdAndDiagramId(versionId, id);
    }

    @Test
    void disabledVersionCacheAlwaysGoesToTheDatabase() {
        DiagramVersionRepository versionDb = mock(DiagramVersionRepository.class);
        CachingDiagramVersionRepository versions = new CachingDiagramVersionRepository(versionDb, 0, registry);
        UUID versionId = UUID.randomUUID();
        when(versionDb.findByIdAndDiagramId(versionId, id)).thenReturn(Optional.empty());

        versions.findByIdAndDiagramId(versionId, id);
        versions.findByIdAndDiagramId(versionId, id);

        verify(versionDb, times(2)).findByIdAndDiagramId(versionId, id);
    }

    // ------------------------------------------------------------------ helpers

    private Diagram diagram(int version, String title) {
        return new Diagram(id, title, DiagramType.ERD, DiagramData.EMPTY, version, false, OWNER, OWNER, T0,
                T0.plusSeconds(version));
    }

    /** Giả lập một transaction Spring đang chạy (đủ cho TransactionSynchronizationManager). */
    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void endTransaction(boolean commit) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (commit) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
    }
}
