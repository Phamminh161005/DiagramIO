package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Quy tắc nhất quán của cache ở chế độ local (Caffeine); phần Redis ở {@code RedisDiagramCacheTest}. */
class DiagramCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LocalDiagramTier tier = new LocalDiagramTier(64 * 1024 * 1024, Duration.ofMinutes(10),
            Duration.ofSeconds(60));
    private final DiagramCache cache = new DiagramCache(DiagramCache.Mode.LOCAL, tier, null, 2 * 1024 * 1024,
            registry);
    private final UUID id = UUID.randomUUID();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private DiagramCache local(long maxBytes, int maxDataChars) {
        return new DiagramCache(DiagramCache.Mode.LOCAL,
                new LocalDiagramTier(maxBytes, Duration.ofMinutes(10), Duration.ofSeconds(60)), null, maxDataChars,
                registry);
    }

    @Test
    void secondReadIsServedFromTheCacheAsAFreshCopy() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<Optional<Diagram>> db = () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(1, "v1"));
        };

        Diagram first = cache.find(id, db).orElseThrow();
        first.setTitle("changed by the caller");   // service sửa đối tượng của nó, cache không bị ảnh hưởng
        Diagram second = cache.find(id, db).orElseThrow();

        assertThat(loads).hasValue(1);
        assertThat(second).isNotSameAs(first);
        assertThat(second.getTitle()).isEqualTo("v1");
        assertThat(registry.get("cache.gets").tag("cache", "diagrams").tag("result", "hit").functionCounter().count())
                .isEqualTo(1);
    }

    @Test
    void writeThroughReplacesTheEntrySoTheWriterReadsItsOwnWrite() {
        cache.find(id, () -> Optional.of(diagram(1, "v1")));

        cache.written(diagram(2, "v2"));

        assertThat(cache.find(id, this::failIfLoaded).orElseThrow().getTitle()).isEqualTo("v2");
        assertThat(cache.findAccessInfo(id, this::failIfLoadedInfo)).contains(
                new DiagramAccessInfo(id, OWNER, false, 2));
    }

    @Test
    void slowReaderThatLoadedBeforeAWriteCannotOverwriteTheNewerVersion() throws Exception {
        CountDownLatch readerHasLoaded = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        // Đọc CSDL thấy version 1, rồi bị chậm; trong lúc đó người khác ghi version 2 vào cache
        Future<Optional<Diagram>> reader = pool.submit(() -> cache.find(id, () -> {
            readerHasLoaded.countDown();
            await(writerDone);
            return Optional.of(diagram(1, "stale"));
        }));
        await(readerHasLoaded);
        cache.written(diagram(2, "fresh"));
        writerDone.countDown();

        assertThat(reader.get(5, TimeUnit.SECONDS).orElseThrow().getTitle()).isEqualTo("stale"); // nó đọc gì trả nấy
        assertThat(cache.find(id, this::failIfLoaded).orElseThrow().getVersion()).isEqualTo(2);  // nhưng không đè được
    }

    /**
     * Lỗi tìm thấy khi đo b12: lần nạp chậm đọc CSDL trước một lần ghi, rồi mục mới rời L1 (bị đẩy ra vì đầy, hay bị
     * bỏ cùng cả L1 khi Redis trục trặc) trước khi lần nạp kịp ghi. So version với mục đang có không còn gì để so, nên
     * version 1 quay lại cache và ở đó tới khi hết hạn: đọc thấy bản cũ, PUT với version đúng nhận 409.
     */
    @Test
    void slowReaderCannotRefillAnOlderVersionAfterTheNewerEntryLeftTheCache() throws Exception {
        CountDownLatch readerHasLoaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Optional<Diagram>> reader = pool.submit(() -> cache.find(id, () -> {
            readerHasLoaded.countDown();
            await(release);
            return Optional.of(diagram(1, "stale"));
        }));
        await(readerHasLoaded);
        cache.written(diagram(2, "fresh"));
        tier.caffeine().invalidate(id);   // mục version 2 rời L1
        release.countDown();
        reader.get(5, TimeUnit.SECONDS);

        AtomicInteger loads = new AtomicInteger();
        Diagram next = cache.find(id, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(2, "fresh"));
        }).orElseThrow();
        assertThat(next.getVersion()).isEqualTo(2);
        assertThat(loads).hasValue(1);
    }

    @Test
    void readerThatJoinsALoadStartedBeforeAWriteReadsAgain() throws Exception {
        CountDownLatch leaderHasLoaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Optional<Diagram>> leader = pool.submit(() -> cache.find(id, () -> {
            leaderHasLoaded.countDown();
            await(release);
            return Optional.of(diagram(1, "stale"));
        }));
        await(leaderHasLoaded);
        cache.written(diagram(2, "fresh"));   // ví dụ người đọc sau chính là người vừa ghi
        tier.caffeine().invalidate(id);
        AtomicInteger loads = new AtomicInteger();
        Future<Optional<Diagram>> joiner = pool.submit(() -> cache.find(id, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(2, "fresh"));
        }));
        Thread.sleep(200);   // cho người đọc sau tới chỗ chờ lần nạp đang chạy
        release.countDown();

        assertThat(leader.get(5, TimeUnit.SECONDS).orElseThrow().getVersion()).isEqualTo(1);  // bắt đầu trước lần ghi
        assertThat(joiner.get(5, TimeUnit.SECONDS).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(loads).hasValue(1);
    }

    @Test
    void lateWriteThroughOfAnOlderVersionCannotReplaceANewerOneThatLeftTheCache() {
        cache.written(diagram(3, "v3"));
        tier.caffeine().invalidate(id);
        cache.written(diagram(2, "v2, written through late"));

        AtomicInteger loads = new AtomicInteger();
        Diagram next = cache.find(id, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(3, "v3"));
        }).orElseThrow();
        assertThat(next.getVersion()).isEqualTo(3);
        assertThat(loads).hasValue(1);
    }

    @Test
    void changeAnnouncedByAnotherInstanceBlocksALateWriteOfAnOlderVersion() {
        cache.onRemoteChange(new RedisDiagramTier.Change(id, 3, "other-instance"));
        cache.written(diagram(2, "v2, written through after v3 was announced"));

        AtomicInteger loads = new AtomicInteger();
        Diagram next = cache.find(id, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(3, "v3"));
        }).orElseThrow();
        assertThat(next.getVersion()).isEqualTo(3);
        assertThat(loads).hasValue(1);
    }

    @Test
    void deleteLeavesATombstoneThatAnswers404AndBlocksASlowReader() throws Exception {
        cache.find(id, () -> Optional.of(diagram(3, "v3")));
        CountDownLatch readerHasLoaded = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        UUID other = UUID.randomUUID();
        Future<Optional<Diagram>> slow = pool.submit(() -> cache.find(other, () -> {
            readerHasLoaded.countDown();
            await(deleted);
            return Optional.of(diagram(other, 3, "loaded before delete"));
        }));
        await(readerHasLoaded);

        cache.deleted(id);
        cache.deleted(other);
        deleted.countDown();
        slow.get(5, TimeUnit.SECONDS);

        assertThat(cache.find(id, this::failIfLoaded)).isEmpty();
        assertThat(cache.findAccessInfo(id, this::failIfLoadedInfo)).isEmpty();
        assertThat(cache.find(other, this::failIfLoaded)).isEmpty();
    }

    @Test
    void concurrentMissesOfOneDiagramLoadItOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        List<Future<Optional<Diagram>>> readers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            readers.add(pool.submit(() -> cache.find(id, () -> {
                loads.incrementAndGet();
                await(release);
                return Optional.of(diagram(1, "v1"));
            })));
        }
        Thread.sleep(200);   // cho cả 8 luồng tới chỗ chờ lần nạp đang chạy
        release.countDown();

        List<Diagram> results = new ArrayList<>();
        for (Future<Optional<Diagram>> reader : readers) {
            results.add(reader.get(5, TimeUnit.SECONDS).orElseThrow());
        }
        assertThat(loads).hasValue(1);
        assertThat(results).extracting(Diagram::getTitle).containsOnly("v1");
        assertThat(results).doesNotHaveDuplicates();   // mỗi luồng một bản riêng, không dùng chung đối tượng
    }

    @Test
    void aFailedLoadIsRethrownToEveryWaiterAndNotCached() {
        assertThatThrownBy(() -> cache.find(id, () -> {
            throw new IllegalStateException("database down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(cache.find(id, () -> Optional.of(diagram(1, "v1")))).isPresent();
    }

    @Test
    void missingDiagramIsNotCached() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<Optional<Diagram>> db = () -> {
            loads.incrementAndGet();
            return Optional.empty();
        };

        assertThat(cache.find(id, db)).isEmpty();
        assertThat(cache.find(id, db)).isEmpty();
        assertThat(loads).hasValue(2);
    }

    @Test
    void diagramsAboveTheSizeLimitAreNotCached() {
        DiagramCache small = local(64 * 1024 * 1024, 100);
        AtomicInteger loads = new AtomicInteger();
        Supplier<Optional<Diagram>> db = () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(1, "big", "{\"nodes\": [\"" + "x".repeat(200) + "\"], \"edges\": []}"));
        };

        small.find(id, db);
        small.find(id, db);
        small.written(diagram(2, "big", "{\"nodes\": [\"" + "x".repeat(200) + "\"], \"edges\": []}"));
        small.find(id, db);

        assertThat(loads).hasValue(3);
    }

    @Test
    void conflictDropsOlderEntriesAndBlocksASlowReaderOfAnOlderVersion() throws Exception {
        cache.find(id, () -> Optional.of(diagram(1, "v1")));
        CountDownLatch readerHasLoaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UUID other = UUID.randomUUID();
        Future<Optional<Diagram>> slow = pool.submit(() -> cache.find(other, () -> {
            readerHasLoaded.countDown();
            await(release);
            return Optional.of(diagram(other, 4, "read before the conflict"));
        }));
        await(readerHasLoaded);

        cache.conflict(id, 2);      // CSDL đang ở version 2
        cache.conflict(other, 5);
        release.countDown();
        slow.get(5, TimeUnit.SECONDS);

        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.find(id, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(2, "v2"));
        }).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(cache.find(other, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(other, 5, "v5"));
        }).orElseThrow().getVersion()).isEqualTo(5);
        assertThat(loads).hasValue(2);
        assertThat(cache.find(id, this::failIfLoaded).orElseThrow().getVersion()).isEqualTo(2);  // bản đúng vào lại
    }

    @Test
    void conflictKeepsAnEntryThatIsAlreadyCurrent() {
        cache.written(diagram(3, "v3"));

        cache.conflict(id, 3);   // người gửi version cũ thua; cache đã đúng

        assertThat(cache.find(id, this::failIfLoaded).orElseThrow().getVersion()).isEqualTo(3);
    }

    @Test
    void changeFromAnotherInstanceEvictsOnlyOlderEntriesAndOwnChangesAreIgnored() {
        cache.find(id, () -> Optional.of(diagram(5, "v5")));

        cache.onRemoteChange(new RedisDiagramTier.Change(id, 5, "other-instance"));
        assertThat(cache.find(id, this::failIfLoaded)).isPresent();          // cùng version: giữ
        cache.onRemoteChange(new RedisDiagramTier.Change(id, 6, cache.instanceId()));
        assertThat(cache.find(id, this::failIfLoaded)).isPresent();          // của chính mình: bỏ qua
        cache.onRemoteChange(new RedisDiagramTier.Change(id, 6, "other-instance"));
        AtomicInteger loads = new AtomicInteger();
        cache.find(id, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(6, "v6"));
        });
        assertThat(loads).hasValue(1);                                       // cũ hơn: bỏ, đọc lại
    }

    @Test
    void noneModeAlwaysGoesToTheDatabase() {
        DiagramCache none = new DiagramCache(DiagramCache.Mode.NONE, null, null, 0, registry);
        AtomicInteger loads = new AtomicInteger();
        Supplier<Optional<Diagram>> db = () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(1, "v1"));
        };

        none.find(id, db);
        none.written(diagram(2, "v2"));
        none.find(id, db);
        none.deleted(id);
        none.find(id, db);

        assertThat(loads).hasValue(3);
        assertThat(none.enabled()).isFalse();
    }

    @Test
    void tiersMustMatchTheMode() {
        assertThatThrownBy(() -> new DiagramCache(DiagramCache.Mode.LOCAL, null, null, 0, registry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private Diagram diagram(int version, String title) {
        return diagram(id, version, title);
    }

    private Diagram diagram(UUID diagramId, int version, String title) {
        return new Diagram(diagramId, title, DiagramType.ERD, DiagramData.EMPTY, version, false, OWNER, OWNER, T0,
                T0.plusSeconds(version));
    }

    private Diagram diagram(int version, String title, String json) {
        return new Diagram(id, title, DiagramType.ERD, DiagramData.fromStorage(json), version, false, OWNER, OWNER,
                T0, T0.plusSeconds(version));
    }

    private Optional<Diagram> failIfLoaded() {
        throw new AssertionError("should have been served from the cache");
    }

    private Optional<DiagramAccessInfo> failIfLoadedInfo() {
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
