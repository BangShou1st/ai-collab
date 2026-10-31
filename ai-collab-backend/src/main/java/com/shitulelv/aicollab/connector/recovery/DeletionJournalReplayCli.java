package com.shitulelv.aicollab.connector.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.connector.application.ConnectorDeletionReplayService;
import com.shitulelv.aicollab.connector.application.ConnectorVisibilityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Offline-only recovery entry point. No Flyway, web server or scheduler is started. */
public final class DeletionJournalReplayCli {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private DeletionJournalReplayCli() { }

    public record Journal(String watermark, int count, List<ConnectorDeletionReplayService.Tombstone> records) { }

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !"YES".equals(System.getenv("AI_COLLAB_RECOVERY_CONFIRM_OFFLINE"))) {
                throw new IllegalArgumentException("offline confirmation required");
            }
            Path file = Path.of(args[0]);
            if (Files.size(file) > MAX_BYTES) throw new IllegalArgumentException("journal too large");
            Journal journal = verified(Files.readAllBytes(file), required("AI_COLLAB_RECOVERY_JOURNAL_HMAC_KEY"),
                    required("AI_COLLAB_RECOVERY_JOURNAL_MAC"), required("AI_COLLAB_RECOVERY_EXPECTED_WATERMARK"));
            var ds = new DriverManagerDataSource(required("AI_COLLAB_RECOVERY_DATABASE_URL"),
                    required("AI_COLLAB_RECOVERY_DATABASE_USER"), required("AI_COLLAB_RECOVERY_DATABASE_PASSWORD"));
            var jdbc = new JdbcTemplate(ds);
            var replay = new ConnectorDeletionReplayService(jdbc, new ConnectorVisibilityService(jdbc), true);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
            for (int offset = 0; offset < journal.records().size(); offset += 1000) {
                int start = offset, end = Math.min(offset + 1000, journal.records().size());
                transactions.executeWithoutResult(status -> replay.replay(journal.records().subList(start, end)));
            }
            System.out.println("已重放删除记录数：" + journal.records().size());
        } catch (Exception failure) {
            // Driver errors can include connection strings. Never echo provider or database exception text.
            System.err.println("删除记录重放失败；请在隔离恢复环境核对输入、完整性和数据库状态。");
            System.exit(1);
        }
    }

    static Journal verified(byte[] bytes, String key, String expectedMac, String expectedWatermark) throws Exception {
        if (bytes.length > MAX_BYTES || key.length() < 32 || !expectedMac.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invalid journal verification inputs");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        if (!MessageDigest.isEqual(mac.doFinal(bytes), HexFormat.of().parseHex(expectedMac))) {
            throw new IllegalArgumentException("journal authentication failed");
        }
        Journal journal = JSON.readValue(bytes, Journal.class);
        if (journal.records() == null || journal.count() != journal.records().size()
                || journal.records().size() > 100_000 || !expectedWatermark.equals(journal.watermark())) {
            throw new IllegalArgumentException("journal is incomplete");
        }
        return journal;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing recovery setting");
        return value;
    }
}
