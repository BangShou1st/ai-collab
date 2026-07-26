package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

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
            AuthRequestOriginValidator originValidator) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .addFilterBefore(
                        new RequestOriginSecurityFilter(originValidator, accessDeniedHandler),
                        CorsFilter.class)
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
                                "/api/v1/invitations/{code}/accept").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/invitations/{code}",
                                "/api/v1/auth/registration-policy").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
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
