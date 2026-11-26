package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内部 AI 端点鉴权（冻结契约 §2/§7.8）。
 *
 * <p>只匹配 {@code /internal/ai/} 前缀，其余请求完全不干预。</p>
 *
 * <p><b>必须早于 Spring Security 的 Resource Server 执行</b>：worker 用
 * {@code Authorization: Bearer <服务令牌>} 调用内部端点，而 OAuth2 Resource Server 会对任何
 * Bearer 头先做 JWT 解析，失败即 401，{@code permitAll} 规则根本轮不到。因此本过滤器排在
 * 最高优先级：先校验令牌，校验通过后用一个"隐藏 Authorization 头"的请求包装继续向下，
 * 让 Security 看到的是匿名请求，再由 {@code /internal/ai/**} 的 permitAll 放行。</p>
 *
 * <p><b>令牌</b>：只接受 Java 签发的 Ed25519 短令牌（三段 JWS，aud=backend，按路径要求 scope）。</p>
 *
 * <p><b>fail closed</b>：鉴权材料未配置时拒绝<b>所有</b>内部 AI 请求（503 MODEL_UNAVAILABLE），
 * 绝不放行。失败分支对调用方不区分"缺失/格式错/过期/签名错"（统一 401 + 同一文案），日志只记
 * {@code reason} 与路径，<b>绝不打印令牌</b>。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAiTokenFilter.class);

    static final String INTERNAL_AI_PATH_PREFIX = "/internal/ai/";
    static final String REQUIRED_AUDIENCE = "backend";
    /**
     * 验签通过后写入 request attribute 的 claims（只读；控制器据此校验 job 路径绑定）。
     */
    public static final String VERIFIED_CLAIMS_ATTRIBUTE =
            "com.shitulelv.aicollab.internal.ai.verifiedClaims";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MCP_GATEWAY_PATH = "/internal/ai/v1/mcp";
    private static final Set<String> MCP_SCOPES = Set.of(
            InternalTokenIssuer.SCOPE_MCP_LIST, InternalTokenIssuer.SCOPE_MCP_CALL);

    /**
     * 路径 → 必需 scope（可扩展映射）。未列出的 {@code /internal/ai/**} 只要求
     * {@code aud=backend}；新增能力时在这里加一行即可，不要放宽成"任意 scope"。
     */
    private static final Map<String, String> PATH_SCOPES = Map.of(
            "/internal/ai/v1/model-calls", "model-call");

    /**
     * Job 端点按路径尾部映射 scope（W5 冻结 §3.3）：{@code job:snapshot} /
     * {@code job:heartbeat} / {@code job:results}。jobId 段只做形状匹配，
     * 真正的 lease 绑定由控制器校验 claims。
     */
    private static final Pattern JOB_SCOPE_PATH =
            Pattern.compile("^/internal/ai/v1/jobs/[^/]+/(snapshot|heartbeat|results)$");

    private final InternalTokenVerifier tokenVerifier;
    private final ObjectMapper objectMapper;

    @Autowired
    public InternalAiTokenFilter(InternalTokenVerifier tokenVerifier, ObjectMapper objectMapper) {
        this.tokenVerifier = tokenVerifier;
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
        String path = request.getRequestURI();
        String providedToken = bearerToken(request.getHeader("Authorization"));
        AuthOutcome outcome = authenticate(providedToken, path);
        if (outcome.authorized()) {
            // 令牌已校验：对下游隐藏 Authorization，避免 OAuth2 Resource Server 把它当 JWT 解析后 401。
            // 验签 claims 通过 request attribute 传给控制器。
            filterChain.doFilter(new AuthorizationHiddenRequest(request, outcome.claims()), response);
            return;
        }
        if (outcome.unavailable()) {
            log.error("内部 AI 端点拒绝请求: 鉴权材料未配置（fail closed）");
            writeError(response, InternalAiErrorCode.MODEL_UNAVAILABLE, "内部模型网关未配置服务令牌");
            return;
        }
        log.warn("内部 AI 端点鉴权失败: path={} reason={}", path, outcome.reason());
        response.setHeader("WWW-Authenticate", "Bearer");
        // HTTP 401（而不是 422）：让运维一眼区分"鉴权失败"与"参数错误"；
        // 错误码仍沿用内部端点九码集合中的 INVALID_REQUEST，保持信封不变。
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                InternalAiErrorCode.INVALID_REQUEST, "内部服务令牌无效");
    }

    /** 只接受 Ed25519 短令牌；验签失败原因只进日志。 */
    private AuthOutcome authenticate(String providedToken, String path) {
        if (tokenVerifier == null || tokenVerifier.knownKids().isEmpty()) {
            return AuthOutcome.configMissing();
        }
        if (providedToken == null) {
            return AuthOutcome.rejected("missing_token");
        }
        InternalTokenVerifier.Result result = verifyForPath(providedToken, path);
        return result.valid()
                ? AuthOutcome.granted(result.claims())
                : AuthOutcome.rejected(result.reasonName());
    }

    /** 路径 → 必需 scope；未登记路径返回空集（只校验 aud）。 */
    static Set<String> requiredScopes(String path) {
        if (path == null) {
            return Set.of();
        }
        String scope = PATH_SCOPES.get(path);
        if (scope != null) {
            return Set.of(scope);
        }
        Matcher jobPath = JOB_SCOPE_PATH.matcher(path);
        if (jobPath.matches()) {
            return Set.of("job:" + jobPath.group(1));
        }
        return Set.of();
    }

    private InternalTokenVerifier.Result verifyForPath(String token, String path) {
        if (MCP_GATEWAY_PATH.equals(path)) {
            return tokenVerifier.verifyAny(token, REQUIRED_AUDIENCE, MCP_SCOPES);
        }
        return tokenVerifier.verify(token, REQUIRED_AUDIENCE, requiredScopes(path));
    }

    /** 只对内部路径隐藏 {@code Authorization}；其余头与方法语义完全透传。 */
    private static final class AuthorizationHiddenRequest extends HttpServletRequestWrapper {

        private final Map<String, Object> claims;

        private AuthorizationHiddenRequest(HttpServletRequest request, Map<String, Object> claims) {
            super(request);
            this.claims = claims == null ? Map.of() : Map.copyOf(claims);
        }

        @Override
        public Object getAttribute(String name) {
            if (VERIFIED_CLAIMS_ATTRIBUTE.equals(name)) {
                return claims;
            }
            return super.getAttribute(name);
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

    /** 鉴权结果：授权（带可空 claims）/ 配置缺失（503）/ 拒绝（401，带日志 reason）。 */
    private record AuthOutcome(boolean authorized, boolean unavailable, String reason,
            Map<String, Object> claims) {

        static AuthOutcome granted(Map<String, Object> claims) {
            return new AuthOutcome(true, false, null, claims == null ? Map.of() : claims);
        }

        static AuthOutcome configMissing() {
            return new AuthOutcome(false, true, null, Map.of());
        }

        static AuthOutcome rejected(String reason) {
            return new AuthOutcome(false, false, reason, Map.of());
        }
    }
}
