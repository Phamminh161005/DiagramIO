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
    private final DiagramCache cache = local(64 * 1024 * 1024, 2 * 1024 * 1024);
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
    void conflictDropsThePossiblyStaleEntry() {
        cache.find(id, () -> Optional.of(diagram(1, "v1")));

        cache.conflict(id);

        AtomicInteger loads = new AtomicInteger();
        cache.find(id, () -> {
            loads.incrementAndGet();
            return Optional.of(diagram(2, "v2"));
        });
        assertThat(loads).hasValue(1);
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
