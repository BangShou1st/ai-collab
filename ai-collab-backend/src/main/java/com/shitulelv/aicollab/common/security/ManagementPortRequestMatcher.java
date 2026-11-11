package com.shitulelv.aicollab.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 只匹配「独立管理端口」上的请求。
 *
 * <p>背景：Actuator 跑在 {@code management.server.port}（默认 8081）时，它的子上下文沿用主应用的
 * {@link SecurityConfig} 过滤链，于是 `/actuator/prometheus` 也要求 JWT —— 而 Prometheus、k8s 探针
 * 这类机器抓取方拿不到用户令牌。按部署契约，该端口从不对外（compose 不映射、nginx 不代理），
 * 所以只在这个端口上放行可观测性端点，主端口行为完全不变。</p>
 *
 * <p>两个必须保持的边界：</p>
 * <ul>
 *   <li>{@code management.server.port} 未配置（-1）或与 {@code server.port} 相同：<b>永不匹配</b>，
 *       即不接管、维持原有的"actuator 需要 JWT"行为；</li>
 *   <li>用 {@code request.getLocalPort()} 判定，而不是 Host/Header 等可伪造输入。</li>
 * </ul>
 */
final class ManagementPortRequestMatcher implements RequestMatcher {

    private final int managementPort;
    private final int serverPort;

    ManagementPortRequestMatcher(int managementPort, int serverPort) {
        this.managementPort = managementPort;
        this.serverPort = serverPort;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (managementPort <= 0 || managementPort == serverPort) return false;
        return request.getLocalPort() == managementPort;
    }

    @Override
    public String toString() {
        return "ManagementPortRequestMatcher[management=" + managementPort + ",server=" + serverPort + "]";
    }
}
