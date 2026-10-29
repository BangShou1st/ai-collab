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

    private String token(UUID id) { return accessTokens.createAccessToken(users.findById(id).orElseThrow()).value(); }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET();
        if (token != null) request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
