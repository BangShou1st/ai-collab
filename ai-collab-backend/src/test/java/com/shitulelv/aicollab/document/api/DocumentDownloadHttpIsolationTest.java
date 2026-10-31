package com.shitulelv.aicollab.document.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.user.service.UserService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP authorization with disposable PostgreSQL and MinIO fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.flyway.enabled=true", "embedding.enabled=false", "chat.enabled=false", "planning.enabled=false",
        "connector.cleanup.enabled=false", "security.jwt.secret=synthetic-test-only-jwt-secret-with-at-least-32-characters",
        "model.config.master-key=synthetic-test-only-master-key", "security.jwt.access-token-minutes=30"})
@Testcontainers(disabledWithoutDocker=true)
@ActiveProfiles("test")
class DocumentDownloadHttpIsolationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER","synthetic-http-access")
            .withEnv("MINIO_ROOT_PASSWORD","synthetic-http-secret").withCommand("server","/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",DB::getJdbcUrl);
        properties.add("spring.datasource.username",DB::getUsername);
        properties.add("spring.datasource.password",DB::getPassword);
        properties.add("storage.minio.endpoint",() -> "http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));
        properties.add("storage.minio.access-key",() -> "synthetic-http-access");
        properties.add("storage.minio.secret-key",() -> "synthetic-http-secret");
        properties.add("storage.minio.bucket",() -> "p41-http-test");
        properties.add("storage.minio.public-endpoint",() -> "https://download.example.test");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;
    @Autowired UserService users;
    @Autowired AccessTokenService accessTokens;
    @MockitoBean TaskPlanRecoveryJob recovery;
    final HttpClient http=HttpClient.newHttpClient();

    @Test
    void onlyMembersCanObtainAddressAndBlockedDocumentsCannotBeDownloaded() throws Exception {
        UUID owner=UUID.randomUUID(), outsider=UUID.randomUUID(), project=UUID.randomUUID(), document=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'synthetic-hash','test'),(?,?,'synthetic-hash','test')",
                owner,"http-owner",outsider,"http-outsider");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'test',?,?)",project,owner,owner);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",project,owner);
        String key="projects/"+project+"/documents/"+document+"/source.txt";
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) VALUES (?,?,'test','test.txt','text/plain',4,?,'READY',?)",document,project,key,owner);
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket("p41-http-test").build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket("p41-http-test").build());
        }
        byte[] bytes="test".getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder().bucket("p41-http-test").object(key)
                .stream(new ByteArrayInputStream(bytes),(long) bytes.length,-1L).build());
        String path="/api/v1/projects/"+project+"/documents/"+document+"/download-url";
        var memberResult=get(path,token(owner));
        assertEquals(200,memberResult.statusCode());
        URI url=URI.create(new ObjectMapper().readTree(memberResult.body()).path("data").path("url").asText());
        assertEquals("https",url.getScheme());
        assertEquals("download.example.test",url.getHost());
        assertTrue(url.getRawPath().startsWith("/storage/p41-http-test/"));
        assertEquals(404,get(path,token(outsider)).statusCode());
        assertEquals(401,get(path,null).statusCode());
        jdbc.update("UPDATE project_document SET access_blocked=true WHERE id=?",document);
        assertEquals(404,get(path,token(owner)).statusCode());
    }

    @Test
    void directStreamDownloadCoversAllSecurityAndIntegrityRequirements() throws Exception {
        UUID owner = UUID.randomUUID(), outsider = UUID.randomUUID(), project1 = UUID.randomUUID(), project2 = UUID.randomUUID();
        UUID docChinese = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'synthetic-hash','test'),(?,?,'synthetic-hash','test')",
                owner, "http-owner-2", outsider, "http-outsider-2");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'project1',?,?),(?,'project2',?,?)",
                project1, owner, owner, project2, outsider, outsider);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER'),(?,?,'OWNER')",
                project1, owner, project2, outsider);

        if (!minio.bucketExists(BucketExistsArgs.builder().bucket("p41-http-test").build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket("p41-http-test").build());
        }

        String chineseFilename = "竞赛规划_2026.txt";
        byte[] originalBytes = "真实中文文档下载与字节一致性校验内容：abc-123-!@#".getBytes(StandardCharsets.UTF_8);
        String chineseKey = "projects/" + project1 + "/documents/" + docChinese + "/" + chineseFilename;
        minio.putObject(PutObjectArgs.builder().bucket("p41-http-test").object(chineseKey)
                .stream(new ByteArrayInputStream(originalBytes), (long) originalBytes.length, -1L)
                .contentType("text/plain").build());
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) " +
                        "VALUES (?,?,?,?,?,?,?,'READY',?)",
                docChinese, project1, "测试文档", chineseFilename, "text/plain", (long) originalBytes.length, chineseKey, owner);

        String downloadPath = "/api/v1/projects/" + project1 + "/documents/" + docChinese + "/download";

        // 1. 正常用户下载：200 OK，字节一致（SHA-256匹配），响应头完备
        HttpResponse<byte[]> normal = getBytes(downloadPath, token(owner));
        assertEquals(200, normal.statusCode());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(originalBytes),
                MessageDigest.getInstance("SHA-256").digest(normal.body()));
        assertEquals("text/plain", normal.headers().firstValue("Content-Type").orElse(""));
        assertEquals(String.valueOf(originalBytes.length), normal.headers().firstValue("Content-Length").orElse(""));
        String disposition = normal.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.startsWith("attachment"));
        assertTrue(disposition.contains("filename*="));

        // 2. 未登录下载：401 Unauthorized
        assertEquals(401, getBytes(downloadPath, null).statusCode());

        // 3. 无权限用户下载：统一按防信息探测规范返回 404 Not Found
        assertEquals(404, getBytes(downloadPath, token(outsider)).statusCode());

        // 4. 跨项目下载：outsider 试图通过自己有权限的项目 project2 访问 project1 的文档 -> 404
        String crossProjectPath = "/api/v1/projects/" + project2 + "/documents/" + docChinese + "/download";
        assertEquals(404, getBytes(crossProjectPath, token(outsider)).statusCode());

        // 5. 文件不存在下载：404 Not Found
        String notFoundPath = "/api/v1/projects/" + project1 + "/documents/" + UUID.randomUUID() + "/download";
        assertEquals(404, getBytes(notFoundPath, token(owner)).statusCode());

        // 6. 文档被屏蔽（access_blocked=true）：404 Not Found
        UUID blockedDoc = UUID.randomUUID();
        String blockedKey = "projects/" + project1 + "/documents/" + blockedDoc + "/blocked.txt";
        minio.putObject(PutObjectArgs.builder().bucket("p41-http-test").object(blockedKey)
                .stream(new ByteArrayInputStream(originalBytes), (long) originalBytes.length, -1L).build());
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by,access_blocked) " +
                        "VALUES (?,?,?,?,?,?,?,'READY',?,true)",
                blockedDoc, project1, "屏蔽文档", "blocked.txt", "text/plain", (long) originalBytes.length, blockedKey, owner);
        assertEquals(404, getBytes("/api/v1/projects/" + project1 + "/documents/" + blockedDoc + "/download", token(owner)).statusCode());

        // 7. MinIO 中存储对象丢失时的错误处理：返回 404 DOCUMENT_STORAGE_OBJECT_NOT_FOUND，不泄露存储内部敏感凭据
        UUID missingStorageDoc = UUID.randomUUID();
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) " +
                        "VALUES (?,?,?,?,?,?,?,'READY',?)",
                missingStorageDoc, project1, "丢失文档", "missing.txt", "text/plain", 10L, "projects/missing/object.txt", owner);
        HttpResponse<String> missingStorageResp = get("/api/v1/projects/" + project1 + "/documents/" + missingStorageDoc + "/download", token(owner));
        assertEquals(404, missingStorageResp.statusCode());
        assertTrue(missingStorageResp.body().contains("DOCUMENT_STORAGE_OBJECT_NOT_FOUND"));
    }

    private String token(UUID id) { return accessTokens.createAccessToken(users.findById(id).orElseThrow()).value(); }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET();
        if (token != null) request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path, String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET();
        if (token != null) request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
}
