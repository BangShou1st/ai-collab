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
                for (String targetKey : targets) {
                    if (jobs.safeTargetKey(claim, targetKey)) {
                        storage.delete(targetKey);
                    } else {
                        log.warn("跳过不满足安全目标校验的对象删除: targetKey={}", targetKey);
                    }
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
        // 4. MinIO 前缀扫描兜底（发现未落库的孤儿对象），受限扫描最多 200 个 key
        String safePrefix = "projects/" + claim.projectId() + "/documents/" + claim.documentId() + "/";
        try {
            List<String> scannedKeys = storage.list(safePrefix, 200);
            if (scannedKeys != null) {
                targets.addAll(scannedKeys);
            }
        } catch (Exception ex) {
            log.warn("扫描文档对象前缀失败，将依据数据库审计清单继续清理: prefix={}", safePrefix, ex);
        }
        return targets;
    }
}
