package com.diagramio.infrastructure.cache;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.ServiceUnavailableException;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.share.DiagramShareRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator của {@link DiagramShareRepository} (B3, ADR-0004): cache tra cứu {@code X-Share-Token} và
 * {@code GET /shares/{token}}, với yêu cầu thu hồi và đổi quyền có hiệu lực ngay từ request tiếp theo.
 *
 * <ul>
 *   <li>{@code findByShareToken}: tra cache (khóa SHA-256 của token); trượt thì đọc CSDL rồi nạp, kể cả kết quả "không
 *       có" (cache âm). Trong transaction, hoặc khi Redis không dùng được, thì đọc thẳng CSDL.</li>
 *   <li>{@code updateRole}, {@code deleteByIdAndDiagramId}: đặt dấu "đang đổi" TRƯỚC khi đổi CSDL, bỏ khóa sau commit.
 *       Không đặt được dấu (Redis lỗi) thì không đổi CSDL mà báo 503: thà chưa thu hồi được và nói rõ, còn hơn báo
 *       đã thu hồi trong khi instance khác vẫn cho token đi qua.</li>
 *   <li>{@code insert}: ghi link mới vào cache sau commit (đè cache âm nếu có).</li>
 *   <li>Xóa sơ đồ (link bị xóa theo nhờ khóa ngoại): {@link #beforeDiagramDelete} đọc các link của sơ đồ trước khi
 *       xóa và bỏ khóa của chúng sau commit.</li>
 * </ul>
 *
 * <p>{@code store} null nghĩa là tắt cache token (chế độ {@code none} hoặc {@code share-tokens=false}).
 */
public final class CachingShareRepository implements DiagramShareRepository {

    private static final Logger log = LoggerFactory.getLogger(CachingShareRepository.class);

    private final DiagramShareRepository delegate;
    private final ShareTokenStore store;
    private final BooleanSupplier storeUsable;
    private final Counter hit;
    private final Counter absent;
    private final Counter miss;
    private final Counter bypass;
    private final Counter error;

    public CachingShareRepository(DiagramShareRepository delegate, ShareTokenStore store, BooleanSupplier storeUsable,
                                  MeterRegistry registry) {
        this.delegate = delegate;
        this.store = store;
        this.storeUsable = storeUsable;
        this.hit = counter(registry, "hit");
        this.absent = counter(registry, "absent");
        this.miss = counter(registry, "miss");
        this.bypass = counter(registry, "bypass");
        this.error = counter(registry, "error");
    }

    private Counter counter(MeterRegistry registry, String result) {
        return store == null ? null : Counter.builder("diagramio.cache.share_tokens").tag("result", result)
                .description("Số lần tra cache token chia sẻ").register(registry);
    }

    public boolean enabled() {
        return store != null;
    }

    // ------------------------------------------------------------------ đọc

    @Override
    public Optional<DiagramShare> findByShareToken(String shareToken) {
        if (store == null || AfterCommit.inTransaction() || !storeUsable.getAsBoolean()) {
            return delegate.findByShareToken(shareToken);
        }
        String hash = ShareTokenStore.hash(shareToken);
        ShareTokenStore.Lookup cached;
        try {
            cached = store.lookup(hash);
        } catch (RuntimeException e) {
            error.increment();
            return delegate.findByShareToken(shareToken);
        }
        switch (cached.kind()) {
            case HIT -> {
                hit.increment();
                return Optional.of(cached.entry().toShare(shareToken));
            }
            case ABSENT -> {
                absent.increment();
                return Optional.empty();
            }
            case BYPASS -> {
                bypass.increment();
                return delegate.findByShareToken(shareToken);
            }
            default -> {
                miss.increment();
                Optional<DiagramShare> loaded = delegate.findByShareToken(shareToken);
                try {
                    store.fill(hash, loaded.map(ShareTokenStore.Entry::of).orElse(null));
                } catch (RuntimeException e) {
                    error.increment();
                }
                return loaded;
            }
        }
    }

    @Override
    public List<DiagramShare> findByDiagramId(UUID diagramId) {
        return delegate.findByDiagramId(diagramId);
    }

    // ------------------------------------------------------------------ ghi

    @Override
    public DiagramShare insert(DiagramShare share) {
        DiagramShare inserted = delegate.insert(share);
        if (store != null) {
            String hash = ShareTokenStore.hash(inserted.getShareToken());
            ShareTokenStore.Entry entry = ShareTokenStore.Entry.of(inserted);
            AfterCommit.run(() -> store.put(hash, entry));
        }
        return inserted;
    }

    @Override
    public Optional<DiagramShare> updateRole(UUID shareId, UUID diagramId, ShareRole role) {
        markChanging(shareId);
        Optional<DiagramShare> updated = delegate.updateRole(shareId, diagramId, role);
        updated.ifPresent(this::dropAfterCommit);
        return updated;
    }

    @Override
    public Optional<DiagramShare> deleteByIdAndDiagramId(UUID shareId, UUID diagramId) {
        markChanging(shareId);
        Optional<DiagramShare> deleted = delegate.deleteByIdAndDiagramId(shareId, diagramId);
        deleted.ifPresent(this::dropAfterCommit);
        return deleted;
    }

    /**
     * Gọi trước khi xóa một sơ đồ: đọc các link của nó (chúng bị xóa theo bằng khóa ngoại) và trả việc bỏ khóa của
     * chúng, để chạy sau commit. Token cũ của sơ đồ đã xóa có còn sót trong cache cũng không mở được gì (sơ đồ không
     * còn, mọi thao tác trả 404), nhưng bỏ đi để giữ cache sạch và giữ nguyên thông báo lỗi như khi không có cache.
     */
    public Runnable beforeDiagramDelete(UUID diagramId) {
        if (store == null) {
            return () -> { };
        }
        List<String> hashes = delegate.findByDiagramId(diagramId).stream()
                .map(share -> ShareTokenStore.hash(share.getShareToken())).toList();
        return () -> hashes.forEach(this::dropQuietly);
    }

    private void markChanging(UUID shareId) {
        if (store == null) {
            return;
        }
        try {
            store.markChanging(shareId);
        } catch (RuntimeException e) {
            error.increment();
            log.warn("Không đặt được dấu đổi quyền cho link {} trên cache, từ chối thao tác: {}", shareId, e.toString());
            throw new ServiceUnavailableException("Share links cannot be changed right now, please retry");
        }
    }

    private void dropAfterCommit(DiagramShare share) {
        if (store != null) {
            String hash = ShareTokenStore.hash(share.getShareToken());
            AfterCommit.run(() -> dropQuietly(hash));
        }
    }

    /** Bỏ khóa; lỗi chỉ ghi log vì dấu "đang đổi" đã bảo đảm không ai dùng bản cũ. */
    private void dropQuietly(String hash) {
        try {
            store.drop(hash);
        } catch (RuntimeException e) {
            error.increment();
            log.debug("Không bỏ được khóa token khỏi cache: {}", e.toString());
        }
    }
}
