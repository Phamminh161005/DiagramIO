package com.diagramio.infrastructure.cache;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Cache token chỉ trên Redis (chế độ {@code redis}, {@code two-level}): không có bản trong JVM, nên mọi instance thấy
 * cùng một trạng thái ngay ở request tiếp theo. Mỗi thao tác là một lệnh (Lua) tới Redis.
 *
 * <ul>
 *   <li>{@code diagramio:share-token:<sha256>}: {@code shareId|diagramId|role|createdAt}, hoặc {@code -} (cache âm);</li>
 *   <li>{@code diagramio:share-changing:<shareId>}: dấu "đang đổi", sống bằng thời gian sống của mục dương.</li>
 * </ul>
 *
 * <p>Script tra cứu đọc khóa dấu suy ra từ giá trị (không khai báo trước trong KEYS), nên chỉ dùng với Redis đơn lẻ
 * hoặc bản sao, không dùng với Redis Cluster.
 */
public final class RedisShareTokenStore implements ShareTokenStore {

    static final String TOKEN_PREFIX = "diagramio:share-token:";
    static final String CHANGING_PREFIX = "diagramio:share-changing:";
    private static final String ABSENT = "-";
    private static final String BYPASS = "?";

    /** KEYS[1] token; ARGV[1] tiền tố khóa dấu. Trả giá trị, "-" (âm), "?" (đang đổi) hoặc nil. */
    private static final RedisScript<String> LOOKUP = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value or value == '-' then
              return value
            end
            local sep = string.find(value, '|', 1, true)
            if sep and redis.call('EXISTS', ARGV[1] .. string.sub(value, 1, sep - 1)) == 1 then
              return '?'
            end
            return value
            """, String.class);

    /** KEYS[1] token, KEYS[2] (nếu có) dấu của link; ARGV: giá trị, TTL (ms). Chỉ ghi khi khóa trống và không có dấu. */
    private static final RedisScript<Long> FILL = new DefaultRedisScript<>("""
            if #KEYS > 1 and redis.call('EXISTS', KEYS[2]) == 1 then
              return 0
            end
            if redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX') then
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration negativeTtl;

    public RedisShareTokenStore(RedisConnectionFactory connectionFactory, Duration ttl, Duration negativeTtl) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        this.redis = template;
        this.ttl = ttl;
        this.negativeTtl = negativeTtl;
    }

    @Override
    public Lookup lookup(String tokenHash) {
        String value = redis.execute(LOOKUP, List.of(TOKEN_PREFIX + tokenHash), CHANGING_PREFIX);
        if (value == null) {
            return Lookup.MISS;
        }
        if (ABSENT.equals(value)) {
            return Lookup.ABSENT;
        }
        if (BYPASS.equals(value)) {
            return Lookup.BYPASS;
        }
        return Lookup.hit(Entry.decode(value));
    }

    @Override
    public void fill(String tokenHash, Entry entry) {
        if (entry == null) {
            redis.execute(FILL, List.of(TOKEN_PREFIX + tokenHash), ABSENT, Long.toString(negativeTtl.toMillis()));
        } else {
            redis.execute(FILL, List.of(TOKEN_PREFIX + tokenHash, CHANGING_PREFIX + entry.shareId()), entry.encode(),
                    Long.toString(ttl.toMillis()));
        }
    }

    @Override
    public void put(String tokenHash, Entry entry) {
        redis.opsForValue().set(TOKEN_PREFIX + tokenHash, entry.encode(), ttl);
    }

    @Override
    public void markChanging(UUID shareId) {
        redis.opsForValue().set(CHANGING_PREFIX + shareId, "1", ttl);
    }

    @Override
    public void drop(String tokenHash) {
        redis.delete(TOKEN_PREFIX + tokenHash);
    }
}
