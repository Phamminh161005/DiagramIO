package com.diagramio.config;

import com.diagramio.api.dto.ErrorResponse;
import com.diagramio.domain.share.ShareService;
import com.diagramio.infrastructure.security.JwtAuthenticationFilter;
import com.diagramio.infrastructure.security.JwtTokenProvider;
import com.diagramio.infrastructure.security.ShareTokenAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Bảo mật: stateless, endpoint công khai được mở, còn lại phải xác thực.
 * /api/v1/shares/** công khai để xem sơ đồ qua link chia sẻ; GET /api/v1/diagrams/* công khai cho sơ đồ public.
 * Người gọi được xác định bởi filter JWT rồi filter X-Share-Token (link chia sẻ); hai filter không tự
 * từ chối, request chưa xác thực vào endpoint cần đăng nhập sẽ nhận 401 dạng JSON.
 *
 * <p>Actuator (health, prometheus) chỉ được mở trên cổng quản trị riêng, cổng này chỉ nghe localhost
 * (application.yml). Khi cổng quản trị khác cổng API, {@link EndpointRequest} chỉ khớp request đến cổng
 * quản trị, nên cổng API không lộ thêm gì. Cho Actuator dùng chung cổng API là cấu hình sai, có hai lớp chặn:
 * Spring Boot từ chối khởi động vì management.server.address đã được đặt; nếu bỏ cả địa chỉ thì mọi endpoint
 * Actuator bị chặn ở đây, kể cả với người đã đăng nhập. ManagementEndpointsE2eTest kiểm tra cấu hình này.
 */
@Configuration
public class SecurityConfig {

    private static final String[] MANAGEMENT_ENDPOINTS = {"health", "prometheus"};

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/health",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh-token",
            "/api/v1/shares/**",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider jwtTokenProvider,
                                                   ShareService shareService,
                                                   ObjectMapper objectMapper,
                                                   Environment environment)
            throws Exception {
        boolean separateManagementPort = ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(jsonUnauthorizedEntryPoint(objectMapper)))
                .authorizeHttpRequests(auth -> {
                    if (separateManagementPort) {
                        auth.requestMatchers(EndpointRequest.to(MANAGEMENT_ENDPOINTS)).permitAll();
                    } else {
                        auth.requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll();
                    }
                    auth.requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/diagrams/*").permitAll()
                            .anyRequest().authenticated();
                })
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ShareTokenAuthenticationFilter(shareService), JwtAuthenticationFilter.class);
        return http.build();
    }

    /** Trả 401 cùng khung JSON với GlobalExceptionHandler. */
    private static AuthenticationEntryPoint jsonUnauthorizedEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            HttpStatus status = HttpStatus.UNAUTHORIZED;
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(Instant.now(), status.value(),
                    status.getReasonPhrase(), "Authentication required", request.getRequestURI()));
        };
    }
}
