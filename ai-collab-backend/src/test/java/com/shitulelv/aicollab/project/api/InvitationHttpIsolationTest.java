package com.shitulelv.aicollab.project.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.project.application.service.InvitationCodeService;
import com.shitulelv.aicollab.user.service.UserService;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises HTTP, project authorization, one-use acceptance and persistence on disposable containers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "embedding.enabled=false", "chat.enabled=false", "planning.enabled=false",
        "connector.cleanup.enabled=false", "security.jwt.secret=synthetic-test-only-jwt-secret-with-at-least-32-characters",
        "model.config.master-key=synthetic-test-only-master-key", "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class InvitationHttpIsolationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "synthetic-invite-access")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-invite-secret").withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("storage.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> "synthetic-invite-access");
        registry.add("storage.minio.secret-key", () -> "synthetic-invite-secret");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired AccessTokenService accessTokens;
    @Autowired InvitationCodeService codes;
    @MockitoBean TaskPlanRecoveryJob recovery;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void ownerInvitesExistingMemberWithEmailEnforcementAndOneUse() throws Exception {
        UUID owner = user("invite-owner", "owner@example.invalid");
        UUID member = user("invite-member", "member@example.invalid");
        UUID invited = user("invite-target", "target@example.invalid");
        UUID wrong = user("invite-wrong", "wrong@example.invalid");
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'synthetic project',?,?)",project,owner,owner);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER'),(?,?,'MEMBER')",project,owner,project,member);
        String ownerToken = token(owner), memberToken = token(member), invitedToken = token(invited), wrongToken = token(wrong);
        String create = "/api/v1/projects/" + project + "/invitations";
        String body = "{\"role\":\"MEMBER\",\"invitedEmail\":\"target@example.invalid\",\"expiresInHours\":24}";
        assertEquals(403, post(create, memberToken, body).statusCode());
        var created = post(create,ownerToken,body);
        assertEquals(201,created.statusCode());
        String code = json.readTree(created.body()).path("data").path("code").asText();
        assertTrue(code.matches("[A-Za-z0-9_-]{43}"));
        assertFalse(jdbc.queryForObject("SELECT invite_code_hash FROM project_invitation WHERE project_id=?",String.class,project).equals(code));
        String base = "/api/v1/invitations/" + code;
        assertEquals(200,get(base,null).statusCode());
        var rejected = post(base + "/accept-current-user",wrongToken,"");
        assertEquals(403,rejected.statusCode());
        assertEquals("INVITATION_EMAIL_MISMATCH",json.readTree(rejected.body()).path("code").asText());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id=? AND user_id=?",Integer.class,project,wrong));
        var accepted = post(base + "/accept-current-user",invitedToken,"");
        assertEquals(200,accepted.statusCode());
        assertEquals("MEMBER",json.readTree(accepted.body()).path("data").path("role").asText());
        assertFalse(json.readTree(accepted.body()).path("data").path("alreadyMember").asBoolean());
        assertEquals("MEMBER",jdbc.queryForObject("SELECT role FROM project_member WHERE project_id=? AND user_id=?",String.class,project,invited));
        var repeated = post(base + "/accept-current-user",invitedToken,"");
        assertEquals(200,repeated.statusCode());
        assertTrue(json.readTree(repeated.body()).path("data").path("alreadyMember").asBoolean());
        assertEquals(410,post(base + "/accept-current-user",wrongToken,"").statusCode());

        // Two different users racing for a fresh unbound invitation cannot both consume it.
        var second = post(create,ownerToken,"{\"role\":\"MEMBER\",\"expiresInHours\":24}");
        assertEquals(201,second.statusCode());
        String secondCode = json.readTree(second.body()).path("data").path("code").asText();
        UUID candidateA = user("invite-candidate-a", "a@example.invalid");
        UUID candidateB = user("invite-candidate-b", "b@example.invalid");
        String tokenA = token(candidateA), tokenB = token(candidateB);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> post("/api/v1/invitations/"+secondCode+"/accept-current-user",tokenA,""));
            var b = pool.submit(() -> post("/api/v1/invitations/"+secondCode+"/accept-current-user",tokenB,""));
            assertEquals(1,java.util.stream.Stream.of(a.get(),b.get()).filter(r -> r.statusCode()==200).count());
            assertEquals(1,java.util.stream.Stream.of(a.get(),b.get()).filter(r -> r.statusCode()==410).count());
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id=? AND user_id IN (?,?)",Integer.class,project,candidateA,candidateB));

        var expiring = post(create,ownerToken,"{\"role\":\"MEMBER\",\"expiresInHours\":24}");
        String expiredCode = json.readTree(expiring.body()).path("data").path("code").asText();
        jdbc.update("UPDATE project_invitation SET expires_at=now()-interval '1 minute' WHERE invite_code_hash=?",codes.hash(expiredCode));
        assertEquals(410,get("/api/v1/invitations/"+expiredCode,null).statusCode());
        assertEquals(410,post("/api/v1/invitations/"+expiredCode+"/accept-current-user",wrongToken,"").statusCode());

        var registration = post(create,ownerToken,
                "{\"role\":\"MEMBER\",\"invitedEmail\":\"new@example.invalid\",\"expiresInHours\":24}");
        String registerCode = json.readTree(registration.body()).path("data").path("code").asText();
        String registerPath = "/api/v1/invitations/" + registerCode + "/accept";
        String registerBody = "{\"username\":\"synthetic_new_user\",\"password\":\"Synthetic-Only-Strong1!\",\"displayName\":\"test\",\"email\":\"new@example.invalid\"}";
        assertEquals(403,post(registerPath,null,registerBody).statusCode());
        var registered = postFromAllowedOrigin(registerPath,registerBody);
        assertEquals(201,registered.statusCode());
        assertTrue(registered.headers().firstValue("Set-Cookie").orElse("").contains("HttpOnly"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_member pm JOIN app_user u ON u.id=pm.user_id WHERE pm.project_id=? AND u.username='synthetic_new_user'",Integer.class,project));
    }

    private UUID user(String name, String email) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name,email) VALUES (?,?,?,'test',?)",id,name,"synthetic-hash",email);
        return id;
    }
    private String token(UUID id) { return accessTokens.createAccessToken(users.findById(id).orElseThrow()).value(); }
    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) builder.header("Authorization","Bearer " + token);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET();
        if(token!=null) builder.header("Authorization","Bearer " + token);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postFromAllowedOrigin(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").header("Origin","http://localhost:5173")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
