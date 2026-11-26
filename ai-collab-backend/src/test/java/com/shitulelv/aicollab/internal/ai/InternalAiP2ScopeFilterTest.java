package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2 契约 §4.1、§4.3、§4.6：新增 scope 与过滤器路径登记（不依赖 Docker）。
 * 正文接口只认 {@code index:document:{docId}}；未登记路径仍默认拒绝。
 */
class InternalAiP2ScopeFilterTest {

    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TASK = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DOC = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OTHER_DOC = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final KeyPair keyPair = newKeyPair();
    private final InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k1", 300, CLOCK);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EchoController())
            .addFilters(new InternalAiTokenFilter(
                    new InternalTokenVerifier(Map.of("k1", keyPair.getPublic()), 60, CLOCK), new ObjectMapper()))
            .build();

    @Test
    void registersDocumentBodyPathWithPerDocumentScope() {
        assertThat(InternalAiTokenFilter.requiredScopes("/internal/ai/documents/" + DOC + "/body"))
                .isEqualTo(Set.of("index:document:" + DOC));
        assertThat(InternalAiTokenFilter.requiredScopes("/internal/ai/documents/not-a-uuid/body"))
                .isEqualTo(Set.of("index:document:<invalid>"));
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/documents/" + DOC)).isFalse();
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/documents/" + DOC + "/body/x")).isFalse();
        assertThat(InternalAiTokenFilter.isRegisteredPath("/internal/ai/index/generations")).isFalse();
    }

    @Test
    void indexTaskTokenReadsOnlyItsOwnDocumentBody() throws Exception {
        String token = issuer.issueIndexTaskToken(TASK, 3L, PROJECT, DOC);

        mvc.perform(get("/internal/ai/documents/" + DOC + "/body").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/ai/documents/" + OTHER_DOC + "/body").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/ai/documents/not-a-uuid/body").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        // 同一令牌仍可走任务回调、租借、用量
        mvc.perform(post("/internal/ai/tasks/" + TASK + "/claim").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/ai/credentials/lease").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/ai/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void otherTokensCannotReadDocumentBody() throws Exception {
        String reindex = issuer.issueIndexTaskToken(TASK, 1L, null, null);
        String search = issuer.issueKnowledgeSearchToken(USER, PROJECT);
        String admin = issuer.issueIndexAdminToken();
        String plainTask = issuer.issueTaskToken(TASK, 1L, USER, PROJECT, List.of("MEMORY_EXTRACTION"));
        for (String token : List.of(reindex, search, admin, plainTask)) {
            mvc.perform(get("/internal/ai/documents/" + DOC + "/body").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unregisteredPathStillRejectedEvenWithIndexScopes() throws Exception {
        String admin = issuer.issueIndexAdminToken();
        mvc.perform(post("/internal/ai/xxx").header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnauthorized());
        String index = issuer.issueIndexTaskToken(TASK, 1L, PROJECT, DOC);
        mvc.perform(post("/internal/ai/xxx").header("Authorization", "Bearer " + index))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchAndAdminTokensCarryContractClaims() throws Exception {
        Map<String, Object> search = payload(issuer.issueKnowledgeSearchToken(USER, PROJECT));
        assertThat(search.get("scope")).isEqualTo("knowledge:search credentials:lease usage:report");
        assertThat(search.get("purp")).isEqualTo(List.of("EMBEDDING", "RERANK"));
        assertThat(search.get("uid")).isEqualTo(USER.toString());
        assertThat(search.get("pid")).isEqualTo(PROJECT.toString());

        Map<String, Object> admin = payload(issuer.issueIndexAdminToken());
        assertThat(admin.get("scope")).isEqualTo("index:admin");
        assertThat(admin).doesNotContainKeys("uid", "pid", "purp");

        Map<String, Object> index = payload(issuer.issueIndexTaskToken(TASK, 7L, PROJECT, DOC));
        assertThat(index.get("scope"))
                .isEqualTo("task:" + TASK + " credentials:lease usage:report index:document:" + DOC);
        assertThat(index.get("purp")).isEqualTo(List.of("EMBEDDING"));
        assertThat(index).doesNotContainKey("uid");
        assertThat(((Number) index.get("ep")).longValue()).isEqualTo(7L);
        assertThat(((Number) index.get("exp")).longValue() - ((Number) index.get("iat")).longValue())
                .isLessThanOrEqualTo(300L);

        Map<String, Object> reindex = payload(issuer.issueIndexTaskToken(TASK, 1L, null, null));
        assertThat(reindex.get("scope")).isEqualTo("task:" + TASK + " credentials:lease usage:report");
        assertThat(reindex).doesNotContainKeys("uid", "pid");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(String token) throws Exception {
        String[] parts = token.split("\\.");
        return new ObjectMapper().readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
    }

    @RestController
    static class EchoController {
        @PostMapping("/internal/ai/xxx")
        String unregistered() {
            return "ok";
        }

        @GetMapping("/internal/ai/documents/{docId}/body")
        String body(@PathVariable String docId) {
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
