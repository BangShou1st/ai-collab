package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import com.shitulelv.aicollab.common.net.ProxyRouteDiagnostics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit diagnostic opt-in; zero Chat, no credentials, no Spring or business database. */
@EnabledIfEnvironmentVariable(named = "P10_PROXY_DIAGNOSTIC", matches = "true")
class P10NetworkProxyDiagnosticTest {
    @Test
    void inspectActualForkAndVerifySelectedTransportWithoutChat() throws Exception {
        try (var client = OutboundHttpClients.builder().build()) {
            var result = ProxyRouteDiagnostics.connectAndTls(client, URI.create("https://generativelanguage.googleapis.com/"));
            var output = Path.of("target/p10-network-proxy-auto-01/forked-jvm-diagnostic.json");
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
            System.out.println("P10_NETWORK_DIAGNOSTIC=" + output.toAbsolutePath());
            assertThat(result.get("systemDiscoveryStartupArgument")).isEqualTo(true);
            assertThat(result.get("tls")).isEqualTo("VERIFIED");
            assertThat(result.get("chatRequests")).isEqualTo(0);
        }
    }
}
