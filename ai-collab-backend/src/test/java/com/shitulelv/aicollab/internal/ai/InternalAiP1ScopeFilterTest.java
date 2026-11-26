package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 契约 §1：{@code PATH_SCOPES} 登记与 {@code task:{taskId}} 的 tid 绑定（过滤器面，不依赖 Docker）。
 */
class InternalAiP1ScopeFilterTest {

    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TASK = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_TASK = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final KeyPair keyPair = newKeyPair();
    private final InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k1", 300, CLOCK);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EchoController())
            .addFilters(new InternalAiTokenFilter(
                    new InternalTokenVerifier(Map.of("k1", keyPair.getPublic()), 60, CLOCK), new ObjectMapper()))
            .build();

    @Test
    void registersLeaseUsageAndTaskScopes() {
        assertThat(InternalAiTokenFilter.requiredScopes("/internal/ai/credentials/lease"))
                .containsExactly("credentials:lease");
        assertThat(InternalAiTokenFilter.requiredScopes("/internal/ai/usage"))
                .containsExactly("usage:report");
        for (String op : List.of("claim", "snapshot", "heartbeat", "result", "fail")) {
            assertThat(InternalAiTokenFilter.requiredScopes("/internal/ai/tasks/" + TASK + "/" + op))
                    .isEqualTo(Set.of("task:" + TASK));
        }
    }

    @Test
    void taskTokenIsAcceptedOnlyForItsOwnTask() throws Exception {
        String token = issuer.issueTaskToken(TASK, 1L, USER, PROJECT, List.of("MEMORY_EXTRACTION"));

        mvc.perform(post("/internal/ai/tasks/" + TASK + "/claim").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 另一个任务路径：scope 不含 task:{other} → 401。
        mvc.perform(post("/internal/ai/tasks/" + OTHER_TASK + "/claim").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        // 非法 taskId 路径段 → 401（fail closed）。
        mvc.perform(post("/internal/ai/tasks/not-a-uuid/claim").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        // 同一令牌可以租借、回报用量。
        mvc.perform(post("/internal/ai/credentials/lease").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/ai/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void scopeMatchesButTidMismatchIsRejected() throws Exception {
        // scope 写对了 task:{TASK}，但 tid 指向别的任务：必须拒绝。
        String forged = issuer.issueAt(List.of("ai-worker", "backend"),
                List.of("task:" + TASK, "credentials:lease"), NOW, NOW + 300, "jti-forged",
                Map.of("tid", OTHER_TASK.toString(), "ep", 1));
        mvc.perform(post("/internal/ai/tasks/" + TASK + "/heartbeat").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());

        String missingTid = issuer.issueAt(List.of("backend"), List.of("task:" + TASK), NOW, NOW + 300,
                "jti-no-tid", Map.of());
        mvc.perform(post("/internal/ai/tasks/" + TASK + "/result").header("Authorization", "Bearer " + missingTid))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queryPlanTokenCanLeaseAndReportButNotTouchTasks() throws Exception {
        String token = issuer.issueQueryPlanToken(USER, PROJECT);
        mvc.perform(post("/internal/ai/credentials/lease").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/ai/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/ai/tasks/" + TASK + "/claim").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        String noLease = issuer.issueAt(List.of("backend"), List.of("plan"), NOW, NOW + 300, "jti-plan", Map.of());
        mvc.perform(post("/internal/ai/credentials/lease").header("Authorization", "Bearer " + noLease))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/ai/usage").header("Authorization", "Bearer " + noLease))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unregisteredInternalPathIsDeniedByDefault() throws Exception {
        assertThat(InternalAiTokenFilter.requiredScopes("/internal/ai/xxx")).isNull();
        assertThat(InternalAiTokenFilter.requiredScopes(null)).isNull();
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/xxx")).isFalse();
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/v1/mcp")).isTrue();
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/credentials/lease")).isTrue();
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/usage")).isTrue();
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/tasks/" + TASK + "/claim")).isTrue();

        String taskToken = issuer.issueTaskToken(TASK, 1L, USER, PROJECT, List.of("MEMORY_EXTRACTION"));
        String planToken = issuer.issueQueryPlanToken(USER, PROJECT);
        for (String token : List.of(taskToken, planToken)) {
            // 令牌有效、aud=backend，但路径未登记：默认拒绝（控制器存在也不放行）。
            mvc.perform(post("/internal/ai/xxx").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/ai/tasks/" + TASK + "/cancel").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        // 已登记端点行为不变。
        mvc.perform(post("/internal/ai/tasks/" + TASK + "/claim").header("Authorization", "Bearer " + taskToken))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/ai/credentials/lease").header("Authorization", "Bearer " + planToken))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/ai/usage").header("Authorization", "Bearer " + planToken))
                .andExpect(status().isOk());
    }

    @RestController
    static class EchoController {
        @PostMapping("/internal/ai/xxx")
        String unregistered() {
            return "ok";
        }

        @PostMapping("/internal/ai/tasks/{taskId}/{op}")
        String task(@PathVariable String taskId, @PathVariable String op) {
            return "ok";
        }

        @PostMapping("/internal/ai/credentials/lease")
        String lease() {
            return "ok";
        }

        @PostMapping("/internal/ai/usage")
        String usage() {
            return "ok";
        }
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
