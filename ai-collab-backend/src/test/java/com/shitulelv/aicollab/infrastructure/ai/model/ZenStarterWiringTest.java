package com.shitulelv.aicollab.infrastructure.ai.model;

import com.sharedlibs.opencodezen.OpenCodeZenClientProfile;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenAutoConfiguration;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用实际 JitPack starter，在本项目的 Spring Boot 版本中验证装配与身份配置一致。 */
class ZenStarterWiringTest {
    @Test void publishedStarterWiresBothProductionExecutionAndDiscovery() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenCodeZenAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(OutboundEndpointPolicy.class, OutboundEndpointPolicy::new)
                .withUserConfiguration(ProviderPresetRegistry.class, ZenModelExecution.class,
                        HttpOpenCodeZenTransport.class)
                // 保持测试封闭：不探测/拉起本地 Bun 中继，工厂直连（真实部署默认 auto 走中继）。
                .withPropertyValues("opencode.zen.relay-url=none")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OpenCodeZenRequestFactory.class)
                            .hasSingleBean(ZenModelExecution.class).hasSingleBean(HttpOpenCodeZenTransport.class);
                    var registry = context.getBean(ProviderPresetRegistry.class);
                    var factory = context.getBean(OpenCodeZenRequestFactory.class);
                    var endpoint = URI.create(context.getBean(ZenModelExecution.class).runtimeEndpoint());
                    HttpRequest request = factory.newConversationRequest(endpoint, true, null, UUID.randomUUID())
                            .POST(HttpRequest.BodyPublishers.noBody()).build();
                    assertThat(factory.userAgent()).isEqualTo(OpenCodeZenClientProfile.userAgent())
                            .isEqualTo(registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE).userAgent());
                    assertThat(request.headers().firstValue("Authorization")).contains("Bearer public");
                    assertThat(request.headers().firstValue("x-opencode-session-id"))
                            .isEqualTo(request.headers().firstValue("x-opencode-session"));
                    assertThat(request.headers().firstValue("x-opencode-request")).isEmpty();
                });
    }
}
