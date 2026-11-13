package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenMode;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProperties;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpGatewayScopeFilterTest {

    private static final String ENDPOINT = "/internal/ai/v1/mcp";
    private static final long NOW = 1_762_900_100L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    @Test
    void acceptsMcpListOrMcpCallButRejectsUnrelatedScope() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(pair.getPrivate(), "k1", 300, CLOCK);
        InternalTokenVerifier verifier = new InternalTokenVerifier(Map.of("k1", pair.getPublic()), 60, CLOCK);
        InternalTokenProperties properties = new InternalTokenProperties(
                InternalTokenMode.ED25519,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                "k1", 300, 60, "");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new InternalAiTokenFilter(rerank(), properties, verifier, new ObjectMapper()))
                .build();

        for (String scope : List.of("mcp:list", "mcp:call")) {
            mvc.perform(post(ENDPOINT)
                            .header("Authorization", "Bearer " + token(issuer, scope))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }

        mvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + token(issuer, "rerank"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private static String token(InternalTokenIssuer issuer, String scope) {
        return issuer.issueAt(List.of("backend"), List.of(scope), NOW, NOW + 300,
                "jti-" + scope.replace(':', '-'), Map.of());
    }

    private static RerankProperties rerank() {
        return new RerankProperties(RerankMode.JAVA, "http://127.0.0.1:1", 3000,
                Duration.ofSeconds(2), "", 20, 8, 1.0,
                "99999999-9999-9999-9999-999999999999");
    }

    @RestController
    static class ProbeController {
        @PostMapping(ENDPOINT)
        Map<String, Boolean> probe() {
            return Map.of("ok", true);
        }
    }
}
