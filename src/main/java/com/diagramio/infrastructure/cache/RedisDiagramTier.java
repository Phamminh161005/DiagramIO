package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Tầng L2: Redis dùng chung giữa các instance. Mỗi sơ đồ là một hash {@code diagramio:diagram:<id>} gồm ba trường:
 * {@code v} (version), {@code h} (phần đầu: chủ, công khai, tiêu đề, thời điểm) và {@code d} (data). Kiểm tra quyền
 * chỉ đọc {@code v}, {@code h} (vài trăm byte) thay vì cả data tới 2 MB.
 *
 * <p>Ghi bằng một Lua script so version ngay trên Redis: chỉ ghi khi version mới lớn hơn version đang lưu, nên hai
 * instance ghi chen nhau không làm bản cũ đè bản mới. Lỗi kết nối, hết thời gian chờ được ném ra cho
 * {@link DiagramCache} xử lý (chuyển sang đọc CSDL).
 */
public final class RedisDiagramTier {

    static final String KEY_PREFIX = "diagramio:diagram:";
    public static final String CHANGES_CHANNEL = "diagramio:diagram-changed";

    private static final byte[] VERSION = bytes("v");
    private static final byte[] HEADER = bytes("h");
    private static final byte[] DATA = bytes("d");
    private static final byte[] DELETED = bytes("-");

    /**
     * KEYS[1] khóa; ARGV: version, phần đầu, data, TTL (ms), [kênh, tin]. Trả 1 nếu đã ghi, 0 nếu đang có bản mới
     * hơn. Có kênh thì publish tin trong cùng script (dù có ghi hay không: CSDL đã đổi, instance khác cần bỏ bản cũ
     * hơn), nên một lần ghi sơ đồ chỉ tốn một lượt tới Redis.
     */
    private static final RedisScript<Long> PUT_IF_NEWER = new DefaultRedisScript<>("""
            local stored = 0
            local current = redis.call('HGET', KEYS[1], 'v')
            if not current or tonumber(current) < tonumber(ARGV[1]) then
              redis.call('HSET', KEYS[1], 'v', ARGV[1], 'h', ARGV[2], 'd', ARGV[3])
              redis.call('PEXPIRE', KEYS[1], ARGV[4])
              stored = 1
            end
            if #ARGV >= 6 then
              redis.call('PUBLISH', ARGV[5], ARGV[6])
            end
            return stored
            """, Long.class);

