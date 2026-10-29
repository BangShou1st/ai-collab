package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorCleanupRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/** Physical deletion is idempotent and follows a durable access block and deletion journal. */
@Service
public class ConnectorCleanupService {
    private final ConnectorCleanupRepository jobs;
    private final DocumentStorageGateway storage;
    private final boolean enabled;

    public ConnectorCleanupService(ConnectorCleanupRepository jobs, DocumentStorageGateway storage,
                                   @Value("${connector.cleanup.enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.storage = storage;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${connector.cleanup.delay-ms:30000}")
    public void processDue() {
        if (!enabled) return;
        for (int count = 0; count < 20; count++) {
            var next = jobs.claim();
            if (next.isEmpty()) return;
            var claim = next.get();
            try {
                if (!jobs.safeTarget(claim)) {
                    jobs.retry(claim, "TARGET_MISMATCH");
                    continue;
                }
                storage.delete(claim.objectKey());
                if (!jobs.complete(claim)) jobs.retry(claim, "CLAIM_OR_TARGET_CHANGED");
            } catch (RuntimeException failure) {
                // Never store exception messages, object paths, or credentials in the job.
                jobs.retry(claim, "CLEANUP_FAILED");
            }
        }
    }
}
