package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Actual restart/transport experiments, isolated DB only; model output is scripted. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,classes={com.shitulelv.aicollab.AiCollabBackendApplication.class,ReliabilityAcceptanceHostTest.QueueControl.class},properties={
 "server.port=18080","spring.main.allow-bean-definition-overriding=true","agent.enabled=true",
 "chat.enabled=false","planning.enabled=false","embedding.enabled=false",
 "planning.recovery-delay-ms=1000","security.refresh-token.cookie-secure=false",
 "security.cors.allowed-origins=http://localhost:15173","security.jwt.access-token-minutes=60",
 "auth.rate-limit.login-per-hour=200"})
@EnabledIfEnvironmentVariable(named="AI_RELIABILITY_ACCEPTANCE",matches="true")
class ReliabilityAcceptanceHostTest {
 static final Path CONTROL=Path.of("target/reliability-fault-control.json");
 static final Path STOP=Path.of("target/reliability-fault.stop");
 @DynamicPropertySource static void configure(DynamicPropertyRegistry r)throws Exception{
  var ds=AcceptanceDatabaseSupport.dataSource();var p=AcceptanceDatabaseSupport.localProperties();
  r.add("spring.datasource.url",ds::getUrl);r.add("spring.datasource.username",ds::getUsername);r.add("spring.datasource.password",ds::getPassword);
  r.add("security.jwt.secret",()->p.getProperty("JWT_SECRET"));r.add("model.config.master-key",()->p.getProperty("MODEL_CONFIG_MASTER_KEY"));
  r.add("spring.data.redis.host",()->"127.0.0.1");r.add("spring.data.redis.port",()->16379);
  r.add("storage.minio.endpoint",()->"http://127.0.0.1:18090");r.add("storage.minio.access-key",()->"acceptance");r.add("storage.minio.secret-key",()->"acceptance-test-only");r.add("storage.minio.bucket",()->"ai-real-acceptance");
 }
 @TestConfiguration static class QueueControl {
  @Bean("planningTaskExecutor") java.util.concurrent.Executor executor() {
   var pool=Executors.newSingleThreadExecutor();
   pool.submit(()->{try{while(new ObjectMapper().readTree(Files.readString(CONTROL)).path("queueHold").asBoolean())Thread.sleep(100);}catch(Exception e){throw new IllegalStateException(e);}});
   return pool;
  }
 }
 @Autowired JdbcTemplate jdbc;@Autowired ModelSecretCipher cipher;
 @Autowired com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService providers;
 @MockitoBean OutboundEndpointPolicy endpoints;
 @Test void servesControlledFaultExperimentsUntilStop()throws Exception{
  if(!Files.exists(Path.of("target/reliability-provider-backup.json")))throw new IllegalStateException("Private provider backup required before restart experiments");
  doAnswer(call->{URI uri=call.getArgument(0);if(!"127.0.0.1".equals(uri.getHost())||uri.getPort()!=18082)throw new IllegalArgumentException("Only isolated controlled peer permitted");return null;}).when(endpoints).requirePublicHttps(any());
  UUID owner=jdbc.queryForObject("select id from app_user where username=?",UUID.class,AcceptanceDatabaseSupport.localProperties().getProperty("DEMO_OWNER_USERNAME","owner"));
  var rows=jdbc.queryForList("select id from user_ai_provider where user_id=? and name='reliability-scripted-peer'",UUID.class,owner);
  UUID id=rows.isEmpty()?UUID.randomUUID():rows.getFirst();
  if(rows.isEmpty())jdbc.update("""
    INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,api_path,encrypted_api_key,model_name,enabled,temperature,max_output_tokens,capabilities,is_default)
    VALUES (?,?,'reliability-scripted-peer','OPENAI_COMPATIBLE','http://127.0.0.1:18082','/v1/chat/completions',?,'reliability-scripted-peer',true,0.2,6000,'CHAT,NATIVE_TOOLS',false)
    """,id,owner,cipher.encrypt("synthetic-test-only"));
  else jdbc.update("UPDATE user_ai_provider SET enabled=true WHERE id=? AND enabled=false",id);
  // Caller saves/restores original configuration in private target before experiments.
  if(!Boolean.TRUE.equals(jdbc.queryForObject("select is_default from user_ai_provider where id=?",Boolean.class,id)))providers.setDefault(owner,id);
  jdbc.update("update user_model_purpose_assignment set provider_id=? where user_id=?",id,owner);
  Files.deleteIfExists(STOP);System.out.println("RELIABILITY_ACCEPTANCE_READY controlled peer; isolated persistent DB only");
  long end=System.nanoTime()+TimeUnit.MINUTES.toNanos(45);while(!Files.exists(STOP)&&System.nanoTime()<end)Thread.sleep(100);
 }
}
