package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentRevisionMapper;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorCleanupRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Physical deletion is idempotent and follows a durable access block and deletion journal. */
@Service
public class ConnectorCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ConnectorCleanupService.class);
    private static final int BATCH_SCAN_LIMIT = 100;

    private final ConnectorCleanupRepository jobs;
    private final DocumentStorageGateway storage;
    private final ExternalDocumentRevisionMapper revisions;
    private final boolean enabled;

    @Autowired
    public ConnectorCleanupService(ConnectorCleanupRepository jobs,
                                   DocumentStorageGateway storage,
                                   ExternalDocumentRevisionMapper revisions,
                                   @Value("${connector.cleanup.enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.storage = storage;
        this.revisions = revisions;
        this.enabled = enabled;
    }

    public ConnectorCleanupService(ConnectorCleanupRepository jobs,
                                   DocumentStorageGateway storage,
                                   boolean enabled) {
        this(jobs, storage, null, enabled);
    }

    @Scheduled(fixedDelayString = "${connector.cleanup.delay-ms:30000}")
    public void processDue() {
        if (!enabled) return;
        processDueBatch();
    }

    public int processDueBatch() {
        int processed = 0;
        for (int count = 0; count < 20; count++) {
            var next = jobs.claim();
            if (next.isEmpty()) return processed;
            var claim = next.get();
            try {
                if (!jobs.safeTarget(claim)) {
                    jobs.retry(claim, "TARGET_MISMATCH");
                    continue;
                }
                Set<String> targets = collectTargetsToDelete(claim);
                boolean safetyViolation = false;
                for (String targetKey : targets) {
                    if (!jobs.safeTargetKey(claim, targetKey)) {
                        log.warn("待清理对象未通过安全目标校验，阻断完成: targetKey={}", targetKey);
                        jobs.retry(claim, "TARGET_SAFETY_VIOLATION");
                        safetyViolation = true;
                        break;
                    }
                    storage.delete(targetKey);
                }
                if (safetyViolation) {
                    continue;
                }

                // 后验校验：检查该文档专属前缀下是否仍有未清理对象（例如对象总数超过单批上限）
                String safePrefix = "projects/" + claim.projectId() + "/documents/" + claim.documentId() + "/";
                List<String> remaining = storage.list(safePrefix, 1);
                if (remaining != null && !remaining.isEmpty()) {
                    log.info("文档专属前缀下仍有剩余对象未清理完毕，排队继续下一批: prefix={}", safePrefix);
                    jobs.requeue(claim);
                    continue;
                }

                if (!jobs.complete(claim)) {
                    jobs.retry(claim, "CLAIM_OR_TARGET_CHANGED");
                } else {
                    processed++;
                }
            } catch (RuntimeException failure) {
                // Never store exception messages, object paths, or credentials in the job.
                jobs.retry(claim, "CLEANUP_FAILED");
            }
        }
        return processed;
    }

    private Set<String> collectTargetsToDelete(ConnectorCleanupRepository.Claim claim) {
        Set<String> targets = new LinkedHashSet<>();
        // 1. journal 中记录的当前 key
        if (claim.objectKey() != null) {
            targets.add(claim.objectKey());
        }
        // 2. external_document_revision 中记录的历史版本 key
        if (revisions != null) {
            List<String> recordedRevisionKeys = revisions.listObjectKeysByDocument(claim.sourceId(), claim.documentId());
            if (recordedRevisionKeys != null) {
                targets.addAll(recordedRevisionKeys);
            }
        }
        // 3. 历史旧格式固定 key 兼容
        targets.add("projects/" + claim.projectId() + "/documents/" + claim.documentId() + "/source.txt");
        // 4. MinIO 前缀受限枚举：如果扫描抛出异常，不再静默吞掉，直接抛出由重试机制安全收敛！
        String safePrefix = "projects/" + claim.projectId() + "/documents/" + claim.documentId() + "/";
        List<String> scannedKeys = storage.list(safePrefix, BATCH_SCAN_LIMIT);
        if (scannedKeys != null) {
            targets.addAll(scannedKeys);
        }
        return targets;
    }
}
