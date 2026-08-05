package com.shitulelv.aicollab.agent.infrastructure.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpAdministrationTransactionBoundaryTest {
    @Test
    void discoveryNetworkFlowDoesNotHoldDatabaseTransaction() throws Exception {
        var discover = McpAdministrationService.class.getMethod("discover", UUID.class, UUID.class);

        assertThat(discover.getAnnotation(Transactional.class)).isNull();
    }
}
