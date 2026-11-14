package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.auth.service.UserSessionStateService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * REST API 的访问规则与认证机制配置。
 * 它位于所有 Controller 之前，依赖官方 Resource Server 完成 Bearer JWT 验证，并将失败交给统一 401/403 处理器。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * JWT 本身携带身份且每次请求都重新验证，因此不创建服务端 Session。
     * Access Token 使用 Bearer Header，不依赖 Cookie；Refresh Token Cookie 端点则由 HttpOnly、SameSite、生产 Secure、
     * 精确 Origin/Referer 校验和 CORS allowlist 共同保护。CORS 本身不是 CSRF 防护，因此关闭默认 CSRF 后仍必须保留来源校验。
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource,
            AuthRequestOriginValidator originValidator,
            UserSessionStateService sessionStates,
            ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .addFilterBefore(
                        new RequestOriginSecurityFilter(originValidator, accessDeniedHandler),
                        CorsFilter.class)
                .addFilterAfter(
                        new AuthSessionGuardFilter(sessionStates, objectMapper),
                        BearerTokenAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/invitations/{code}/accept-current-user").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                                "/api/v1/auth/register",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                                "/api/v1/auth/verify-email",
                                "/api/v1/connector/events/{connectionId}",
                                "/api/v1/invitations/{code}/accept").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/invitations/{code}",
                                "/api/v1/auth/registration-policy",
                                "/api/v1/connector/oauth/callback",
                                "/api/v1/connector/oauth/complete",
                                "/internal/storage-authorize").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // W3 内部模型网关（Python worker → Java）：JWT 无法覆盖机器调用，
                        // 这里只放开 Security 层，鉴权由 InternalAiTokenFilter 按
                        // rerank.service-token 常量时间比较完成（未配置即 fail closed）。
                        // 端点仅在 ai 内部网络可达，nginx 不代理 /internal/**。
                        .requestMatchers("/internal/ai/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * 独立管理端口（{@code management.server.port}）上的 Actuator 放行链。
     *
     * <p>该端口按部署契约从不对外（compose 不映射宿主端口、nginx 不代理），抓取方是无法持有用户
     * JWT 的机器（Prometheus / 探针）。因此仅在这个端口上匿名放行可观测性端点；主端口
     * （{@code server.port}）的规则完全不变——见 {@link ManagementPortRequestMatcher}
     * 对"未配置或与主端口相同"的排除。</p>
     *
     * <p>放行范围刻意收窄到 health / info / prometheus / metrics，其余 Actuator 端点即使被
     * {@code management.endpoints.web.exposure} 打开也仍要求认证（纵深防御）。</p>
     */
    @Bean
    @Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain managementPortSecurityFilterChain(
            HttpSecurity http,
            @org.springframework.beans.factory.annotation.Value("${management.server.port:-1}") int managementPort,
            @org.springframework.beans.factory.annotation.Value("${server.port:8080}") int serverPort) throws Exception {
        http
                .securityMatcher(new ManagementPortRequestMatcher(managementPort, serverPort))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(corsProperties.allowedOrigins()));
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cache-Control"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 过滤器先于 CORS 与 MVC 请求体解析执行：三个 Cookie 端点强制来源，其余请求仅校验已经携带的 Origin。
     * 这样多值或不可信来源都复用统一 403 出口，且不会由 Spring 默认 CORS 拒绝体暴露不同响应契约。
     */
    private static final class RequestOriginSecurityFilter extends OncePerRequestFilter {

        private static final List<RequestMatcher> COOKIE_ENDPOINTS = List.of(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/login"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/refresh"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/logout"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/register"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/invitations/{code}/accept"));

        private final AuthRequestOriginValidator originValidator;
        private final RestAccessDeniedHandler accessDeniedHandler;

        private RequestOriginSecurityFilter(
                AuthRequestOriginValidator originValidator,
                RestAccessDeniedHandler accessDeniedHandler) {
            this.originValidator = originValidator;
            this.accessDeniedHandler = accessDeniedHandler;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            try {
                if (requiresMandatorySource(request)) {
                    originValidator.validate(request);
                } else {
                    originValidator.validateCorsOriginIfPresent(request);
                }
            } catch (BusinessException exception) {
                if (exception.getErrorCode() != ErrorCode.AUTH_FORBIDDEN) {
                    throw exception;
                }
                accessDeniedHandler.handle(
                        request,
                        response,
                        new AccessDeniedException("请求来源校验失败", exception));
                return;
            }
            filterChain.doFilter(request, response);
        }

        private boolean requiresMandatorySource(HttpServletRequest request) {
            return COOKIE_ENDPOINTS.stream().anyMatch(matcher -> matcher.matches(request));
        }
    }
}
