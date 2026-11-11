package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 内部 AI 端点鉴权（冻结契约 §2/§7.8）。
 *
 * <p>只匹配 {@code /internal/ai/} 前缀，其余请求完全不干预。</p>
 *
 * <p><b>必须早于 Spring Security 的 Resource Server 执行</b>：worker 用
 * {@code Authorization: Bearer <服务令牌>} 调用内部端点，而 OAuth2 Resource Server 会对任何
 * Bearer 头先做 JWT 解析，失败即 401，{@code permitAll} 规则根本轮不到。因此本过滤器排在
 * 最高优先级：先校验服务令牌，校验通过后用一个"隐藏 Authorization 头"的请求包装继续向下，
 * 让 Security 看到的是匿名请求，再由 {@code /internal/ai/**} 的 permitAll 放行。</p>
 *
 * <p><b>fail closed</b>：{@code rerank.service-token} 未配置时拒绝<b>所有</b>内部 AI 请求
 * （503 MODEL_UNAVAILABLE），绝不放行；令牌比较用 {@link MessageDigest#isEqual} 常量时间
 * 比较，错误分支不区分"缺失/错误"给调用方，日志也不打印令牌。</p>
 *
 * <p>鉴权失败写扁平错误信封（与控制器同一契约），不使用 {@code ApiResponse}。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAiTokenFilter.class);

    static final String INTERNAL_AI_PATH_PREFIX = "/internal/ai/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RerankProperties rerankProperties;
    private final ObjectMapper objectMapper;

    public InternalAiTokenFilter(RerankProperties rerankProperties, ObjectMapper objectMapper) {
        this.rerankProperties = rerankProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(INTERNAL_AI_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String configuredToken = rerankProperties.serviceToken();
        if (configuredToken == null || configuredToken.isEmpty()) {
            log.error("内部 AI 端点拒绝请求: rerank.service-token 未配置（fail closed）");
            writeError(response, InternalAiErrorCode.MODEL_UNAVAILABLE, "内部模型网关未配置服务令牌");
            return;
        }
        String providedToken = bearerToken(request.getHeader("Authorization"));
        if (providedToken == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8))) {
            log.warn("内部 AI 端点鉴权失败: path={} reason={}",
                    request.getRequestURI(), providedToken == null ? "missing_token" : "invalid_token");
            response.setHeader("WWW-Authenticate", "Bearer");
            // HTTP 401（而不是 422）：让运维一眼区分"鉴权失败"与"参数错误"；
            // 错误码仍沿用内部端点九码集合中的 INVALID_REQUEST，保持信封不变。
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    InternalAiErrorCode.INVALID_REQUEST, "内部服务令牌无效");
            return;
        }
        // 令牌已校验：对下游隐藏 Authorization，避免 OAuth2 Resource Server 把它当 JWT 解析后 401。
        filterChain.doFilter(new AuthorizationHiddenRequest(request), response);
    }

    /** 只对内部路径隐藏 {@code Authorization}；其余头与方法语义完全透传。 */
    private static final class AuthorizationHiddenRequest extends HttpServletRequestWrapper {

        private AuthorizationHiddenRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isAuthorization(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isAuthorization(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(AuthorizationHiddenRequest::isAuthorization);
            return Collections.enumeration(names);
        }

        private static boolean isAuthorization(String name) {
            return name != null && "Authorization".equalsIgnoreCase(name);
        }
    }

    private void writeError(HttpServletResponse response, InternalAiErrorCode code, String message)
            throws IOException {
        writeError(response, code.httpStatus(), code, message);
    }

    private void writeError(HttpServletResponse response, int status, InternalAiErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = objectMapper.writeValueAsBytes(InternalAiErrorResponse.of(code, message));
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
