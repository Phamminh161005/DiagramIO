package com.diagramio.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CacheConfigTest {

    private static final long MB = 1024 * 1024;

    @Test
    void configuredSizeIsKeptWhenTheHeapIsLargeEnough() {
        // Bộ đo: heap 2 GB, cấu hình mặc định 256 MB và 128 MB giữ nguyên
        assertThat(CacheConfig.boundedByHeap(256 * MB, CacheConfig.DIAGRAM_HEAP_SHARE, 2048 * MB)).isEqualTo(256 * MB);
        assertThat(CacheConfig.boundedByHeap(128 * MB, CacheConfig.VERSION_HEAP_SHARE, 2048 * MB)).isEqualTo(128 * MB);
    }

    @Test
    void smallHeapsShrinkTheCachesInsteadOfRunningOutOfMemory() {
        // Container 1 GB, JVM mặc định dùng 25% RAM: heap 256 MB. Cấu hình cố định 256 + 128 MB sẽ vượt cả heap
        assertThat(CacheConfig.boundedByHeap(256 * MB, CacheConfig.DIAGRAM_HEAP_SHARE, 256 * MB)).isEqualTo(64 * MB);
        assertThat(CacheConfig.boundedByHeap(128 * MB, CacheConfig.VERSION_HEAP_SHARE, 256 * MB))
                .isEqualTo((long) (256 * MB * 0.10));
    }
}
