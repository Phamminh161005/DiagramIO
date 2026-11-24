package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Decorator của {@link DiagramRepository}: đọc qua {@link DiagramCache}, ghi CSDL rồi cập nhật cache sau commit.
 * Domain không biết có cache; cấu hình đăng ký decorator này làm bean chính.
 *
 * <ul>
 *   <li>{@code findById}, {@code findAccessInfo}: đọc cache trước. Trong một transaction đang chạy thì đọc thẳng
 *       CSDL, để thấy cả những gì chính transaction đó vừa ghi.</li>
 *   <li>{@code lockAccessInfo}: luôn CSDL (phải khóa dòng).</li>
 *   <li>{@code insert}, {@code updateIfVersion}, {@code restoreContent}: write-through trạng thái CSDL trả về
 *       ({@code RETURNING}); {@code deleteById}: bia mộ. Câu UPDATE không khớp version thì bỏ bản trong cache.</li>
 *   <li>{@code findPublic}: micro-cache theo trang nếu bật {@code public-list-ttl} (mặc định tắt). Khi bật, danh
 *       sách công khai có thể trễ tới chừng ấy (sơ đồ vừa chuyển sang riêng tư vẫn còn trong danh sách); nội dung
 *       sơ đồ vẫn được kiểm tra quyền trên trạng thái mới nhất khi mở.</li>
 *   <li>{@code findByOwner}: không cache (mỗi người một danh sách, đổi sau mỗi lần sửa).</li>
 * </ul>
 */
public final class CachingDiagramRepository implements DiagramRepository {

    private final DiagramRepository delegate;
    private final DiagramCache cache;
    private final Cache<Long, DiagramPage> publicPages;

    public CachingDiagramRepository(DiagramRepository delegate, DiagramCache cache, Duration publicListTtl,
                                    MeterRegistry registry) {
        this.delegate = delegate;
        this.cache = cache;
        if (cache.enabled() && !publicListTtl.isZero()) {
            this.publicPages = Caffeine.newBuilder().expireAfterWrite(publicListTtl).maximumSize(10_000)
                    .recordStats().build();
            CaffeineCacheMetrics.monitor(registry, publicPages, "public-diagrams");
        } else {
            this.publicPages = null;
        }
    }

    @Override
    public Diagram insert(Diagram diagram) {
        Diagram inserted = delegate.insert(diagram);
        writeThroughAfterCommit(inserted);
        return inserted;
    }

    @Override
    public Optional<Diagram> findById(UUID id) {
        if (AfterCommit.inTransaction()) {
            return delegate.findById(id);
        }
        return cache.find(id, () -> delegate.findById(id));
    }

    @Override
    public Optional<DiagramAccessInfo> findAccessInfo(UUID id) {
        if (AfterCommit.inTransaction()) {
            return delegate.findAccessInfo(id);
        }
        return cache.findAccessInfo(id, () -> delegate.findAccessInfo(id));
    }

    @Override
    public Optional<DiagramAccessInfo> lockAccessInfo(UUID id) {
        return delegate.lockAccessInfo(id);
    }

    @Override
    public Optional<Diagram> updateIfVersion(Diagram diagram) {
        Optional<Diagram> updated = delegate.updateIfVersion(diagram);
        if (updated.isPresent()) {
            writeThroughAfterCommit(updated.get());
        } else {
            cache.conflict(diagram.getId());
        }
        return updated;
    }

    @Override
    public Optional<Diagram> restoreContent(UUID diagramId, UUID versionId, UUID changedBy, Instant changedAt) {
        Optional<Diagram> restored = delegate.restoreContent(diagramId, versionId, changedBy, changedAt);
        restored.ifPresent(this::writeThroughAfterCommit);
        return restored;
    }

    @Override
    public DiagramPage findByOwner(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        return delegate.findByOwner(ownerId, diagramType, title, page, size);
    }

    @Override
    public DiagramPage findPublic(int page, int size) {
        if (publicPages == null || AfterCommit.inTransaction()) {
            return delegate.findPublic(page, size);
        }
        return publicPages.get(((long) page << 32) | size, key -> delegate.findPublic(page, size));
    }

    @Override
    public void deleteById(UUID id) {
        delegate.deleteById(id);
        AfterCommit.run(() -> cache.deleted(id));
    }

    /**
     * Chụp trạng thái CSDL vừa trả về ngay bây giờ (bất biến), ghi vào cache sau commit: service có thể sửa tiếp
     * đối tượng {@code Diagram} đã trả cho nó trước khi transaction kết thúc.
     */
    private void writeThroughAfterCommit(Diagram stored) {
        CachedDiagram entry = CachedDiagram.of(stored);
        AfterCommit.run(() -> entry.toDiagram().ifPresent(cache::written));
    }
}
