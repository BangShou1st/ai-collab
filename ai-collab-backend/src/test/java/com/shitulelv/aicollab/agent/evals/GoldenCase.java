package com.shitulelv.aicollab.agent.evals;

import java.util.List;
import java.util.Map;

/**
 * 评估 golden case 的领域模型（纯数据，由 {@link GoldenCaseLoader} 从 JSON 装配并校验）。
 *
 * <p>一个 golden case = 场景输入（用户请求 + 项目上下文 seed）+ 脚本化模型响应 + 可机械化
 * 判定的期望断言。断言只依赖持久化事实（{@code agent_run_event} 事件流、DB 副作用、
 * invocation 状态），不依赖人工判断——评估结果可复现、可对比。</p>
 *
 * <p>六大类目见 {@link Category}；{@code guards} 字段写明该 case 守护的防护机制，
 * 是评估集作为回归防线的说明书。</p>
 */
public final class GoldenCase {

    /** case 类目（六大类）。 */
    public enum Category {
        /** 任务写操作（审批流：提案→批准/拒绝→落库或不落库）。 */
        TASK_WRITE_APPROVAL,
        /** 任务查询类只读工具。 */
        TASK_QUERY,
        /** 委派研究（含 P0 判重）。 */
        DELEGATION_RESEARCH,
        /** 预算收敛（软阈值提示、硬顶终止、部分回答）。 */
        BUDGET_CONVERGENCE,
        /** 循环防护（AgentLoopGuard）。 */
        LOOP_GUARD,
        /** 记忆写入审批。 */
        MEMORY_WRITE_APPROVAL
    }

    /** 脚本化模型的一个轮次：文本 + 工具调用。finishReason 由 toolCalls 是否为空推导。 */
    public record ScriptTurn(String content, List<ScriptToolCall> toolCalls) {
        public record ScriptToolCall(String name, Map<String, Object> arguments) {}
    }

    /** 脚本：根运行轮次序列、委派子运行轮次序列、推进上限。 */
    public record Script(List<ScriptTurn> rootTurns, List<ScriptTurn> childTurns, int maxAdvances) {}

    /** 项目上下文 seed：预置任务行。 */
    public record Seed(List<SeedTask> tasks) {
        public record SeedTask(String title, String status) {}
    }

    /** 预算相关覆盖：软顶与预置的真实累计输入。 */
    public record Budget(Long softInputTokenCap, Long seedInputTokensActual) {}

    /** 运行行覆盖：预算策略版本、输入上限、推进步耗到只剩 1。 */
    public record RunOverrides(Integer contextPolicyVersion, Integer maxInputTokens,
            Long seedInputTokensActual, Boolean useAllButOneStep) {}

    /** 预置 agent_step 行（用于证据兜底类 case）。 */
    public record SeedStep(String type, String toolName, Map<String, Object> output, String reason) {}

    /** 运行结束后的模拟人工动作：批准或拒绝提案。 */
    public record PostRun(String action, String reason) {}

    /** 事件断言：类型计数 + 可选的载荷包含校验。 */
    public record EventAssertion(String type, Integer min, Integer max, String payloadContains) {}

    /** 工具调用断言：按工具名统计指定状态（agent_tool_invocation.status 列）的行数。 */
    public record ToolInvocationAssertion(String tool, String status, Integer min, Integer max) {}

    /** DB 副作用断言：probe 是运行器支持的受限判定词表，禁止任意 SQL。 */
    public record DbAssertion(String name, String probe, String expected, Integer min, Integer max) {}

    /** case 级工具行为覆盖（如让 list_tasks 失败）。 */
    public record ToolBehavior(Map<String, String> listTasks) {}

    /** 断言集合：全部可从事件流 + DB + 捕获的模型请求机械化判定。 */
    public record Assertions(String finalStatus, List<EventAssertion> events,
            List<ToolInvocationAssertion> toolInvocations, List<DbAssertion> db,
            List<String> modelRequestContains, List<String> answerContains,
            List<String> forbiddenEvents, List<String> forbiddenToolExecuted,
            List<String> forbiddenErrorCodes) {}

    private final String id;
    private final Category category;
    private final String title;
    private final String guards;
    private final String skillCode;
    private final String userRequest;
    private final Seed seed;
    private final ToolBehavior toolBehavior;
    private final Budget budget;
    private final RunOverrides runOverrides;
    private final List<SeedStep> seedSteps;
    private final Script script;
    private final PostRun postRun;
    private final Assertions assertions;

    public GoldenCase(String id, Category category, String title, String guards, String skillCode,
            String userRequest, Seed seed, ToolBehavior toolBehavior, Budget budget,
            RunOverrides runOverrides, List<SeedStep> seedSteps, Script script,
            PostRun postRun, Assertions assertions) {
        this.id = id;
        this.category = category;
        this.title = title;
        this.guards = guards;
        this.skillCode = skillCode;
        this.userRequest = userRequest;
        this.seed = seed;
        this.toolBehavior = toolBehavior;
        this.budget = budget;
        this.runOverrides = runOverrides;
        this.seedSteps = seedSteps;
        this.script = script;
        this.postRun = postRun;
        this.assertions = assertions;
    }

    public String id() { return id; }
    public Category category() { return category; }
    public String title() { return title; }
    public String guards() { return guards; }
    public String skillCode() { return skillCode; }
    public String userRequest() { return userRequest; }
    public Seed seed() { return seed; }
    public ToolBehavior toolBehavior() { return toolBehavior; }
    public Budget budget() { return budget; }
    public RunOverrides runOverrides() { return runOverrides; }
    public List<SeedStep> seedSteps() { return seedSteps; }
    public Script script() { return script; }
    public PostRun postRun() { return postRun; }
    public Assertions assertions() { return assertions; }
}
