package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real Ollama + production embedding gateway + pgvector in the fixed offline copy. */
@SpringBootTest(properties={"spring.flyway.enabled=true","agent.enabled=false","chat.enabled=false","planning.enabled=false","embedding.enabled=false","security.jwt.access-token-minutes=60"})
@EnabledIfEnvironmentVariable(named="AI_REAL_RETRIEVAL",matches="true")
class RealRetrievalBaselineTest {
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        var ds=AcceptanceDatabaseSupport.dataSource();var p=AcceptanceDatabaseSupport.localProperties();
        registry.add("spring.datasource.url",ds::getUrl);registry.add("spring.datasource.username",ds::getUsername);registry.add("spring.datasource.password",ds::getPassword);
        registry.add("security.jwt.secret",()->p.getProperty("JWT_SECRET"));registry.add("model.config.master-key",()->p.getProperty("MODEL_CONFIG_MASTER_KEY"));
        registry.add("spring.data.redis.port",()->16379);registry.add("storage.minio.endpoint",()->"http://127.0.0.1:18090");registry.add("storage.minio.access-key",()->"acceptance");registry.add("storage.minio.secret-key",()->"acceptance-test-only");registry.add("storage.minio.bucket",()->"ai-real-acceptance");
    }
    @Autowired DocumentSearchService search; @Autowired ObjectMapper json;
    @Test void measuresRealVectorRetrievalWithoutHybridOrReranker() throws Exception {
        var state=json.readTree(Files.readString(Path.of("target/p4-state.json")));
        UUID project=UUID.fromString(state.path("projectId").asText()),document=UUID.fromString(state.path("documentId").asText());
        var cases=new LinkedHashMap<String,String>();
        cases.put("系统需要支持哪些功能？","必要需求");cases.put("允许多少项任务？","必要需求");
        cases.put("规划开始和结束日期？","验收约束");cases.put("谁批准正式任务？","验收约束");
        var results=new ArrayList<Map<String,Object>>();int hits=0;
        for(var entry:cases.entrySet()) {
            long start=System.nanoTime();var sources=search.search(project,entry.getKey(),List.of(document),3);
            boolean hit=sources.stream().anyMatch(s->entry.getValue().equals(s.heading()));if(hit) hits++;
            results.add(Map.of("query",entry.getKey(),"expectedHeading",entry.getValue(),"hitAt3",hit,"latencyMs",(System.nanoTime()-start)/1_000_000,"sources",sources));
        }
        var evidence=Map.of("executionDate","2026-10-04","adapter","ProjectEmbeddingGateway + DocumentSearchService + pgvector","embedding","real Ollama, configured system model","cases",results,"hitAt3",hits,"caseCount",cases.size(),"hybrid",false);
        Files.writeString(Path.of("../docs/acceptance-evidence/2026-10-04/p4-vector-baseline.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        assertThat(results).hasSize(4);assertThat(results).allMatch(r->!((List<?>)r.get("sources")).isEmpty());
        System.out.println("REAL_VECTOR_BASELINE hit@3="+hits+"/4; production gateway, pgvector; no hybrid");
    }
}
