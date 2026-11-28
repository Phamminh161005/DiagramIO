package com.diagramio.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Tầng L1: Caffeine trong JVM, giới hạn theo dung lượng ({@link CachedDiagram#weight()}), hết hạn sau
 * {@code ttl} kể từ lần ghi (bia mộ: {@code tombstoneTtl}).
 *
 * <p>Mỗi mục mang epoch của cache lúc nó được ghi. {@link DiagramCache} tăng epoch khi phải bỏ toàn bộ L1; mục của
 * epoch cũ coi như không có, kể cả mục được ghi xong sau khi {@link #clear()} đã đi qua khóa của nó.
 *
 * <p>Mọi lần ghi đi qua {@link #putIfNewer}: chỉ thay mục đang có khi version mới lớn hơn, so và thay trong
 * {@code asMap().compute} nên nguyên tử; điều kiện riêng của người gọi (epoch chưa đổi, version chưa thấp hơn sàn)
 * cũng được kiểm tra bên trong compute đó. Bỏ mục cũng dùng compute, nên một thay đổi của cùng khóa không lọt vào
 * giữa lần kiểm tra và lần ghi của một putIfNewer đang chạy.
 */
public final class LocalDiagramTier {

    /** Một mục L1 cùng epoch của cache lúc mục được ghi. */
    private record Slot(CachedDiagram entry, long epoch) {
    }

    private final Cache<UUID, Slot> cache;

    public LocalDiagramTier(long maxBytes, Duration ttl, Duration tombstoneTtl) {
        long ttlNanos = ttl.toNanos();
        long tombstoneNanos = tombstoneTtl.toNanos();
        this.cache = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((UUID id, Slot slot) -> slot.entry().weight())
                .expireAfter(new Expiry<UUID, Slot>() {
                    @Override
                    public long expireAfterCreate(UUID id, Slot slot, long now) {
                        return slot.entry().deleted() ? tombstoneNanos : ttlNanos;
                    }

                    @Override
                    public long expireAfterUpdate(UUID id, Slot slot, long now, long remaining) {
                        return slot.entry().deleted() ? tombstoneNanos : ttlNanos;
                    }

                    @Override
                    public long expireAfterRead(UUID id, Slot slot, long now, long remaining) {
                        return remaining;
                    }
                })
                .recordStats()
                .build();
    }

    /** Caffeine bên dưới, để đăng ký số liệu (hit, miss, eviction) với Micrometer. */
    public Cache<UUID, ?> caffeine() {
        return cache;
    }

    /** Mục đang có (kể cả bia mộ) nếu nó được ghi ở epoch {@code epoch}, null nếu không; tính vào số liệu hit/miss. */
    public CachedDiagram get(UUID id, long epoch) {
        Slot slot = cache.getIfPresent(id);
        return slot != null && slot.epoch() == epoch ? slot.entry() : null;
    }

    /**
     * Ghi {@code entry} ở epoch {@code epoch} nếu chưa có mục nào (của epoch này) hoặc mục đang có có version nhỏ
     * hơn, và {@code valid} vẫn đúng lúc ghi; trả true nếu đã ghi.
     */
    public boolean putIfNewer(CachedDiagram entry, long epoch, BooleanSupplier valid) {
        boolean[] stored = {false};
        cache.asMap().compute(entry.id(), (id, current) -> {
            boolean replaceable = current == null || current.epoch() != epoch
                    || current.entry().version() < entry.version();
            if (replaceable && valid.getAsBoolean()) {
                stored[0] = true;
                return new Slot(entry, epoch);
            }
            return current;
        });
        return stored[0];
    }

    /** Bỏ mục nếu version của nó nhỏ hơn {@code version}. */
    public void evictIfOlder(UUID id, int version) {
        cache.asMap().compute(id, (key, current) ->
                current != null && current.entry().version() < version ? null : current);
    }

    /** Giải phóng bộ nhớ sau khi tăng epoch (mục còn sót nhờ chạy song song đã vô hiệu theo epoch). */
    public void clear() {
        cache.invalidateAll();
    }
}
