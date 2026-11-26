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
import org.testcontainers.images.builder.Transferable;
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
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 双实例 upstream 降级：只有 app-1-edge 在线，app-2-edge 在 Compose 网络里不存在（未创建或已停止，DNS 无记录）。
 * staging.conf 的 server ... resolve 必须让代理照常启动，且下载鉴权全部落到在线实例并返回 200。
 */
@Testcontainers(disabledWithoutDocker = true)
class MinioProxySingleInstanceIntegrationTest {
    private static final String ACCESS = "synthetic-p41-access";
    private static final String SECRET = "synthetic-p41-secret";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> AUTH = new GenericContainer<>(DockerImageName.parse("nginx:1.30.6-alpine"))
            // 仅一个实例在线：不注册 app-2-edge 别名。
            .withNetwork(NETWORK).withNetworkAliases("app-1-edge")
            .withCopyToContainer(Transferable.of("""
                    server {
                      listen 8080;
                      location = /internal/storage-authorize {
                        if ($http_x_storage_path = "") { return 500; }
                        return 204;
                      }
                    }
                    """), "/etc/nginx/conf.d/default.conf");

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Container
    private static final GenericContainer<?> PROXY = new GenericContainer<>(DockerImageName.parse("nginx:1.30.6-alpine"))
            .withNetwork(NETWORK).withExposedPorts(80).dependsOn(MINIO, AUTH)
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("../ai-collab-deploy/nginx/staging.conf").toAbsolutePath()),
                    "/etc/nginx/conf.d/default.conf");

    @Test
    void proxyServesSignedDownloadWhenOnlyOneAppInstanceIsOnline() throws Exception {
        String internal = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        MinioClient client = MinioClient.builder().endpoint(internal).credentials(ACCESS, SECRET)
                .region("us-east-1").build();
        client.makeBucket(MakeBucketArgs.builder().bucket("p41-single").region("us-east-1").build());
        byte[] bytes = "单实例降级下载".getBytes(StandardCharsets.UTF_8);
        String key = "projects/synthetic/single-instance.txt";
        client.putObject(PutObjectArgs.builder().bucket("p41-single").object(key)
                .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L).contentType("text/plain").build());
        String origin = "https://127.0.0.1:" + PROXY.getMappedPort(80);
        MinioDocumentStorageGateway gateway = new MinioDocumentStorageGateway(client,
                new MinioProperties(internal, ACCESS, SECRET, "p41-single", origin, false));
        URI signed = URI.create(gateway.presign(key, "单实例.txt", Duration.ofMinutes(5)).url());
        URI target = URI.create("http://127.0.0.1:" + signed.getPort() + signed.getRawPath() + "?" + signed.getRawQuery());
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // 连续多次请求覆盖轮询：每一次都必须落到在线实例，而不是因缺失的 app-2-edge 返回 5xx。
        for (int i = 0; i < 4; i++) {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode(), "attempt " + i);
            assertArrayEquals(bytes, response.body());
        }
    }
}
