package com.shitulelv.aicollab.agent.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AiIndexJobService} 单元测试（不依赖 Docker）。 */
class AiIndexJobServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GEN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 27, 8, 0, 0, 0, ZoneOffset.UTC);

    private AiJobRepository repository;
    private List<Object> published;
    private AiIndexJobService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiJobRepository.class);
        published = new ArrayList<>();
        service = new AiIndexJobService(repository, new AiJobProperties(false, 4, 60), published::add);
    }

    @Test
    void enqueueIndexDocumentCreatesJobAndPublishesAfterCommitEvent() {
        AiJobRecord regular = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, null, null);
        AiJobRecord targeted = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, GEN_ID, null);
        when(repository.createIndexJob(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, null, null, 4))
                .thenReturn(regular);
        when(repository.createIndexJob(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, GEN_ID, null, 4))
                .thenReturn(targeted);

        assertThat(service.enqueueIndexDocument(PROJECT_ID, DOC_ID)).isSameAs(regular);
        assertThat(service.enqueueIndexDocument(PROJECT_ID, DOC_ID, GEN_ID)).isSameAs(targeted);
        assertThat(published).containsExactly(new AiJobQueuedEvent(regular), new AiJobQueuedEvent(targeted));
    }

    @Test
    void enqueueDeleteDocumentIndexAndReindexGenerationPublishEvents() {
        AiJobRecord deleteJob = job(AiJobTypes.DELETE_DOCUMENT_INDEX, PROJECT_ID, DOC_ID, null, null);
        AiJobRecord reindexJob = job(AiJobTypes.REINDEX_GENERATION, null, null, null, null);
        when(repository.createIndexJob(AiJobTypes.DELETE_DOCUMENT_INDEX, PROJECT_ID, DOC_ID, null, null, 4))
                .thenReturn(deleteJob);
        when(repository.createIndexJob(AiJobTypes.REINDEX_GENERATION, null, null, null, null, 4))
                .thenReturn(reindexJob);

        assertThat(service.enqueueDeleteDocumentIndex(PROJECT_ID, DOC_ID)).isSameAs(deleteJob);
        assertThat(service.enqueueReindexGeneration()).isSameAs(reindexJob);
        assertThat(published).containsExactly(new AiJobQueuedEvent(deleteJob), new AiJobQueuedEvent(reindexJob));
    }

    @Test
    void enqueueReindexChildrenBindsParentAndGeneration() {
        AiJobRecord child = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, GEN_ID, PARENT_ID);
        when(repository.createIndexJob(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, GEN_ID, PARENT_ID, 4))
                .thenReturn(child);

        List<AiJobRecord> created = service.enqueueReindexChildren(
                PARENT_ID, GEN_ID, List.of(new AiIndexJobStore.DocumentRef(DOC_ID, PROJECT_ID)));

        assertThat(created).containsExactly(child);
        assertThat(published).containsExactly(new AiJobQueuedEvent(child));
        verify(repository).createIndexJob(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, GEN_ID, PARENT_ID, 4);
    }

    private static AiJobRecord job(String type, UUID projectId, UUID docId, UUID genId, UUID parentId) {
        return new AiJobRecord(UUID.randomUUID(), projectId, type, AiJobStatus.QUEUED, 1, "{}",
                4, 0, 0L, null, null, null, null, null, null, NOW, NOW, 0,
                docId, genId, parentId, null);
    }
}
