package com.shitulelv.aicollab.infrastructure.ai.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import java.time.OffsetDateTime;
import java.util.List;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, one synthetic batch; no install, model pull, credentials or production database. */
class OllamaEmbeddingSmokeTest {
    @Test @EnabledIfEnvironmentVariable(named="AI_OLLAMA_SMOKE",matches="true")
    void installedLocalModelUsesProductionGateway() {
        var gateway=new ProjectEmbeddingGateway(mock(SystemEmbeddingConfigRepository.class),mock(ModelSecretCipher.class));
        var now=OffsetDateTime.now();
        String model=System.getenv().getOrDefault("AI_OLLAMA_SMOKE_MODEL","qwen3-embedding:0.6b");
        var config=new ProjectEmbeddingConfig(null,"OLLAMA","http://127.0.0.1:11434","/v1/embeddings",null,model,0,2,true,now,now);
        var batch=gateway.embedWithConfig(config,List.of("合成测试：项目计划","合成测试：文档检索"),EmbeddingProgressListener.NONE);
        assertThat(batch.vectors()).hasSize(2);
        assertThat(batch.dimension()).isBetween(1,4096);
        assertThat(batch.vectors().getFirst()).hasSize(batch.dimension());
        System.out.println("Ollama production smoke: model="+model+", dimensions="+batch.dimension()+", count="+batch.vectors().size());
    }
}
