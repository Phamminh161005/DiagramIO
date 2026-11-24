package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.TestDatabase;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cache hai tầng trên Redis thật: hai {@link DiagramCache} dùng chung một Redis, như hai instance của app sau load
 * balancer. "CSDL" là một map trong bộ nhớ, đếm số lần bị đọc. Bỏ qua khi máy không có Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisDiagramCacheTest {

    private static final Duration MONITOR = Duration.ofMillis(100);
    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(TestDatabase.REDIS_IMAGE).withExposedPorts(6379);

    private final Map<UUID, Diagram> database = new ConcurrentHashMap<>();
    private final AtomicInteger databaseReads = new AtomicInteger();
    private LettuceConnectionFactory connections;
    private Instance a;
    private Instance b;

    /** Một "instance": cache hai tầng và bộ theo dõi Redis của nó. */
    private record Instance(DiagramCache cache, RedisCacheMonitor monitor) {
    }

    @BeforeEach
    void start() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(250))
                        .clientOptions(ClientOptions.builder()
                                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                                .timeoutOptions(TimeoutOptions.enabled()).build())
                        .build());
        connections.afterPropertiesSet();
        connections.start();
        connections.getConnection().serverCommands().flushAll();
        a = instance();
        b = instance();
        awaitTrue(() -> a.cache().remoteUp() && b.cache().remoteUp(), "both instances use Redis");
    }

    @AfterEach
    void stop() {
        a.monitor().stop();
        b.monitor().stop();
        connections.destroy();
    }

    private Instance instance() {
        RedisDiagramTier tier = new RedisDiagramTier(connections, Duration.ofMinutes(30), Duration.ofSeconds(60));
        DiagramCache cache = new DiagramCache(DiagramCache.Mode.TWO_LEVEL,
                new LocalDiagramTier(64 * 1024 * 1024, Duration.ofMinutes(10), Duration.ofSeconds(60)), tier,
                2 * 1024 * 1024, new SimpleMeterRegistry());
        RedisCacheMonitor monitor = new RedisCacheMonitor(cache, tier, connections, true, MONITOR);
        monitor.start();
        return new Instance(cache, monitor);
    }

    @Test
    void redisKeepsOnlyTheNewestVersionAndServesAccessInfoWithoutData() {
        RedisDiagramTier tier = new RedisDiagramTier(connections, Duration.ofMinutes(30), Duration.ofSeconds(60));
        UUID id = UUID.randomUUID();
        Diagram v2 = diagram(id, 2, "Tiêu đề có\u001fký tự lạ", true);

        assertThat(tier.putIfNewer(CachedDiagram.of(v2))).isTrue();
        assertThat(tier.putIfNewer(CachedDiagram.of(diagram(id, 1, "older", false)))).isFalse();
        assertThat(tier.putIfNewer(CachedDiagram.of(v2))).isFalse();

        CachedDiagram stored = tier.get(id).orElseThrow();
        assertThat(stored).isEqualTo(CachedDiagram.of(v2));
        assertThat(tier.getAccessInfo(id)).contains(Optional.of(new DiagramAccessInfo(id, OWNER, true, 2)));
        assertThat(tier.get(UUID.randomUUID())).isEmpty();

        assertThat(tier.putIfNewer(CachedDiagram.tombstone(id))).isTrue();
        assertThat(tier.putIfNewer(CachedDiagram.of(diagram(id, 3, "after delete", false)))).isFalse();
        assertThat(tier.get(id)).contains(CachedDiagram.tombstone(id));
        assertThat(tier.getAccessInfo(id)).contains(Optional.empty());
    }

    @Test
    void secondInstanceIsServedByRedisAndDropsItsCopyWhenTheFirstWrites() {
        UUID id = UUID.randomUUID();
        save(diagram(id, 1, "v1", false));
        read(a, id);
        assertThat(databaseReads).hasValue(1);
        assertThat(read(b, id).getTitle()).isEqualTo("v1");
        assertThat(databaseReads).hasValue(1);                       // B lấy từ Redis, không hỏi CSDL

        Diagram v2 = save(diagram(id, 2, "v2", false));
        a.cache().written(v2);

        assertThat(read(a, id).getVersion()).isEqualTo(2);           // người ghi đọc được ngay
        awaitTrue(() -> read(b, id).getVersion() == 2, "B sees version 2");
        assertThat(databaseReads).hasValue(1);                       // B lấy bản mới từ Redis
    }

    @Test
    void privacyChangeOnOneInstanceReachesTheOtherQuickly() {
        UUID id = UUID.randomUUID();
        save(diagram(id, 1, "public", true));
        assertThat(info(b, id).isPublic()).isTrue();
        read(b, id);

        a.cache().written(save(diagram(id, 2, "now private", false)));

        awaitTrue(() -> !info(b, id).isPublic(), "B sees the diagram as private");
    }

    @Test
    void deleteOnOneInstanceIsSeenByTheOther() {
        UUID id = UUID.randomUUID();
        save(diagram(id, 1, "doomed", false));
        read(a, id);
        read(b, id);

        database.remove(id);
        a.cache().deleted(id);

        awaitTrue(() -> b.cache().find(id, this::failIfLoaded).isEmpty(), "B answers 404");
        assertThat(b.cache().findAccessInfo(id, () -> Optional.empty())).isEmpty();
    }

    @Test
    void whenRedisHangsBothInstancesFallBackToTheDatabaseAndNeverServeStaleDataAfterwards() throws Exception {
        UUID id = UUID.randomUUID();
        save(diagram(id, 1, "v1", false));
        read(a, id);
        read(b, id);

        pauseRedis(true);
        try {
            awaitTrue(() -> !a.cache().remoteUp() && !b.cache().remoteUp(), "both stop using Redis");
            // Ghi trong lúc Redis treo: Redis vẫn giữ version 1, không ai báo được cho B
            a.cache().written(save(diagram(id, 2, "written while Redis hangs", false)));
            int before = databaseReads.get();
            assertThat(read(b, id).getVersion()).isEqualTo(2);       // B đọc thẳng CSDL, không dùng L1 cũ
            assertThat(read(a, id).getVersion()).isEqualTo(2);
            assertThat(databaseReads.get()).isEqualTo(before + 2);
        } finally {
            pauseRedis(false);
        }

        awaitTrue(() -> a.cache().remoteUp() && b.cache().remoteUp(), "both use Redis again");
        // Bản version 1 còn trên Redis đã bị A xóa khi hồi phục: không instance nào đọc lại nó
        assertThat(read(b, id).getVersion()).isEqualTo(2);
        assertThat(read(a, id).getVersion()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private Diagram save(Diagram diagram) {
        database.put(diagram.getId(), diagram);
        return diagram;
    }

    private Diagram read(Instance instance, UUID id) {
        return instance.cache().find(id, () -> {
            databaseReads.incrementAndGet();
            return Optional.ofNullable(database.get(id)).map(d -> new Diagram(d.getId(), d.getTitle(),
                    d.getDiagramType(), d.getData(), d.getVersion(), d.isPublic(), d.getOwnerId(), d.getUpdatedBy(),
                    d.getCreatedAt(), d.getUpdatedAt()));
        }).orElseThrow();
    }

    private DiagramAccessInfo info(Instance instance, UUID id) {
        return instance.cache().findAccessInfo(id, () -> {
            databaseReads.incrementAndGet();
            return Optional.ofNullable(database.get(id)).map(Diagram::accessInfo);
        }).orElseThrow();
    }

    private Optional<Diagram> failIfLoaded() {
        throw new AssertionError("should have been answered by the cache");
    }

    private static Diagram diagram(UUID id, int version, String title, boolean isPublic) {
        return new Diagram(id, title, DiagramType.UML,
                DiagramData.fromStorage("{\"nodes\": [{\"id\": \"v" + version + "\"}], \"edges\": []}"), version,
                isPublic, OWNER, null, T0, T0.plusSeconds(version));
    }

    private static void pauseRedis(boolean pause) {
        var docker = redis.getDockerClient();
        if (pause) {
            docker.pauseContainerCmd(redis.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("timed out waiting until " + what);
    }
}
