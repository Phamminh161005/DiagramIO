package com.diagramio.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.util.UUID;

/**
 * Tầng L1: Caffeine trong JVM, giới hạn theo dung lượng ({@link CachedDiagram#weight()}), hết hạn sau
 * {@code ttl} kể từ lần ghi (bia mộ: {@code tombstoneTtl}).
 *
 * <p>Mọi lần ghi đi qua {@link #putIfNewer}: chỉ thay mục đang có khi version mới lớn hơn, so và thay trong
 * {@code asMap().compute} nên nguyên tử. Nhờ vậy một lần nạp chậm (đọc CSDL trước khi người khác ghi) không thể
 * ghi đè bản mới hơn, lỗi kinh điển của cache-aside chỉ xóa key.
 */
public final class LocalDiagramTier {

    private final Cache<UUID, CachedDiagram> cache;

    public LocalDiagramTier(long maxBytes, Duration ttl, Duration tombstoneTtl) {
        long ttlNanos = ttl.toNanos();
        long tombstoneNanos = tombstoneTtl.toNanos();
        this.cache = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((UUID id, CachedDiagram entry) -> entry.weight())
                .expireAfter(new Expiry<UUID, CachedDiagram>() {
                    @Override
                    public long expireAfterCreate(UUID id, CachedDiagram entry, long now) {
                        return entry.deleted() ? tombstoneNanos : ttlNanos;
                    }

                    @Override
                    public long expireAfterUpdate(UUID id, CachedDiagram entry, long now, long remaining) {
                        return entry.deleted() ? tombstoneNanos : ttlNanos;
                    }

                    @Override
                    public long expireAfterRead(UUID id, CachedDiagram entry, long now, long remaining) {
                        return remaining;
                    }
                })
                .recordStats()
                .build();
    }

    /** Caffeine bên dưới, để đăng ký số liệu (hit, miss, eviction) với Micrometer. */
    public Cache<UUID, CachedDiagram> caffeine() {
        return cache;
    }

    /** Mục đang có (kể cả bia mộ), null nếu không có; tính vào số liệu hit/miss. */
    public CachedDiagram get(UUID id) {
        return cache.getIfPresent(id);
    }

    /** Ghi nếu chưa có mục nào hoặc mục đang có có version nhỏ hơn; trả true nếu đã ghi. */
    public boolean putIfNewer(CachedDiagram entry) {
        boolean[] stored = {false};
        cache.asMap().compute(entry.id(), (id, current) -> {
            if (current == null || current.version() < entry.version()) {
                stored[0] = true;
                return entry;
            }
            return current;
        });
        return stored[0];
    }

    /** Bỏ mục nếu version của nó nhỏ hơn {@code version} (thông báo thay đổi từ instance khác). */
    public void evictIfOlder(UUID id, int version) {
        cache.asMap().computeIfPresent(id, (key, current) -> current.version() < version ? null : current);
    }

    public void evict(UUID id) {
        cache.invalidate(id);
    }

    public void clear() {
        cache.invalidateAll();
    }
}
