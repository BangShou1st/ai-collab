package com.shitulelv.aicollab.connector.recovery;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class DeletionJournalReplayCliTest {
    @Test
    void validatesAuthenticatedBytesCountAndIndependentWatermark() throws Exception {
        byte[] bytes = "{\"watermark\":\"synthetic-end\",\"count\":0,\"records\":[]}".getBytes(StandardCharsets.UTF_8);
        String key = "synthetic-recovery-key-for-test-only";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(bytes));
        assertThat(DeletionJournalReplayCli.verified(bytes,key,signature,"synthetic-end").records()).isEmpty();
        assertThatThrownBy(() -> DeletionJournalReplayCli.verified(bytes,key,signature,"different-end"))
                .isInstanceOf(IllegalArgumentException.class);
        bytes[0] = 'X';
        assertThatThrownBy(() -> DeletionJournalReplayCli.verified(bytes,key,signature,"synthetic-end"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
