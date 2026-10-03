package com.shitulelv.aicollab.acceptance;

import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import java.net.URI;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.port=18080","spring.flyway.enabled=true","agent.enabled=true",
    "chat.enabled=false","planning.enabled=false","embedding.enabled=false",
    "security.refresh-token.cookie-secure=false","security.cors.allowed-origins=http://localhost:15173",
    "security.jwt.access-token-minutes=30"
})
@EnabledIfEnvironmentVariable(named="AI_BROWSER_ACCEPTANCE",matches="true")
class BrowserAcceptanceHostTest {
    static ScriptedAcceptanceModel peer;
    static GenericContainer<?> minio,redis;
    static Properties properties;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        properties=AcceptanceDatabaseSupport.localProperties(); peer=new ScriptedAcceptanceModel();
        minio=new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest")).withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER","acceptance").withEnv("MINIO_ROOT_PASSWORD","acceptance-test-only")
                .withCommand("server","/data").waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));minio.start();
        redis=new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);redis.start();
        registry.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:55432/"+properties.getProperty("POSTGRES_DB"));
        registry.add("spring.datasource.username",()->properties.getProperty("POSTGRES_USER"));
        registry.add("spring.datasource.password",()->properties.getProperty("POSTGRES_PASSWORD"));
        registry.add("security.jwt.secret",()->properties.getProperty("JWT_SECRET"));
        registry.add("model.config.master-key",()->properties.getProperty("MODEL_CONFIG_MASTER_KEY"));
        registry.add("spring.data.redis.host",redis::getHost);registry.add("spring.data.redis.port",()->redis.getMappedPort(6379));
        registry.add("storage.minio.endpoint",()->"http://"+minio.getHost()+":"+minio.getMappedPort(9000));
        registry.add("storage.minio.access-key",()->"acceptance");registry.add("storage.minio.secret-key",()->"acceptance-test-only");
        registry.add("embedding.local-allowed-targets",()->"127.0.0.1:"+peer.port());
    }
    @MockitoBean OutboundEndpointPolicy endpoints;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelSecretCipher cipher;
    @Test void servesOfflineCopyUntilExplicitStop() throws Exception {
        doAnswer(invocation->{
            URI uri=invocation.getArgument(0);
            if(!uri.getHost().equals("127.0.0.1")||uri.getPort()!=peer.port())
                throw new IllegalArgumentException("Acceptance host only permits its synthetic local peer");
            return null;
        }).when(endpoints).requirePublicHttps(any());
        UUID owner=jdbc.queryForObject("select id from app_user where username=?",UUID.class,properties.getProperty("DEMO_OWNER_USERNAME","owner"));
        for(String model:List.of("acceptance-a","acceptance-b","acceptance-auth-failure")) {
            var existing=jdbc.queryForList("select id from user_ai_provider where user_id=? and model_name=?",UUID.class,owner,model);
            UUID id=existing.isEmpty()?UUID.randomUUID():existing.getFirst();
            jdbc.update("""
                insert into user_ai_provider(id,user_id,name,provider_type,base_url,api_path,encrypted_api_key,model_name,
                  enabled,temperature,max_output_tokens,capabilities,is_default)
                values (?,?,?,'OPENAI_COMPATIBLE',?,'/v1/chat/completions',?,?,true,0.2,6000,
                  'CHAT,STREAMING,NATIVE_TOOLS',false)
                on conflict(id) do update set base_url=excluded.base_url,encrypted_api_key=excluded.encrypted_api_key,updated_at=now()
                """,id,owner,model,peer.base(),cipher.encrypt("synthetic-acceptance-key"),model);
        }
        jdbc.update("update user_ai_provider set is_default=(model_name='acceptance-a') where user_id=?",owner);
        UUID selected=jdbc.queryForObject("select id from user_ai_provider where user_id=? and model_name='acceptance-a'",UUID.class,owner);
        jdbc.update("update user_model_purpose_assignment set provider_id=? where user_id=?",selected,owner);
        System.out.println("BROWSER_ACCEPTANCE_READY api=http://localhost:18080 peer="+peer.base()+"; all model responses synthetic, source database untouched");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(45);
        try { while(!peer.stopped&&System.nanoTime()<deadline) Thread.sleep(500); }
        finally { peer.close();redis.stop();minio.stop(); }
    }
}
