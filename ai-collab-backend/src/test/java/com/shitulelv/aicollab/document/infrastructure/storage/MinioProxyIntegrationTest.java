package com.shitulelv.aicollab.document.infrastructure.storage;

import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.infrastructure.storage.MinioProperties;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated containers and synthetic objects; no shared database or persistent volume. */
@Testcontainers(disabledWithoutDocker = true)
class MinioProxyIntegrationTest {
    private static final String ACCESS = "synthetic-p41-access";
    private static final String SECRET = "synthetic-p41-secret";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Container
    private static final GenericContainer<?> PROXY = new GenericContainer<>(DockerImageName.parse("nginx:1.30.6-alpine"))
            .withNetwork(NETWORK).withExposedPorts(80).dependsOn(MINIO)
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("../ai-collab-deploy/nginx/staging.conf").toAbsolutePath()),
                    "/etc/nginx/conf.d/default.conf");

    @Test
    void signedPathAndHostSurviveProxyAndInvalidRequestsAreRejected() throws Exception {
        String internal = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        MinioClient client = MinioClient.builder().endpoint(internal).credentials(ACCESS, SECRET)
                .region("us-east-1").build();
        client.makeBucket(MakeBucketArgs.builder().bucket("p41-test").region("us-east-1").build());
        byte[] bytes = "隔离下载测试".getBytes(StandardCharsets.UTF_8);
        String key = "projects/synthetic/space + percent%25.txt";
        client.putObject(PutObjectArgs.builder().bucket("p41-test").object(key)
                .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L).contentType("text/plain").build());
        String origin = "https://127.0.0.1:" + PROXY.getMappedPort(80);
        MinioDocumentStorageGateway gateway = new MinioDocumentStorageGateway(client,
                new MinioProperties(internal, ACCESS, SECRET, "p41-test", origin, false));
        URI signed = URI.create(gateway.presign(key, "测试.txt", Duration.ofMinutes(5)).url());
        HttpResponse<byte[]> response = get(signed, "127.0.0.1", signed.getRawPath(), signed.getRawQuery());
        assertEquals(200, response.statusCode());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes),
                MessageDigest.getInstance("SHA-256").digest(response.body()));
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
        assertEquals(403, get(signed, "localhost", signed.getRawPath(), signed.getRawQuery()).statusCode());
        assertEquals(403, get(signed, "127.0.0.1", signed.getRawPath() + "x", signed.getRawQuery()).statusCode());
        assertEquals(403, get(signed, "127.0.0.1", signed.getRawPath(), null).statusCode());
        String query = signed.getRawQuery().replaceFirst("X-Amz-Signature=.", "X-Amz-Signature=0");
        if (query.equals(signed.getRawQuery())) {
            query = signed.getRawQuery().replaceFirst("X-Amz-Signature=.", "X-Amz-Signature=1");
        }
        assertEquals(403, get(signed, "127.0.0.1", signed.getRawPath(), query).statusCode());
        URI expired = URI.create(gateway.presign(key, "测试.txt", Duration.ofSeconds(1)).url());
        Thread.sleep(2500);
        assertEquals(403, get(expired, "127.0.0.1", expired.getRawPath(), expired.getRawQuery()).statusCode());
    }

    private static HttpResponse<byte[]> get(URI signed, String host, String path, String query) throws Exception {
        URI target = URI.create("http://" + host + ":" + signed.getPort() + path + (query == null ? "" : "?" + query));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(target).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
