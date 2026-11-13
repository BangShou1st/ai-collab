package com.shitulelv.aicollab.p2b1probe;

import com.shitulelv.aicollab.AiCollabBackendApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * M-12 测试专用启动入口。
 *
 * <p>该类只在 test classpath 存在，不进入生产 jar。它启动与生产相同的主应用上下文，
 * 额外注册测试探针配置，用于验证 Gateway 关闭时的旧 Provider 回退路径。</p>
 */
public final class P2B1GatewayOffMain {

    private P2B1GatewayOffMain() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(
                AiCollabBackendApplication.class)
                .web(WebApplicationType.SERVLET)
                .bannerMode(Banner.Mode.OFF)
                .run();
    }
}
