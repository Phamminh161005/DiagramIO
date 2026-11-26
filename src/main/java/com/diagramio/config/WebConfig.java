package com.diagramio.config;

import com.diagramio.api.CallerArgumentResolver;
import com.diagramio.api.RequestSizeLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cấu hình tầng web: CallerArgumentResolver (controller nhận Caller hoặc @CurrentUser UUID làm tham số) và giới hạn
 * kích thước body request.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Chặn body quá lớn trước mọi thứ khác, kể cả Spring Security và Jackson (xem RequestSizeLimitFilter). Mặc định
     * 4 MB: gấp đôi giới hạn 2 MB của data, chừa chỗ cho các trường khác và JSON có khoảng trắng.
     */
    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            @Value("${diagramio.http.max-request-size:4MB}") DataSize maxRequestSize, ObjectMapper objectMapper) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>(
                new RequestSizeLimitFilter(maxRequestSize.toBytes(), objectMapper));
        // Sau bộ lọc mã hóa ký tự và bộ đo thời gian request của Spring Boot, trước Spring Security (-100)
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }
}
