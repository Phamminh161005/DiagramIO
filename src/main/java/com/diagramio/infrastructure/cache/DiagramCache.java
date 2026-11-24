package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache sơ đồ hai tầng: L1 Caffeine trong JVM, L2 Redis dùng chung. Chế độ ({@link Mode}) quyết định có tầng nào;
 * {@link Mode#NONE} là đi thẳng CSDL, để đo A/B trên cùng một file jar.
 *
 * <p>Quy tắc nhất quán (ADR-0003):
 * <ul>
 *   <li><b>Write-through có so version.</b> Sau khi ghi CSDL (và sau commit), trạng thái mới được ghi thẳng vào cả
 *       hai tầng, chỉ khi version lớn hơn version đang có. Lần nạp từ CSDL cũng đi qua phép so này, nên không lần
 *       nạp chậm nào đè được bản mới.</li>
 *   <li><b>Bia mộ khi xóa</b>: version lớn nhất, nên đọc ngay sau khi xóa trả 404 mà không ghi đè lại được.</li>
 *   <li><b>Nhiều instance</b>: sau khi ghi, gửi {@code (id, version)} qua Redis pub/sub; instance khác bỏ bản L1
 *       cũ hơn. Người vừa ghi luôn đọc được bản mới; instance khác có thể thấy bản cũ trong khoảng trễ pub/sub.</li>
 *   <li><b>Redis không dùng được</b> (lỗi lệnh hoặc bộ theo dõi báo mất kết nối, mất đăng ký pub/sub): ngừng dùng
 *       cả L2 lẫn L1 (L1 có thể đã lỡ thông báo hủy), đọc thẳng CSDL. Khi Redis trở lại: xóa trên Redis các sơ đồ
 *       đã ghi trong lúc mất kết nối, xóa sạch L1, rồi mới dùng lại.</li>
 *   <li><b>Gộp lần nạp</b>: nhiều request cùng trượt một sơ đồ trong một instance chỉ đọc CSDL một lần.</li>
 * </ul>
 */
public final class DiagramCache {

    public enum Mode {
        NONE, LOCAL, REDIS, TWO_LEVEL;

        public boolean usesLocal() {
            return this == LOCAL || this == TWO_LEVEL;
        }

        public boolean usesRedis() {
            return this == REDIS || this == TWO_LEVEL;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DiagramCache.class);

    private final Mode mode;
    private final LocalDiagramTier local;
    private final RedisDiagramTier remote;
    private final int maxDataChars;
    private final String instanceId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<UUID, CompletableFuture<Optional<CachedDiagram>>> loading =
            new ConcurrentHashMap<>();
    /** Sơ đồ đã ghi CSDL trong lúc không ghi được Redis: bản trên Redis (nếu có) đã cũ, phải xóa khi Redis trở lại. */
    private final Set<UUID> missedRemoteWrites = ConcurrentHashMap.newKeySet();
    /** Có tầng Redis thì bắt đầu ở trạng thái "chưa dùng được", chờ bộ theo dõi xác nhận kết nối và đăng ký. */
    private volatile boolean remoteUp;
    private final Counter remoteHit;
    private final Counter remoteMiss;
    private final Counter remoteError;

    public DiagramCache(Mode mode, LocalDiagramTier local, RedisDiagramTier remote, int maxDataChars,
                        MeterRegistry registry) {
        if (mode.usesLocal() != (local != null) || mode.usesRedis() != (remote != null)) {
            throw new IllegalArgumentException("cache tiers do not match mode " + mode);
        }
        this.mode = mode;
        this.local = local;
        this.remote = remote;
        this.maxDataChars = maxDataChars;
        this.remoteUp = false;
        // Bộ đếm L2 chỉ có khi chế độ có Redis (mọi chỗ dùng chúng đều nằm sau kiểm tra remote != null)
        this.remoteHit = remote == null ? null : l2Counter(registry, "hit");
        this.remoteMiss = remote == null ? null : l2Counter(registry, "miss");
        this.remoteError = remote == null ? null : l2Counter(registry, "error");
        if (local != null) {
            CaffeineCacheMetrics.monitor(registry, local.caffeine(), "diagrams");
        }
        if (remote != null) {
            Gauge.builder("diagramio.cache.l2.up", this, cache -> cache.remoteUp ? 1 : 0)
                    .tag("cache", "diagrams").description("1 khi đang dùng được Redis (L2)").register(registry);
        }
    }

    private static Counter l2Counter(MeterRegistry registry, String result) {
        return Counter.builder("diagramio.cache.l2").tag("cache", "diagrams").tag("result", result)
                .description("Số lần tra Redis (L2) cho sơ đồ").register(registry);
    }

    public Mode mode() {
        return mode;
    }

    /** Tầng Redis (null nếu chế độ không có Redis), cho bộ theo dõi kết nối. */
    public RedisDiagramTier remoteTier() {
        return remote;
    }

    /** Định danh của instance này trong các thông báo pub/sub (để bỏ qua thông báo của chính mình). */
    public String instanceId() {
        return instanceId;
    }

    public boolean enabled() {
        return mode != Mode.NONE;
    }

    /** Có đang dùng được Redis không (luôn false nếu chế độ không có Redis). */
    public boolean remoteUp() {
        return remoteUp;
    }

    // ------------------------------------------------------------------ đọc

    /** Cả sơ đồ: L1, rồi L2, rồi {@code loader} (CSDL); bản nạp được ghi vào các tầng đang dùng. */
    public Optional<Diagram> find(UUID id, Supplier<Optional<Diagram>> loader) {
        if (mode == Mode.NONE) {
            return loader.get();
        }
        if (localUsable()) {
            CachedDiagram hit = local.get(id);
            if (hit != null) {
                return hit.toDiagram();
            }
        }
        if (remoteUsable()) {
            Optional<CachedDiagram> hit = remoteGet(id);
            if (hit.isPresent()) {
                if (localUsable()) {
                    local.putIfNewer(hit.get());
                }
                return hit.get().toDiagram();
            }
        }
        return load(id, loader).flatMap(CachedDiagram::toDiagram);
    }

    /** Chỉ thông tin kiểm tra quyền: L1, rồi phần đầu trên L2 (không kéo data), rồi {@code loader}. */
    public Optional<DiagramAccessInfo> findAccessInfo(UUID id, Supplier<Optional<DiagramAccessInfo>> loader) {
        if (mode == Mode.NONE) {
            return loader.get();
        }
        if (localUsable()) {
            CachedDiagram hit = local.get(id);
            if (hit != null) {
                return hit.accessInfo();
            }
        }
        if (remoteUsable()) {
            try {
                Optional<Optional<DiagramAccessInfo>> hit = remote.getAccessInfo(id);
                if (hit.isPresent()) {
                    remoteHit.increment();
                    return hit.get();
                }
                remoteMiss.increment();
            } catch (RuntimeException e) {
                remoteFailed(e);
            }
        }
        return loader.get();
    }

    private Optional<CachedDiagram> remoteGet(UUID id) {
        try {
            Optional<CachedDiagram> hit = remote.get(id);
            (hit.isPresent() ? remoteHit : remoteMiss).increment();
            return hit;
        } catch (RuntimeException e) {
            remoteFailed(e);
            return Optional.empty();
        }
    }

    /** Nạp từ CSDL; các request cùng trượt một sơ đồ chờ chung một lần nạp. */
    private Optional<CachedDiagram> load(UUID id, Supplier<Optional<Diagram>> loader) {
        CompletableFuture<Optional<CachedDiagram>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<CachedDiagram>> running = loading.putIfAbsent(id, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException cause ? cause : e;
            }
        }
        try {
            Optional<CachedDiagram> loaded = loader.get().map(CachedDiagram::of);
            loaded.ifPresent(this::fill);
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(id, mine);
        }
    }

    /** Ghi bản vừa đọc từ CSDL vào các tầng (không phải thay đổi nên không thông báo cho instance khác). */
    private void fill(CachedDiagram entry) {
        if (entry.dataLength() > maxDataChars) {
            return;
        }
        if (localUsable()) {
            local.putIfNewer(entry);
        }
        if (remoteUsable()) {
            try {
                remote.putIfNewer(entry);
            } catch (RuntimeException e) {
                remoteFailed(e);
            }
        }
    }

    // ------------------------------------------------------------------ ghi (gọi sau commit)

    /** Sơ đồ vừa được thêm, sửa hoặc khôi phục: ghi trạng thái mới vào các tầng và báo cho instance khác. */
    public void written(Diagram diagram) {
        if (mode == Mode.NONE) {
            return;
        }
        CachedDiagram entry = CachedDiagram.of(diagram);
        boolean cacheable = entry.dataLength() <= maxDataChars;
        if (local != null) {
            if (cacheable) {
                local.putIfNewer(entry);
            } else {
                local.evictIfOlder(entry.id(), entry.version());
            }
        }
        if (remote != null) {
            writeRemote(entry.id(), () -> {
                if (cacheable) {
                    remote.putIfNewer(entry);
                } else {
                    remote.delete(entry.id());
                }
                remote.publishChange(entry.id(), entry.version(), instanceId);
            });
        }
    }

    /** Sơ đồ vừa bị xóa: ghi bia mộ vào các tầng và báo cho instance khác. */
    public void deleted(UUID id) {
        if (mode == Mode.NONE) {
            return;
        }
        CachedDiagram tombstone = CachedDiagram.tombstone(id);
        if (local != null) {
            local.putIfNewer(tombstone);
        }
        if (remote != null) {
            writeRemote(id, () -> {
                remote.putIfNewer(tombstone);
                remote.publishChange(id, CachedDiagram.TOMBSTONE_VERSION, instanceId);
            });
        }
    }

    /**
     * Câu UPDATE có điều kiện không khớp (version đã đổi): bản trong cache, nếu có, có thể đã cũ. Bỏ đi để lần đọc
     * tiếp theo (kể cả version hiện tại để trả trong 409) lấy từ CSDL.
     */
    public void conflict(UUID id) {
        if (local != null) {
            local.evict(id);
        }
        if (remote != null && remoteUp) {
            try {
                remote.delete(id);
            } catch (RuntimeException e) {
                remoteFailed(e);
            }
        }
    }

    private void writeRemote(UUID id, Runnable write) {
        if (!remoteUp) {
            missedRemoteWrites.add(id);
            return;
        }
        try {
            write.run();
        } catch (RuntimeException e) {
            missedRemoteWrites.add(id);
            remoteFailed(e);
        }
    }

    // ------------------------------------------------------------------ nhiều instance, Redis lỗi

    /** Thông báo thay đổi từ instance khác (pub/sub): bỏ bản L1 cũ hơn. */
    public void onRemoteChange(RedisDiagramTier.Change change) {
        if (change == null || change.origin().equals(instanceId) || local == null) {
            return;
        }
        local.evictIfOlder(change.id(), change.version());
    }

    /** Bộ theo dõi báo Redis có dùng được không (ping được và, nếu có L1, đang nhận pub/sub). */
    public synchronized void remoteAvailable(boolean available) {
        if (remote == null) {
            return;
        }
        if (!available) {
            markRemoteDown("không ping được Redis hoặc mất đăng ký pub/sub");
            return;
        }
        if (remoteUp) {
            return;
        }
        // Đang hồi phục: xóa trên Redis các bản đã cũ trước, rồi mới dùng lại L2 và L1
        if (!drainMissedWrites()) {
            return;
        }
        if (local != null) {
            local.clear();
        }
        remoteUp = true;
        drainMissedWrites();
        log.info("Redis (L2) dùng lại được, cache sơ đồ hoạt động lại ở chế độ {}", mode);
    }

    private void remoteFailed(RuntimeException e) {
        remoteError.increment();
        markRemoteDown(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private synchronized void markRemoteDown(String reason) {
        if (!remoteUp) {
            return;
        }
        remoteUp = false;
        // L1 có thể đã lỡ thông báo hủy từ instance khác: không dùng nữa cho tới khi Redis trở lại
        if (local != null) {
            local.clear();
        }
        log.warn("Ngừng dùng Redis (L2) cho cache sơ đồ, đọc thẳng CSDL: {}", reason);
    }

    /** Xóa trên Redis các sơ đồ đã ghi CSDL trong lúc mất Redis và báo instance khác bỏ bản L1; false nếu lỗi. */
    private boolean drainMissedWrites() {
        for (UUID id : List.copyOf(missedRemoteWrites)) {
            try {
                remote.delete(id);
                remote.publishChange(id, CachedDiagram.TOMBSTONE_VERSION, instanceId);
                missedRemoteWrites.remove(id);
            } catch (RuntimeException e) {
                remoteError.increment();
                return false;
            }
        }
        return true;
    }

    private boolean localUsable() {
        return local != null && (remote == null || remoteUp);
    }

    private boolean remoteUsable() {
        return remote != null && remoteUp;
    }
}
