package com.diagramio.config;

import com.diagramio.infrastructure.cache.DiagramCache;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Cấu hình cache (B2, ADR-0003), tiền tố {@code diagramio.cache}.
 *
 * @param mode            {@code none} (đi thẳng CSDL) | {@code local} (Caffeine) | {@code redis} (chỉ Redis) |
 *                        {@code two-level} (Caffeine + Redis, nhiều instance)
 * @param localMaxSize    dung lượng tối đa của L1 cho sơ đồ (không quá 25% heap)
 * @param localTtl        thời gian sống của một sơ đồ trong L1 kể từ lần ghi
 * @param redisTtl        thời gian sống của một sơ đồ trên Redis
 * @param tombstoneTtl    thời gian giữ bia mộ của sơ đồ vừa xóa
 * @param maxEntrySize    sơ đồ có data lớn hơn thì không cache
 * @param versionMaxSize  dung lượng tối đa của cache phiên bản (bất biến, không hết hạn; không quá 10% heap)
 * @param publicListTtl   micro-cache danh sách công khai; mặc định tắt (0) vì sơ đồ vừa chuyển sang riêng tư sẽ
 *                        còn trong danh sách tới hết thời gian này, và sau B1 câu danh sách đã rẻ (ADR-0003)
 * @param monitorInterval chu kỳ kiểm tra Redis và pub/sub
 * @param shareTokens     cache tra cứu token chia sẻ (B3, ADR-0004); tắt để đo riêng phần của B2
 * @param shareTokenTtl   thời gian sống của một token trong cache; cũng là thời gian sống của dấu "đang đổi"
 * @param shareTokenNegativeTtl thời gian nhớ một token không tồn tại (cache âm)
 * @param shareTokenMaxEntries  số token tối đa trong cache của chế độ local
 */
@ConfigurationProperties("diagramio.cache")
public record CacheProperties(
        @DefaultValue("local") DiagramCache.Mode mode,
        @DefaultValue("256MB") DataSize localMaxSize,
        @DefaultValue("10m") Duration localTtl,
        @DefaultValue("30m") Duration redisTtl,
        @DefaultValue("60s") Duration tombstoneTtl,
        @DefaultValue("2MB") DataSize maxEntrySize,
        @DefaultValue("128MB") DataSize versionMaxSize,
        @DefaultValue("0s") Duration publicListTtl,
        @DefaultValue("1s") Duration monitorInterval,
        @DefaultValue("true") boolean shareTokens,
        @DefaultValue("5m") Duration shareTokenTtl,
        @DefaultValue("30s") Duration shareTokenNegativeTtl,
        @DefaultValue("100000") long shareTokenMaxEntries) {
}
