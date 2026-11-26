package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.TestDatabase;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.ServiceUnavailableException;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.share.DiagramShareRepository;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cache token trên Redis thật (Testcontainers): các script Lua và hành vi khi Redis treo. */
@Testcontainers(disabledWithoutDocker = true)
class RedisShareTokenStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-26T08:00:00Z");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(TestDatabase.REDIS_IMAGE).withExposedPorts(6379);

    private LettuceConnectionFactory connections;
    private StringRedisTemplate raw;
    private RedisShareTokenStore store;
    private final String token = UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef";
    private final String hash = ShareTokenStore.hash(token);
    private final UUID shareId = UUID.randomUUID();
    private final ShareTokenStore.Entry entry = new ShareTokenStore.Entry(shareId, UUID.randomUUID(), ShareRole.EDIT, T0);

    @BeforeEach
    void connect() {
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
        raw = new StringRedisTemplate(connections);
        raw.getConnectionFactory().getConnection().serverCommands().flushAll();
        store = new RedisShareTokenStore(connections, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    @AfterEach
    void disconnect() {
        connections.destroy();
    }

    @Test
    void filledEntryIsReadBackAndStoredUnderTheHashWithATtl() {
        assertThat(store.lookup(hash).kind()).isEqualTo(ShareTokenStore.Kind.MISS);

        store.fill(hash, entry);

        ShareTokenStore.Lookup found = store.lookup(hash);
        assertThat(found.kind()).isEqualTo(ShareTokenStore.Kind.HIT);
        assertThat(found.entry()).isEqualTo(entry);
        Set<String> keys = raw.keys("*");
        assertThat(keys).containsExactly(RedisShareTokenStore.TOKEN_PREFIX + hash);
        assertThat(String.join(",", keys) + raw.opsForValue().get(RedisShareTokenStore.TOKEN_PREFIX + hash))
                .doesNotContain(token);   // token gốc không bao giờ nằm trên Redis
        assertThat(raw.getExpire(RedisShareTokenStore.TOKEN_PREFIX + hash)).isBetween(290L, 300L);
    }

    @Test
    void fillNeverOverwritesAndAbsentEntriesExpireSooner() {
        store.fill(hash, null);
        store.fill(hash, entry);   // lần nạp sau không đè lên cái đang có

        assertThat(store.lookup(hash).kind()).isEqualTo(ShareTokenStore.Kind.ABSENT);
        assertThat(raw.getExpire(RedisShareTokenStore.TOKEN_PREFIX + hash)).isBetween(25L, 30L);
    }

    @Test
    void newLinkOverwritesAnAbsentEntry() {
        store.fill(hash, null);

        store.put(hash, entry);

        assertThat(store.lookup(hash).entry()).isEqualTo(entry);
    }

    @Test
    void changeMarkerHidesTheEntryAndBlocksFillsForAsLongAsAnEntryLives() {
        store.fill(hash, entry);

        store.markChanging(shareId);

        assertThat(store.lookup(hash).kind()).isEqualTo(ShareTokenStore.Kind.BYPASS);
        store.drop(hash);
        store.fill(hash, entry);   // lần tra cứu chậm cố nạp lại quyền cũ
        assertThat(store.lookup(hash).kind()).isEqualTo(ShareTokenStore.Kind.MISS);
        assertThat(raw.getExpire(RedisShareTokenStore.CHANGING_PREFIX + shareId)).isBetween(290L, 300L);
        // Cache âm (token không còn) không phụ thuộc dấu
        store.fill(hash, null);
        assertThat(store.lookup(hash).kind()).isEqualTo(ShareTokenStore.Kind.ABSENT);
    }

    @Test
    void whenRedisHangsLinksAreNotChangedAndLookupsUseTheDatabase() {
        DiagramShareRepository db = mock(DiagramShareRepository.class);
        DiagramShare share = entry.toShare(token);
        when(db.findByShareToken(token)).thenReturn(Optional.of(share));
        CachingShareRepository shares = new CachingShareRepository(db, store, () -> true, new SimpleMeterRegistry());
        shares.findByShareToken(token);

        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            // Không đặt được dấu: không thu hồi, báo 503 để client thử lại
            assertThatThrownBy(() -> shares.deleteByIdAndDiagramId(shareId, share.getDiagramId()))
                    .isInstanceOf(ServiceUnavailableException.class);
            verify(db, never()).deleteByIdAndDiagramId(any(), any());
            // Tra cứu vẫn trả lời được, từ CSDL
            assertThat(shares.findByShareToken(token)).contains(share);
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }

        when(db.deleteByIdAndDiagramId(shareId, share.getDiagramId())).thenReturn(Optional.of(share));
        when(db.findByShareToken(token)).thenReturn(Optional.empty());
        shares.deleteByIdAndDiagramId(shareId, share.getDiagramId());
        assertThat(shares.findByShareToken(token)).isEmpty();
    }
}