    /** KEYS[1] khóa; ARGV: kênh, tin. Xóa mục rồi publish, một lượt tới Redis. */
    private static final RedisScript<Long> DELETE_AND_PUBLISH = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            return redis.call('PUBLISH', ARGV[1], ARGV[2])
            """, Long.class);

    private final RedisTemplate<String, byte[]> redis;
    private final Duration ttl;
    private final Duration tombstoneTtl;

    public RedisDiagramTier(RedisConnectionFactory connectionFactory, Duration ttl, Duration tombstoneTtl) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(RedisSerializer.byteArray());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        this.redis = template;
        this.ttl = ttl;
        this.tombstoneTtl = tombstoneTtl;
    }

    /** Cả mục (kể cả bia mộ), rỗng nếu Redis không có. */
    public Optional<CachedDiagram> get(UUID id) {
        List<byte[]> fields = redis.execute((RedisCallback<List<byte[]>>) connection ->
                connection.hashCommands().hMGet(keyBytes(id), VERSION, HEADER, DATA));
        if (fields == null || fields.size() < 3 || fields.get(0) == null || fields.get(1) == null) {
            return Optional.empty();
        }
        Header header = Header.decode(id, fields.get(1));
        if (header == null) {
            return Optional.of(CachedDiagram.tombstone(id));
        }
        if (fields.get(2) == null) {
            return Optional.empty();
        }
        DiagramData data = DiagramData.fromStorage(new String(fields.get(2), StandardCharsets.UTF_8));
        return Optional.of(header.toEntry(id, data));
    }

    /**
     * Chỉ thông tin kiểm tra quyền. Rỗng nếu Redis không có mục này; chứa {@code Optional.empty()} nếu là bia mộ
     * (sơ đồ đã bị xóa).
     */
    public Optional<Optional<DiagramAccessInfo>> getAccessInfo(UUID id) {
        List<byte[]> fields = redis.execute((RedisCallback<List<byte[]>>) connection ->
                connection.hashCommands().hMGet(keyBytes(id), VERSION, HEADER));
        if (fields == null || fields.size() < 2 || fields.get(0) == null || fields.get(1) == null) {
            return Optional.empty();
        }
        Header header = Header.decode(id, fields.get(1));
        return Optional.of(header == null ? Optional.empty()
                : Optional.of(new DiagramAccessInfo(id, header.ownerId, header.isPublic, header.version)));
    }

    /** Ghi nếu Redis chưa có bản mới hơn (nạp từ CSDL, không phải thay đổi); trả true nếu đã ghi. */
    public boolean putIfNewer(CachedDiagram entry) {
        return put(entry, null);
    }

    /**
     * Sơ đồ vừa đổi trong CSDL: ghi nếu Redis chưa có bản mới hơn và báo cho instance khác, trong một script.
     * Trả true nếu đã ghi.
     */
    public boolean putIfNewerAndPublish(CachedDiagram entry, String origin) {
        return put(entry, changeMessage(entry.id(), entry.version(), origin));
    }

    private boolean put(CachedDiagram entry, byte[] message) {
        byte[] header = entry.deleted() ? DELETED : Header.encode(entry);
        byte[] data = entry.deleted() ? new byte[0] : entry.data().json().getBytes(StandardCharsets.UTF_8);
        long ttlMillis = (entry.deleted() ? tombstoneTtl : ttl).toMillis();
        byte[][] args = message == null
                ? new byte[][] {bytes(Integer.toString(entry.version())), header, data, bytes(Long.toString(ttlMillis))}
                : new byte[][] {bytes(Integer.toString(entry.version())), header, data, bytes(Long.toString(ttlMillis)),
                        bytes(CHANGES_CHANNEL), message};
        Long stored = redis.execute(PUT_IF_NEWER, List.of(key(entry.id())), (Object[]) args);
        return stored != null && stored == 1L;
    }

    public void delete(UUID id) {
        redis.delete(key(id));
    }

    /** Bỏ mục (sơ đồ vừa đổi nhưng quá lớn để cache) và báo cho instance khác, trong một script. */
    public void deleteAndPublish(UUID id, int version, String origin) {
        redis.execute(DELETE_AND_PUBLISH, List.of(key(id)), bytes(CHANGES_CHANNEL), changeMessage(id, version, origin));
    }

    /** Báo cho các instance khác: sơ đồ {@code id} vừa có version {@code version} (bia mộ: lớn nhất). */
    public void publishChange(UUID id, int version, String origin) {
        redis.convertAndSend(CHANGES_CHANNEL, changeMessage(id, version, origin));
    }

    private static byte[] changeMessage(UUID id, int version, String origin) {
        return bytes(id + " " + version + " " + origin);
    }

    /**
     * Gửi heartbeat qua chính kênh thông báo thay đổi. Bộ theo dõi coi pub/sub là thông suốt khi instance nhận lại
     * được heartbeat của mình: kiểm tra đầu cuối, không phụ thuộc client tự nối lại kết nối nào trước.
     */
    public void publishHeartbeat(String origin, long sequence) {
        redis.convertAndSend(CHANGES_CHANNEL, bytes(HEARTBEAT + " " + origin + " " + sequence));
    }

    private static final String HEARTBEAT = "hb";

    /** Thông báo "sơ đồ {@code id} vừa có version {@code version}" từ instance {@code origin}. */
    public record Change(UUID id, int version, String origin) {
    }

    /** Heartbeat của instance {@code origin}. */
    public record Heartbeat(String origin, long sequence) {
    }

    /** Một tin trên kênh: {@link Change}, {@link Heartbeat}, hoặc null nếu sai định dạng. */
    public static Object parseMessage(byte[] body) {
        String[] parts = new String(body, StandardCharsets.UTF_8).split(" ");
        if (parts.length != 3) {
            return null;
        }
        try {
            if (parts[0].equals(HEARTBEAT)) {
                return new Heartbeat(parts[1], Long.parseLong(parts[2]));
            }
            return new Change(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean ping() {
        String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
        return "PONG".equalsIgnoreCase(pong);
    }

    private static String key(UUID id) {
        return KEY_PREFIX + id;
    }

    private static byte[] keyBytes(UUID id) {
        return bytes(key(id));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Phần đầu của mục: các trường nhỏ, nối bằng ký tự {@code \u001f} (unit separator, không xuất hiện trong UUID,
     * số hay thời điểm). Tiêu đề đứng cuối nên được phép chứa mọi ký tự.
     */
    private record Header(int version, UUID ownerId, boolean isPublic, DiagramType type, UUID updatedBy,
                          Instant createdAt, Instant updatedAt, String title) {

        private static final String SEP = "\u001f";

        static byte[] encode(CachedDiagram e) {
            return bytes(String.join(SEP, Integer.toString(e.version()), e.ownerId().toString(),
                    e.isPublic() ? "1" : "0", e.diagramType().name(), text(e.updatedBy()), text(e.createdAt()),
                    text(e.updatedAt()), e.title()));
        }

        /** null nếu là bia mộ. */
        static Header decode(UUID id, byte[] raw) {
            String value = new String(raw, StandardCharsets.UTF_8);
            if (value.equals("-")) {
                return null;
            }
            String[] f = value.split(SEP, 8);
            return new Header(Integer.parseInt(f[0]), UUID.fromString(f[1]), f[2].equals("1"), DiagramType.valueOf(f[3]),
                    f[4].isEmpty() ? null : UUID.fromString(f[4]), f[5].isEmpty() ? null : Instant.parse(f[5]),
                    f[6].isEmpty() ? null : Instant.parse(f[6]), f[7]);
        }

        CachedDiagram toEntry(UUID id, DiagramData data) {
            return new CachedDiagram(id, title, type, data, version, isPublic, ownerId, updatedBy, createdAt,
                    updatedAt, false);
        }

        private static String text(Object value) {
            return value == null ? "" : value.toString();
        }
    }
}
