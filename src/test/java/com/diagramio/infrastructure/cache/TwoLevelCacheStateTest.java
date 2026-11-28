package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * Trạng thái của cache hai tầng khi Redis trục trặc, với tầng Redis giả (mock): những gì phiên đo b12 cho thấy.
 * Khi CPU bão hòa lúc khởi động, lệnh Redis hết thời gian chờ; trước đây mỗi lỗi bỏ cả L1, và lỗi của lệnh gửi trước
 * lần hồi phục làm cache ngừng dùng Redis lần nữa ngay sau khi vừa dùng lại (5 lần trong 25 giây).
 */
class TwoLevelCacheStateTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    private final RedisDiagramTier remote = mock(RedisDiagramTier.class);
    private final DiagramCache cache = new DiagramCache(DiagramCache.Mode.TWO_LEVEL,
            new LocalDiagramTier(64 * 1024 * 1024, Duration.ofMinutes(10), Duration.ofSeconds(60)), remote,
            2 * 1024 * 1024, new SimpleMeterRegistry());
    private final UUID id = UUID.randomUUID();
    private final AtomicInteger loads = new AtomicInteger();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @BeforeEach
    void redisAndPubSubAreHealthy() {
        cache.remoteStatus(true, true);
        assertThat(cache.remoteUp()).isTrue();
        assertThat(cache.localTrusted()).isTrue();
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void startsWithoutRedisAndWithoutL1UntilTheMonitorConfirmsBoth() {
        DiagramCache fresh = new DiagramCache(DiagramCache.Mode.TWO_LEVEL,
                new LocalDiagramTier(1024 * 1024, Duration.ofMinutes(10), Duration.ofSeconds(60)), remote,
                1024 * 1024, new SimpleMeterRegistry());
        assertThat(fresh.remoteUp()).isFalse();
        assertThat(fresh.localTrusted()).isFalse();

        fresh.remoteStatus(true, false);   // lệnh chạy được, heartbeat chưa về
        assertThat(fresh.remoteUp()).isTrue();
        assertThat(fresh.localTrusted()).isFalse();

        fresh.remoteStatus(true, true);
        assertThat(fresh.localTrusted()).isTrue();
    }

    @Test
    void timedOutRedisCommandStopsOnlyL2AndL1KeepsServing() {
        read(id, 1);
        UUID other = UUID.randomUUID();
        when(remote.get(other)).thenThrow(new QueryTimeoutException("Redis command timed out"));

        read(other, 1);

        assertThat(cache.remoteUp()).isFalse();
        assertThat(cache.localTrusted()).isTrue();
        assertThat(cache.find(id, this::failIfLoaded).orElseThrow().getVersion()).isEqualTo(1);   // L1 còn nguyên
    }

    @Test
    void failureOfACommandSentBeforeTheLastRecoveryIsIgnored() throws Exception {
        UUID slowId = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        CountDownLatch inGet = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(remote.get(slowId)).thenAnswer(invocation -> {
            inGet.countDown();
            await(release);
            throw new QueryTimeoutException("Redis command timed out");
        });
        doThrow(new QueryTimeoutException("Redis command timed out")).when(remote).getAccessInfo(other);

        Future<?> slow = pool.submit(() -> read(slowId, 1));   // lệnh này gửi khi Redis còn dùng được
        await(inGet);
        cache.findAccessInfo(other, Optional::empty);           // một lệnh khác lỗi: ngừng dùng Redis
        assertThat(cache.remoteUp()).isFalse();
        cache.remoteStatus(true, true);                         // bộ theo dõi: Redis trả lời lại
        assertThat(cache.remoteUp()).isTrue();

        release.countDown();                                    // lệnh cũ lúc này mới báo lỗi
        slow.get(5, TimeUnit.SECONDS);

        assertThat(cache.remoteUp()).isTrue();
    }

    @Test
    void lostPubSubStopsL1AndL1RestartsEmptyWhenTheHeartbeatIsBack() {
        read(id, 1);
        assertThat(loads).hasValue(1);

        cache.remoteStatus(true, false);
        assertThat(cache.localTrusted()).isFalse();
        assertThat(cache.remoteUp()).isTrue();
        read(id, 1);                       // L1 có thể đã lỡ thông báo hủy: không dùng
        assertThat(loads).hasValue(2);

        cache.remoteStatus(true, true);
        assertThat(cache.localTrusted()).isTrue();
        read(id, 1);                       // L1 bắt đầu lại từ rỗng
        assertThat(loads).hasValue(3);
        assertThat(cache.find(id, this::failIfLoaded)).isPresent();
    }

    @Test
    void readerThatStartedBeforeAnL1ResetCannotFillTheNewL1() throws Exception {
        CountDownLatch readerHasLoaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Optional<Diagram>> reader = pool.submit(() -> cache.find(id, () -> {
            readerHasLoaded.countDown();
            await(release);
            return Optional.of(diagram(id, 1));
        }));
        await(readerHasLoaded);
        cache.remoteStatus(true, false);   // mất pub/sub: có thể đã lỡ thông báo hủy
        cache.remoteStatus(true, true);
        release.countDown();
        reader.get(5, TimeUnit.SECONDS);

        read(id, 1);
        assertThat(loads).hasValue(1);     // phải đọc lại CSDL: bản nạp từ trước lúc mất pub/sub không vào L1 mới
    }

    @Test
    void redisCopyOlderThanAKnownChangeIsTreatedAsAMiss() {
        cache.onRemoteChange(new RedisDiagramTier.Change(id, 3, "other-instance"));
        when(remote.get(id)).thenReturn(Optional.of(CachedDiagram.of(diagram(id, 2))));

        assertThat(read(id, 3).getVersion()).isEqualTo(3);
        assertThat(loads).hasValue(1);
    }

    @Test
    void changesMadeWhileRedisIsDownAreFencedOnRedisWhenItIsBack() {
        cache.remoteStatus(false, true);
        assertThat(cache.remoteUp()).isFalse();
        UUID deleted = UUID.randomUUID();
        UUID conflicted = UUID.randomUUID();

        cache.written(diagram(id, 2));
        cache.deleted(deleted);
        cache.conflict(conflicted, 7);
        verify(remote, never()).putIfNewerAndPublish(any(), anyString());
        verify(remote, never()).fenceAndPublish(any(), anyInt(), anyInt(), anyString());
        assertThat(cache.find(id, this::failIfLoaded).orElseThrow().getVersion()).isEqualTo(2);   // L1 vẫn đúng

        cache.remoteStatus(true, true);

        assertThat(cache.remoteUp()).isTrue();
        verify(remote).fenceAndPublish(id, 1, 2, cache.instanceId());
        verify(remote).putIfNewerAndPublish(CachedDiagram.tombstone(deleted), cache.instanceId());
        verify(remote).fenceAndPublish(conflicted, 6, 7, cache.instanceId());
    }

    @Test
    void failedWriteThroughIsRememberedAndFencedOnRecovery() {
        when(remote.putIfNewerAndPublish(any(), anyString()))
                .thenThrow(new QueryTimeoutException("Redis command timed out"));

        cache.written(diagram(id, 2));

        assertThat(cache.remoteUp()).isFalse();
        assertThat(cache.find(id, this::failIfLoaded).orElseThrow().getVersion()).isEqualTo(2);
        cache.remoteStatus(true, true);
        verify(remote).fenceAndPublish(id, 1, 2, cache.instanceId());
        assertThat(cache.remoteUp()).isTrue();
    }

    @Test
    void recoveryWaitsUntilEveryMissedChangeIsFenced() {
        cache.remoteStatus(false, true);
        cache.written(diagram(id, 2));
        when(remote.fenceAndPublish(eq(id), anyInt(), anyInt(), anyString()))
                .thenThrow(new QueryTimeoutException("Redis command timed out"))
                .thenReturn(true);

        cache.remoteStatus(true, true);
        assertThat(cache.remoteUp()).isFalse();   // chưa dựng được dấu sàn: Redis còn bản cũ, chưa dùng lại
        cache.remoteStatus(true, true);
        assertThat(cache.remoteUp()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private Diagram read(UUID diagramId, int version) {
        return cache.find(diagramId, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(diagramId, version));
        }).orElseThrow();
    }

    private static Diagram diagram(UUID diagramId, int version) {
        return new Diagram(diagramId, "v" + version, DiagramType.ERD, DiagramData.EMPTY, version, false, OWNER, OWNER,
                T0, T0.plusSeconds(version));
    }

    private Optional<Diagram> failIfLoaded() {
        throw new AssertionError("should have been served from the cache");
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
