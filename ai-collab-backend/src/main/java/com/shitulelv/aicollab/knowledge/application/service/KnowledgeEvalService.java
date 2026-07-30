package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeEvalRequest;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeEvalResultView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeEvalRunDetailView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeEvalRunView;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeEvalResultEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeEvalRunEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.mapper.KnowledgeEvalMapper;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class KnowledgeEvalService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEvalService.class);
    private static final int TOP_K = 5;
    private final ProjectAccessGuard access;
    private final KnowledgeEvalMapper evalMapper;
    private final DocumentSearchService search;
    private final ObjectMapper objectMapper;
    private final ExecutorService evalExecutor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "knowledge-eval-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    });

    public KnowledgeEvalService(
            ProjectAccessGuard access,
            KnowledgeEvalMapper evalMapper,
            DocumentSearchService search,
            ObjectMapper objectMapper) {
        this.access = access;
        this.evalMapper = evalMapper;
        this.search = search;
        this.objectMapper = objectMapper;
    }

    public UUID startEval(UUID projectId, UUID userId, KnowledgeEvalRequest request) {
        access.requireAdmin(projectId, userId);
        if (request == null || request.testCases() == null || request.testCases().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "测试用例不能为空");
        }
        if (request.testCases().size() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "测试用例不能超过 100 个");
        }

        KnowledgeEvalRunEntity run = new KnowledgeEvalRunEntity();
        run.setId(UUID.randomUUID());
        run.setProjectId(projectId);
        run.setTriggeredBy(userId);
        run.setStatus("RUNNING");
        run.setTotalQuestions(request.testCases().size());
        run.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        evalMapper.insert(run);

        UUID runId = run.getId();
        evalExecutor.submit(() -> executeEval(projectId, runId, request));
        return runId;
    }

    private void executeEval(UUID projectId, UUID runId, KnowledgeEvalRequest request) {
        try {
            List<BigDecimal> recallAt3List = new ArrayList<>();
            List<BigDecimal> recallAt5List = new ArrayList<>();
            List<BigDecimal> mrrList = new ArrayList<>();
            List<BigDecimal> similarityList = new ArrayList<>();

            for (KnowledgeEvalRequest.TestCase testCase : request.testCases()) {
                try {
                    List<DocumentSearchHit> hits = search.search(
                            projectId, testCase.question(), List.of(), TOP_K);

                    List<String> retrievedDocIds = hits.stream()
                            .map(h -> h.documentId().toString())
                            .toList();
                    List<String> expectedDocIds = testCase.expectedDocumentIds() != null
                            ? testCase.expectedDocumentIds() : List.of();

                    BigDecimal recall3 = computeRecall(expectedDocIds, retrievedDocIds, 3);
                    BigDecimal recall5 = computeRecall(expectedDocIds, retrievedDocIds, 5);
                    BigDecimal mrr = computeMRR(expectedDocIds, retrievedDocIds);

                    recallAt3List.add(recall3);
                    recallAt5List.add(recall5);
                    mrrList.add(mrr);
                    if (!hits.isEmpty()) {
                        similarityList.add(BigDecimal.valueOf(hits.getFirst().similarity()));
                    }

                    KnowledgeEvalResultEntity result = new KnowledgeEvalResultEntity();
                    result.setId(UUID.randomUUID());
                    result.setRunId(runId);
                    result.setQuestion(testCase.question());
                    result.setExpectedDocumentIds(objectMapper.writeValueAsString(expectedDocIds));
                    result.setRetrievedDocumentIds(objectMapper.writeValueAsString(retrievedDocIds));
                    result.setRecallAt3(recall3);
                    result.setRecallAt5(recall5);
                    result.setMrr(mrr);
                    result.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    evalMapper.insertResult(
                            result.getId(), result.getRunId(), result.getQuestion(),
                            result.getExpectedDocumentIds(), result.getRetrievedDocumentIds(),
                            result.getRecallAt3(), result.getRecallAt5(), result.getMrr(),
                            result.getCreatedAt());
                } catch (Exception e) {
                    log.warn("Eval question failed: {}", testCase.question(), e);
                }
            }

            KnowledgeEvalRunEntity run = evalMapper.findRun(runId, projectId);
            if (run == null) return;
            run.setStatus("COMPLETED");
            run.setRecallAt3(average(recallAt3List));
            run.setRecallAt5(average(recallAt5List));
            run.setMrr(average(mrrList));
            run.setAvgSimilarity(average(similarityList));
            run.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            evalMapper.updateById(run);
        } catch (Exception e) {
            log.error("Eval run failed", e);
            KnowledgeEvalRunEntity run = evalMapper.selectById(runId);
            if (run != null) {
                run.setStatus("FAILED");
                run.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
                evalMapper.updateById(run);
            }
        }
    }

    public List<KnowledgeEvalRunView> listRuns(UUID projectId, UUID userId) {
        access.requireAdmin(projectId, userId);
        return evalMapper.listRuns(projectId).stream()
                .map(this::toRunView)
                .toList();
    }

    public KnowledgeEvalRunDetailView getRun(UUID projectId, UUID runId, UUID userId) {
        access.requireAdmin(projectId, userId);
        KnowledgeEvalRunEntity run = evalMapper.findRun(runId, projectId);
        if (run == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND, "评测记录不存在");
        }
        List<KnowledgeEvalResultView> results = evalMapper.listResults(runId).stream()
                .map(this::toResultView)
                .toList();
        return new KnowledgeEvalRunDetailView(toRunView(run), results);
    }

    private KnowledgeEvalRunView toRunView(KnowledgeEvalRunEntity entity) {
        return new KnowledgeEvalRunView(
                entity.getId(), entity.getStatus(), entity.getTotalQuestions(),
                entity.getRecallAt3(), entity.getRecallAt5(), entity.getMrr(),
                entity.getAvgSimilarity(), entity.getCreatedAt(), entity.getCompletedAt());
    }

    private KnowledgeEvalResultView toResultView(KnowledgeEvalResultEntity entity) {
        try {
            List<String> expected = objectMapper.readValue(entity.getExpectedDocumentIds(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            List<String> retrieved = objectMapper.readValue(entity.getRetrievedDocumentIds(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new KnowledgeEvalResultView(
                    entity.getQuestion(), expected, retrieved,
                    entity.getRecallAt3(), entity.getRecallAt5(), entity.getMrr());
        } catch (Exception e) {
            return new KnowledgeEvalResultView(
                    entity.getQuestion(), List.of(), List.of(),
                    entity.getRecallAt3(), entity.getRecallAt5(), entity.getMrr());
        }
    }

    private static BigDecimal computeRecall(List<String> expected, List<String> retrieved, int k) {
        if (expected.isEmpty()) return BigDecimal.ZERO;
        List<String> topK = retrieved.stream().limit(k).toList();
        long found = expected.stream().filter(topK::contains).count();
        return BigDecimal.valueOf(found)
                .divide(BigDecimal.valueOf(expected.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal computeMRR(List<String> expected, List<String> retrieved) {
        if (expected.isEmpty()) return BigDecimal.ZERO;
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return BigDecimal.ONE.divide(BigDecimal.valueOf(i + 1), 4, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }
}
