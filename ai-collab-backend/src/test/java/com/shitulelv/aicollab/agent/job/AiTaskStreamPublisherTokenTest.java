package com.shitulelv.aicollab.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 派发令牌按任务类型签发（P2-R4 R4-1）；记忆任务保持 P1 行为。不连 Redis，只测令牌路由。 */
class AiTaskStreamPublisherTokenTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID GEN = UUID.randomUUID();

    private final InternalTokenIssuer issuer = mock(InternalTokenIssuer.class);
    private final AiTaskStreamPublisher publisher =
            new AiTaskStreamPublisher(mock(StringRedisTemplate.class), issuer, new SimpleMeterRegistry());

    @Test
    void memoryTaskKeepsP1TokenWithUserAndPurpose() {
        AiJobRecord job = job(AiJobTypes.MEMORY_EXTRACTION, PROJECT, USER, null, null);
        when(issuer.issueTaskToken(any(), anyLong(), any(), any(), any())).thenReturn("memory-token");

        assertThat(publisher.taskToken(job)).isEqualTo("memory-token");
        verify(issuer).issueTaskToken(job.id(), 4L, USER, PROJECT, List.of("MEMORY_EXTRACTION"));
        verify(issuer, never()).issueIndexTaskToken(any(), anyLong(), any(), any());
    }

    @Test
    void indexDocumentTaskGetsIndexTokenBoundToDocument() {
        AiJobRecord job = job(AiJobTypes.INDEX_DOCUMENT, PROJECT, null, DOC, GEN);
        when(issuer.issueIndexTaskToken(any(), anyLong(), any(), any())).thenReturn("index-token");

        assertThat(publisher.taskToken(job)).isEqualTo("index-token");
        verify(issuer).issueIndexTaskToken(job.id(), 4L, PROJECT, DOC);
        verify(issuer, never()).issueTaskToken(any(), anyLong(), any(), any(), any());
    }

    @Test
    void deleteIndexTaskTokenHasNoDocumentScope() {
        AiJobRecord job = job(AiJobTypes.DELETE_DOCUMENT_INDEX, PROJECT, null, DOC, null);
        when(issuer.issueIndexTaskToken(any(), anyLong(), any(), any())).thenReturn("delete-token");

        assertThat(publisher.taskToken(job)).isEqualTo("delete-token");
        verify(issuer).issueIndexTaskToken(eq(job.id()), eq(4L), eq(PROJECT), isNull());
    }

    @Test
    void reindexGenerationTokenHasNoProjectAndNoDocument() {
        AiJobRecord job = job(AiJobTypes.REINDEX_GENERATION, null, null, null, null);
        when(issuer.issueIndexTaskToken(any(), anyLong(), any(), any())).thenReturn("reindex-token");

        assertThat(publisher.taskToken(job)).isEqualTo("reindex-token");
        verify(issuer).issueIndexTaskToken(eq(job.id()), eq(4L), isNull(), isNull());
    }

    @Test
    void indexTaskWithoutOwnerIsNotRejectedAsOwnerMissing() {
        // Redis 是 mock，XADD 会因 ensureGroup 返回 null 之外的路径失败与否都不重要；
        // 这里只确认系统任务不会在 owner 检查处被拦下：令牌一定被签发。
        AiJobRecord job = job(AiJobTypes.REINDEX_GENERATION, null, null, null, null);
        when(issuer.issueIndexTaskToken(any(), anyLong(), any(), any())).thenReturn("reindex-token");

        publisher.publish(job);

        verify(issuer).issueIndexTaskToken(eq(job.id()), eq(4L), isNull(), isNull());
    }

    @Test
    void memoryTaskWithoutOwnerIsStillRejectedBeforeSigning() {
        AiJobRecord job = job(AiJobTypes.MEMORY_EXTRACTION, PROJECT, null, null, null);

        assertThat(publisher.publish(job)).isFalse();
        verify(issuer, never()).issueTaskToken(any(), anyLong(), any(), any(), any());
        verify(issuer, never()).issueIndexTaskToken(any(), anyLong(), any(), any());
    }

    private static AiJobRecord job(String type, UUID project, UUID createdBy, UUID document, UUID generation) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AiJobRecord(UUID.randomUUID(), project, type, AiJobStatus.QUEUED, 1, "{}", 3, 0, 4L,
                null, null, null, null, null, createdBy, now, now, 0, document, generation, null);
    }
}
