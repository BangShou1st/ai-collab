package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 固定 Scenario 定义。Scenario 只决定追加授予的工具与输出要求，不能授予权限。
 */
public interface AgentScenario {

    /** Scenario 唯一代码 */
    String code();

    /** 显示名称 */
    String displayName();

    /** 描述 */
    String description();

    /** 适用的页面入口路由（仅用于无显式选择时的确定性兜底推断） */
    Set<String> entryRoutes();

    /**
     * 本场景追加授予的工具集合：追加授予，不是白名单。
     * 只读工具只能加不能减；写工具必须显式授予并仍走审批。
     */
    Set<String> grantedTools();

    /** 输入 Schema（JSON Schema） */
    JsonNode inputSchema();

    /** 是否允许写工具（需要审批） */
    boolean allowWriteTools();

    /** 是否允许使用外部 MCP 工具 */
    default boolean allowExternalTools() { return false; }

    /**
     * 系统指令片段。
     *
     * <p><b>J2 起本接口不承载 Prompt 正文</b>：正文已迁移到 Python PromptPack 的
     * {@code scenario.&lt;code&gt;.instruction}，由 {@link AgentScenarioRegistry} 在注册时用
     * PromptPack 文本包装每个场景（见 {@link #instruction()} 的实现方）。实现类<b>不得</b>再内嵌
     * Prompt 正文；默认实现直接抛错而不是返回空串——Prompt 缺失必须快速失败，不能静默降级成
     * 一段没有约束的指令。</p>
     */
    default String instruction() {
        throw new IllegalStateException(
                "Scenario 不再内嵌 Prompt 正文，请通过 AgentScenarioRegistry 取得场景: " + code());
    }

    /** 输出要求描述；与 {@link #instruction()} 同一口径，正文来自 PromptPack。 */
    default String outputContract() {
        throw new IllegalStateException(
                "Scenario 不再内嵌 Prompt 正文，请通过 AgentScenarioRegistry 取得场景: " + code());
    }

    /** 场景类别；写工作流（WORKFLOW）必须由用户显式选择进入。 */
    default AgentScenarioCategory category() { return AgentScenarioCategory.SCENARIO; }

    /**
     * 是否为受限子运行视图。子研究运行由协调器构造并显式标记，
     * 调用方不得通过 code 字符串猜测该身份。
     */
    default boolean childScope() { return false; }

    /**
     * 达成当前目标必需的核心业务动作工具（如规划生成的 start_task_plan）。
     * 空 Set 表示本 Scenario 没有必需动作（普通只读/问答）。
     * 核心动作是否被要求由运行目标确定性判定；工具仍须满足权限、参数、
     * 预算、版本与业务校验，本声明不放开任何写路径限制。
     */
    default Set<String> coreActionTools() { return Set.of(); }
}
