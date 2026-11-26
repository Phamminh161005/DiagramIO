package com.diagramio.config;

import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.version.DiagramVersionRepository;
import com.diagramio.infrastructure.cache.CachingDiagramRepository;
import com.diagramio.infrastructure.cache.CachingDiagramVersionRepository;
import com.diagramio.infrastructure.cache.CachingShareRepository;
import com.diagramio.infrastructure.cache.DiagramCache;
import com.diagramio.infrastructure.cache.LocalDiagramTier;
import com.diagramio.infrastructure.cache.LocalShareTokenStore;
import com.diagramio.infrastructure.cache.RedisCacheMonitor;
import com.diagramio.infrastructure.cache.RedisDiagramTier;
import com.diagramio.infrastructure.cache.RedisShareTokenStore;
import com.diagramio.infrastructure.cache.ShareTokenStore;
import com.diagramio.infrastructure.diagram.DiagramRepositoryAdapter;
import com.diagramio.infrastructure.share.DiagramShareRepositoryAdapter;
import com.diagramio.infrastructure.version.DiagramVersionRepositoryAdapter;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Cache sơ đồ (B2) và token chia sẻ (B3). Decorator cache được đăng ký làm {@code DiagramRepository},
 * {@code DiagramVersionRepository}, {@code DiagramShareRepository} chính, nên domain service nhận nó mà không biết
 * có cache. Chế độ {@code diagramio.cache.mode} chọn tầng nào được dùng ({@code none} để so A/B với B1 trên cùng một
 * file jar); Redis chỉ được kết nối khi chế độ có Redis.
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    @Bean
    public DiagramCache diagramCache(CacheProperties properties, ObjectProvider<RedisConnectionFactory> redis,
                                     MeterRegistry registry) {
        DiagramCache.Mode mode = properties.mode();
        LocalDiagramTier local = mode.usesLocal()
                ? new LocalDiagramTier(properties.localMaxSize().toBytes(), properties.localTtl(),
                        properties.tombstoneTtl())
                : null;
        RedisDiagramTier remote = mode.usesRedis()
                ? new RedisDiagramTier(redis.getObject(), properties.redisTtl(), properties.tombstoneTtl())
                : null;
        int maxDataChars = (int) Math.min(Integer.MAX_VALUE, properties.maxEntrySize().toBytes());
        return new DiagramCache(mode, local, remote, maxDataChars, registry);
    }

    @Bean
    @Primary
    public DiagramRepository cachingDiagramRepository(DiagramRepositoryAdapter adapter, DiagramCache cache,
                                                      CachingShareRepository shares, CacheProperties properties,
                                                      MeterRegistry registry) {
        return new CachingDiagramRepository(adapter, cache, properties.publicListTtl(), registry,
                shares::beforeDiagramDelete);
    }

    /**
     * Cache token chia sẻ (B3): chế độ local thì trong JVM, chế độ có Redis thì CHỈ trên Redis (không có bản trong
     * JVM, để thu hồi có hiệu lực ngay ở mọi instance). Tắt khi mode=none hoặc share-tokens=false.
     */
    @Bean
    @Primary
    public CachingShareRepository cachingShareRepository(DiagramShareRepositoryAdapter adapter, DiagramCache cache,
                                                         CacheProperties properties,
                                                         ObjectProvider<RedisConnectionFactory> redis,
                                                         MeterRegistry registry) {
        DiagramCache.Mode mode = properties.mode();
        ShareTokenStore store = null;
        if (properties.shareTokens() && mode.usesRedis()) {
            store = new RedisShareTokenStore(redis.getObject(), properties.shareTokenTtl(),
                    properties.shareTokenNegativeTtl());
        } else if (properties.shareTokens() && mode == DiagramCache.Mode.LOCAL) {
            store = new LocalShareTokenStore(properties.shareTokenMaxEntries(), properties.shareTokenTtl(),
                    properties.shareTokenNegativeTtl());
        }
        // Redis do bộ theo dõi của cache sơ đồ báo là không dùng được thì tra cứu đi thẳng CSDL
        BooleanSupplier usable = mode.usesRedis() ? cache::remoteUp : () -> true;
        return new CachingShareRepository(adapter, store, usable, registry);
    }

    @Bean
    @Primary
    public DiagramVersionRepository cachingDiagramVersionRepository(DiagramVersionRepositoryAdapter adapter,
                                                                    CacheProperties properties,
                                                                    MeterRegistry registry) {
        long maxBytes = properties.mode() == DiagramCache.Mode.NONE ? 0 : properties.versionMaxSize().toBytes();
        return new CachingDiagramVersionRepository(adapter, maxBytes, registry);
    }

    /** Theo dõi Redis, nhận thông báo hủy L1 (chế độ two-level). */
    @Bean
    @Conditional(UsesRedis.class)
    public RedisCacheMonitor redisCacheMonitor(DiagramCache cache, RedisConnectionFactory connectionFactory,
                                               CacheProperties properties) {
        return new RedisCacheMonitor(cache, cache.remoteTier(), connectionFactory, properties.mode().usesLocal(),
                properties.monitorInterval());
    }

    /**
     * Redis chết thì lệnh phải lỗi ngay (cache chuyển sang đọc CSDL), không xếp hàng chờ kết nối lại tới hết thời
     * gian chờ. Thời gian chờ mỗi lệnh đặt ở {@code spring.data.redis.timeout}.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer failFastRedisClient() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                .timeoutOptions(TimeoutOptions.enabled())
                .build());
    }

    /** Chế độ cache có dùng Redis không ({@code redis}, {@code two-level}). */
    static class UsesRedis implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return Binder.get(context.getEnvironment()).bind("diagramio.cache.mode", DiagramCache.Mode.class)
                    .map(DiagramCache.Mode::usesRedis).orElse(false);
        }
    }
}
