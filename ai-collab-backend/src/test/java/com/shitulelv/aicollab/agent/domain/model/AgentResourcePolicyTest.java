package com.shitulelv.aicollab.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行资源策略（{@link AgentResourcePolicy}）单测：新策略取消累计 token 上限、
 * 父子独立执行额度、时长与超时放宽。
 */
class AgentResourcePolicyTest {

    // ================= 累计 token 只统计，不限额 =================

    @Test
    void v2HasNoCumulativeInputOrOutputCap() {
        // 关键口径：无累计上限用 null 表达，不用 0 / 8M / Integer.MAX_VALUE 冒充
        assertThat(AgentResourcePolicy.effectiveInputCap(AgentResourcePolicy.V2, null, null)).isNull();
        assertThat(AgentResourcePolicy.effectiveOutputCap(AgentResourcePolicy.V2, null, null)).isNull();
    }

    @Test
    void v2IgnoresAnyRunLevelCapValuesInsteadOfFallingBackToThem() {
        // 即使旧字段还带着 50000/20000，v2 也不把它们当成累计上限
        assertThat(AgentResourcePolicy.effectiveInputCap(AgentResourcePolicy.V2, 50_000, 50_000)).isNull();
        assertThat(AgentResourcePolicy.effectiveOutputCap(AgentResourcePolicy.V2, 20_000, 16_000)).isNull();
    }

    @Test
    void v2NeverReportsCumulativeTokenExhaustion() {
        // 用量远超旧 50k/20k 甚至超过已撤回的 8M 建议值时，仍不因累计输入/输出判到限
        long huge = 9_000_000L;
        assertThat(AgentResourcePolicy.inputExhausted(AgentResourcePolicy.V2, null, null, huge)).isFalse();
        assertThat(AgentResourcePolicy.outputExhausted(AgentResourcePolicy.V2, null, null, huge)).isFalse();
        assertThat(AgentResourcePolicy.remaining(null, huge)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void v1KeepsExistingCumulativeSemanticsForOldRuns() {
        // v1 兼容：min(运行上限, 技能上限) 语义不变，旧运行恢复/暂停续跑不被重解释
        assertThat(AgentResourcePolicy.effectiveInputCap(AgentResourcePolicy.V1, 50_000, 40_000)).isEqualTo(40_000);
        assertThat(AgentResourcePolicy.effectiveOutputCap(AgentResourcePolicy.V1, 20_000, 16_000)).isEqualTo(16_000);
        assertThat(AgentResourcePolicy.effectiveInputCap(AgentResourcePolicy.V1, 30_000, null)).isEqualTo(30_000);
        assertThat(AgentResourcePolicy.effectiveInputCap(AgentResourcePolicy.V1, null, 40_000)).isEqualTo(40_000);
        assertThat(AgentResourcePolicy.remaining(50_000, 50_000)).isZero();
        assertThat(AgentResourcePolicy.inputExhausted(AgentResourcePolicy.V1, 50_000, 50_000, 50_000)).isTrue();
    }

    @Test
    void unknownPolicyVersionFallsBackToV1Compat() {
        // 未知版本不猜测"新语义"，按既有 v1 兼容处理
        assertThat(AgentResourcePolicy.normalize(null)).isEqualTo(AgentResourcePolicy.V1);
        assertThat(AgentResourcePolicy.normalize(0)).isEqualTo(AgentResourcePolicy.V1);
        assertThat(AgentResourcePolicy.normalize(3)).isEqualTo(AgentResourcePolicy.V1);
        assertThat(AgentResourcePolicy.normalize(AgentResourcePolicy.V2)).isEqualTo(AgentResourcePolicy.V2);
    }

    // ================= 父子独立执行额度 =================

    @Test
    void v2RootAndChildHaveTheirOwnExecutionBudgets() {
        AgentRuntimeLimits root = AgentResourcePolicy.v2Limits(0);
        AgentRuntimeLimits child = AgentResourcePolicy.v2Limits(1);

        assertThat(root.maxModelTurns()).isEqualTo(24);
        assertThat(root.maxSteps()).isEqualTo(64);
        assertThat(root.maxToolCalls()).isEqualTo(64);
        assertThat(child.maxModelTurns()).isEqualTo(12);
        assertThat(child.maxSteps()).isEqualTo(16);
        assertThat(child.maxToolCalls()).isEqualTo(24);
        // 单轮工具两种角色都保持 4
        assertThat(root.maxToolCallsPerTurn()).isEqualTo(4);
        assertThat(child.maxToolCallsPerTurn()).isEqualTo(4);
        // 累计 token 上限在 v2 为 null（只统计）
        assertThat(root.maxInputTokens()).isNull();
        assertThat(child.maxOutputTokens()).isNull();
    }

    @Test
    void v2ChildBudgetDoesNotDependOnParentRemaining() {
        // 子运行上限是常量，不随父"还剩多少"变化——这正是"父子独立执行额度"的含义
        assertThat(AgentResourcePolicy.v2Limits(1).maxSteps())
                .isEqualTo(AgentResourcePolicy.V2_CHILD_MAX_STEPS);
        assertThat(AgentResourcePolicy.v2Limits(1).maxToolCalls())
                .isEqualTo(AgentResourcePolicy.V2_CHILD_MAX_TOOL_CALLS);
    }

    // ================= 时长与超时放宽 =================

    @Test
    void v2RelaxesDurationsAndToolTimeouts() {
        AgentRuntimeLimits root = AgentResourcePolicy.v2Limits(0);
        AgentRuntimeLimits child = AgentResourcePolicy.v2Limits(1);

        // 活跃执行时长：根 45 分钟、子 30 分钟（子不继承父剩余）
        assertThat(root.maxRunDuration()).isEqualTo(Duration.ofMinutes(45));
        assertThat(child.maxRunDuration()).isEqualTo(Duration.ofMinutes(30));
        // 内置工具 30 秒、MCP 120 秒
        assertThat(root.internalToolTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(root.mcpToolTimeout()).isEqualTo(Duration.ofSeconds(120));
        // 正文页放大后同步放宽结果字节保护（仍有界）
        assertThat(root.maxToolResultBytes()).isEqualTo(128 * 1024);
    }

    @Test
    void v2DurationsAreNotSilentlyCappedAtFiveMinutes() {
        // 设计明确要求消除隐藏的 300000ms 截停：统一时长不能被另一层压回 5 分钟
        assertThat(AgentResourcePolicy.v2Limits(0).maxRunDuration().toMillis())
                .isGreaterThan(300_000L);
        assertThat(AgentResourcePolicy.v2Limits(1).maxRunDuration().toMillis())
                .isGreaterThan(300_000L);
    }

    // ================= 按运行策略解析限制 =================

    @Test
    void forRunSelectsPolicyAppropriateLimits() {
        // v2 根/子走新策略额度，忽略 Skill
        assertThat(AgentRuntimeLimits.forRun(AgentResourcePolicy.V2, 0, "PROJECT_HEALTH").maxSteps()).isEqualTo(64);
        assertThat(AgentRuntimeLimits.forRun(AgentResourcePolicy.V2, 1, "PROJECT_HEALTH").maxSteps()).isEqualTo(16);
        // v1 沿用既有 Skill 额度语义（旧运行不重解释）
        assertThat(AgentRuntimeLimits.forRun(AgentResourcePolicy.V1, 0, "PROJECT_HEALTH").maxSteps())
                .isEqualTo(AgentRuntimeLimits.forSkill("PROJECT_HEALTH").maxSteps());
    }
}
