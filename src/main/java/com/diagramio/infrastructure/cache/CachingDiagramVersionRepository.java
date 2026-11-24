package com.diagramio.infrastructure.cache;

import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.domain.version.DiagramVersionRepository;
import com.diagramio.domain.version.DiagramVersionSummary;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decorator của {@link DiagramVersionRepository}: phiên bản là snapshot bất biến nên được cache trong JVM, không
 * hết hạn theo thời gian, chỉ bị đẩy ra khi cache đầy (giới hạn theo dung lượng). Không cần hủy khi xóa sơ đồ:
 * service luôn kiểm tra sơ đồ còn tồn tại và người gọi có quyền ({@code DiagramAccess}) trước khi đọc phiên bản,
 * nên bản cache của một sơ đồ đã xóa không bao giờ được trả ra.
 */
public final class CachingDiagramVersionRepository implements DiagramVersionRepository {

    private final DiagramVersionRepository delegate;
    private final Cache<UUID, DiagramVersion> versions;

    /** {@code maxBytes} bằng 0: không cache (chế độ {@code none}), mọi lời gọi đi thẳng xuống CSDL. */
    public CachingDiagramVersionRepository(DiagramVersionRepository delegate, long maxBytes, MeterRegistry registry) {
        this.delegate = delegate;
        if (maxBytes <= 0) {
            this.versions = null;
            return;
        }
        this.versions = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((UUID id, DiagramVersion v) -> 256 + v.data().json().length())
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, versions, "versions");
    }

    @Override
    public Optional<DiagramVersion> findByIdAndDiagramId(UUID id, UUID diagramId) {
        if (versions == null || AfterCommit.inTransaction()) {
            // Trong transaction: phiên bản chưa commit có thể bị rollback, không đưa vào cache
            return delegate.findByIdAndDiagramId(id, diagramId);
        }
        DiagramVersion cached = versions.getIfPresent(id);
        if (cached != null) {
            return cached.diagramId().equals(diagramId) ? Optional.of(cached) : Optional.empty();
        }
        Optional<DiagramVersion> loaded = delegate.findByIdAndDiagramId(id, diagramId);
        loaded.ifPresent(v -> versions.put(v.id(), v));
        return loaded;
    }

    @Override
    public DiagramVersion snapshot(UUID versionId, UUID diagramId, String note, UUID createdBy, Instant createdAt) {
        return delegate.snapshot(versionId, diagramId, note, createdBy, createdAt);
    }

    @Override
    public DiagramVersionSummary snapshotSummary(UUID versionId, UUID diagramId, String note, UUID createdBy,
                                                 Instant createdAt) {
        return delegate.snapshotSummary(versionId, diagramId, note, createdBy, createdAt);
    }

    @Override
    public List<DiagramVersionSummary> findSummaries(UUID diagramId) {
        return delegate.findSummaries(diagramId);
    }

    @Override
    public Optional<DiagramVersionSummary> findSummary(UUID id, UUID diagramId) {
        return delegate.findSummary(id, diagramId);
    }
}
