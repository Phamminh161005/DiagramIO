package com.diagramio.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.util.UUID;

/**
 * Cache token trong JVM (chế độ {@code local}, một instance). Kiểm tra dấu và ghi mục trong {@link #fill}, đặt dấu
 * trong {@link #markChanging} cùng giữ một khóa, nên không lần nạp nào lọt vào giữa lúc đặt dấu và lúc đổi CSDL.
 */
public final class LocalShareTokenStore implements ShareTokenStore {

    /** Giá trị trong Caffeine: {@code entry} null là cache âm. */
    private record Slot(Entry entry) {
    }

    private final Cache<String, Slot> tokens;
    private final Cache<UUID, Boolean> changing;
    private final Object lock = new Object();

    public LocalShareTokenStore(long maxEntries, Duration ttl, Duration negativeTtl) {
        long ttlNanos = ttl.toNanos();
        long negativeNanos = negativeTtl.toNanos();
        this.tokens = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfter(new Expiry<String, Slot>() {
                    @Override
                    public long expireAfterCreate(String key, Slot slot, long now) {
                        return slot.entry() == null ? negativeNanos : ttlNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, Slot slot, long now, long remaining) {
                        return slot.entry() == null ? negativeNanos : ttlNanos;
                    }

                    @Override
                    public long expireAfterRead(String key, Slot slot, long now, long remaining) {
                        return remaining;
                    }
                })
                .build();
        // Dấu sống đúng bằng thời gian sống của mục dương (xem ShareTokenStore)
        this.changing = Caffeine.newBuilder().expireAfterWrite(ttl).build();
    }

    @Override
    public Lookup lookup(String tokenHash) {
        Slot slot = tokens.getIfPresent(tokenHash);
        if (slot == null) {
            return Lookup.MISS;
        }
        if (slot.entry() == null) {
            return Lookup.ABSENT;
        }
        if (changing.getIfPresent(slot.entry().shareId()) != null) {
            return Lookup.BYPASS;
        }
        return Lookup.hit(slot.entry());
    }

    @Override
    public void fill(String tokenHash, Entry entry) {
        synchronized (lock) {
            if (entry != null && changing.getIfPresent(entry.shareId()) != null) {
                return;
            }
            tokens.asMap().putIfAbsent(tokenHash, new Slot(entry));
        }
    }

    @Override
    public void put(String tokenHash, Entry entry) {
        tokens.put(tokenHash, new Slot(entry));
    }

    @Override
    public void markChanging(UUID shareId) {
        synchronized (lock) {
            changing.put(shareId, Boolean.TRUE);
        }
    }

    @Override
    public void drop(String tokenHash) {
        tokens.invalidate(tokenHash);
    }
}
