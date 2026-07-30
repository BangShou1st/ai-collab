package com.shitulelv.aicollab.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class AgentMigrationChecksumTest {

    @Test
    void keepsAppliedV22MigrationImmutable() throws Exception {
        CRC32 crc32 = new CRC32();
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V22__add_agent_runtime.sql")) {
            assertThat(input).isNotNull();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    crc32.update(line.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        assertThat((int) crc32.getValue()).isEqualTo(-1950727141);
    }
}
