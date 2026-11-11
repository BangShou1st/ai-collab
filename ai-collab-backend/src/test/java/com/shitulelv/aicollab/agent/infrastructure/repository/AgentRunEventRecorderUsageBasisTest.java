package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.shitulelv.aicollab.common.ai.token.TokenCountSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenCount 来源 → 数据库 usage_basis 的映射回归（J0-1）。
 *
 * <p>V56/V57 的 CHECK 只允许 PROVIDER/ESTIMATED/UNKNOWN；TOKENIZER 尚无数据库枚举，
 * 保守落到 ESTIMATED，不为该差异新增迁移。</p>
 */
class AgentRunEventRecorderUsageBasisTest {

    @Test
    void mapsTokenSourcesToExistingDatabaseValues() {
        assertThat(AgentRunEventRecorder.usageBasis(TokenCountSource.PROVIDER)).isEqualTo("PROVIDER");
        assertThat(AgentRunEventRecorder.usageBasis(TokenCountSource.TOKENIZER)).isEqualTo("ESTIMATED");
        assertThat(AgentRunEventRecorder.usageBasis(TokenCountSource.ESTIMATE)).isEqualTo("ESTIMATED");
        assertThat(AgentRunEventRecorder.usageBasis(TokenCountSource.UNKNOWN)).isEqualTo("UNKNOWN");
        assertThat(AgentRunEventRecorder.usageBasis((TokenCountSource) null)).isEqualTo("UNKNOWN");
    }
}
