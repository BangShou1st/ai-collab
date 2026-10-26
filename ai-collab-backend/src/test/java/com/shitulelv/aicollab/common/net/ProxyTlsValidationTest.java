package com.shitulelv.aicollab.common.net;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ProxyTlsValidationTest {
    @TempDir Path temporary;

    @Test void defaultTrustAndHostnameChecksAreNotWeakenedByProxyPolicy() throws Exception {
        Path keyStoreFile = temporary.resolve("ephemeral-test-certificate.p12");
        String password = UUID.randomUUID().toString();
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process generate = new ProcessBuilder(keytool, "-genkeypair", "-alias", "local-test", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(), "-storepass", password,
                "-keypass", password, "-noprompt").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(generate.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(generate.exitValue()).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStoreFile)) { store.load(input, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        try {
            URI localhost = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
            try (HttpClient defaultTrust = OutboundHttpClients.builder().connectTimeout(Duration.ofSeconds(2)).build()) {
                assertThatThrownBy(() -> defaultTrust.send(HttpRequest.newBuilder(localhost).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()))
                        .isInstanceOf(SSLHandshakeException.class);
            }
            TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trusts.init(store);
            SSLContext testOnlyTrust = SSLContext.getInstance("TLS");
            testOnlyTrust.init(null, trusts.getTrustManagers(), null);
            try (HttpClient trusted = OutboundHttpClients.builder().sslContext(testOnlyTrust).connectTimeout(Duration.ofSeconds(2)).build()) {
                assertThat(trusted.send(HttpRequest.newBuilder(localhost).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
                URI wrongName = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/");
                assertThatThrownBy(() -> trusted.send(HttpRequest.newBuilder(wrongName).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()))
                        .isInstanceOf(SSLHandshakeException.class);
            }
            assertThat(requests).hasValue(1);
        } finally { server.stop(0); }
    }
}
