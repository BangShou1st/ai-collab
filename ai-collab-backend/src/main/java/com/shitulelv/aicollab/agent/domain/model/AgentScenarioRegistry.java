package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.builtin.*;
import com.shitulelv.aicollab.common.ai.prompt.PromptCatalog;
import com.shitulelv.aicollab.common.ai.prompt.PromptCatalogs;
import com.shitulelv.aicollab.common.ai.prompt.PromptKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定 Scenario 注册表。由后端固定注册，不允许用户动态安装。
 * <p>
 * 选择顺序（确定性）：
 * 1. 用户显式 scenario code（不区分大小写）
 * 2. 页面 route 的固定映射（TASK / MILESTONE / PLANNING → ITERATION_PLANNING，DASHBOARD → PROJECT_HEALTH）
 * 3. 固定兜底 Scenario（PROJECT_RESEARCH，DOCUMENT 等未映射 route 也落到这里）
 * <p>
 * 不根据 goal 关键词做隐式路由：写工作流（MEETING_TO_TASKS / ITERATION_PLANNING）
 * 必须由用户显式选择，否则只能得到只读兜底场景。
 * <p>
 * <b>J2 起 Prompt 正文来自 PromptPack</b>：每个内置 Scenario 在注册时被
 * {@link PromptBackedScenario} 包装，{@code instruction()} / {@code outputContract()} 从
 * {@code scenario.&lt;code&gt;.instruction} / {@code scenario.&lt;code&gt;.output_contract} 取用。
 * 包装发生在注册处，因此任何经本注册表拿到的 Scenario 都自带 PromptPack 文本；
 * 内置类本身不再内嵌 Prompt 正文（{@link AgentScenario#instruction()} 的默认实现直接抛错，
 * 避免绕过注册表时静默发出空指令）。
 */
@Component
public final class AgentScenarioRegistry {

    private final Map<String, AgentScenario> scenarios;

    /**
     * 生产装配：由 Spring 注入唯一的 {@link PromptCatalog}（远端 worker 或内置 fallback，
     * 取决于 {@code prompt.registry.enabled}）。
     */
    @Autowired
    public AgentScenarioRegistry(PromptCatalog prompts) {
        Map<String, AgentScenario> indexed = new LinkedHashMap<>();
        register(new PromptBackedScenario(new ProjectHealthScenario(), prompts), indexed);
        register(new PromptBackedScenario(new WeeklyReportScenario(), prompts), indexed);
        register(new PromptBackedScenario(new MeetingToTasksScenario(), prompts), indexed);
        register(new PromptBackedScenario(new IterationPlanningScenario(), prompts), indexed);
        register(new PromptBackedScenario(new DeliveryReadinessScenario(), prompts), indexed);
        register(new PromptBackedScenario(new ProjectResearchScenario(), prompts), indexed);
        this.scenarios = Map.copyOf(indexed);
    }

    /**
     * 无容器场景（单元测试、eval 工具）：使用内置只读 fallback 包，零外呼。
     *
     * <p>注意：这是<b>测试/离线工具</b>入口，生产装配永远走 {@link #AgentScenarioRegistry(PromptCatalog)}。
     * 两个构造器同时存在时 Spring 只认带 {@code @Autowired} 的那个，因此不会被误用。</p>
     */
    public AgentScenarioRegistry() {
        this(PromptCatalogs.bundled());
    }

    private static void register(AgentScenario scenario, Map<String, AgentScenario> map) {
        if (map.putIfAbsent(scenario.code(), scenario) != null) {
            throw new IllegalArgumentException("Scenario code 重复: " + scenario.code());
        }
    }

    /**
     * 获取指定 Scenario，不存在时抛出异常。
     */
    public AgentScenario require(String code) {
        AgentScenario scenario = scenarios.get(code);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario 不存在: " + code);
        }
        return scenario;
    }

    /**
     * 确定性选择 Scenario。
     * 1. 显式 code 优先（不区分大小写）；
     * 2. route 固定映射（根据页面上下文选择最可能需要的只读 Scenario）；
     * 3. 固定兜底 PROJECT_RESEARCH。
     * <p>
     * 不做 goal 关键词匹配：隐式路由已移除，写工作流必须显式选择。
     */
    public AgentScenario select(String explicitCode, AgentPageContext page) {
        // 1. 显式 code
        if (explicitCode != null && !explicitCode.isBlank()) {
            return require(explicitCode.trim().toUpperCase());
        }

        // 2. route 固定映射：只保留确定性映射，不根据 goal 文本推断
        if (page != null && page.route() != null) {
            String route = page.route().toUpperCase();
            if (route.contains("TASK")) return require("ITERATION_PLANNING");
            if (route.contains("MILESTONE")) return require("ITERATION_PLANNING");
            if (route.contains("PLANNING")) return require("ITERATION_PLANNING");
            if (route.contains("DASHBOARD")) return require("PROJECT_HEALTH");
        }

        // 3. 最终兜底（DOCUMENT 等未映射 route 也走这里）
        return require("PROJECT_RESEARCH");
    }

    /**
     * 列出所有可用 Scenario。
     */
    public List<AgentScenario> listAll() {
        return List.copyOf(scenarios.values());
    }

    /**
     * 检查 Scenario 是否存在。
     */
    public boolean exists(String code) {
        return scenarios.containsKey(code);
    }

    /**
     * PromptPack 支撑的场景视图：除两个 Prompt 方法外全部委托给内置实现。
     *
     * <p>Prompt 取用在<b>调用时</b>发生（不是在注册时预取），因此 Prompt 不可用会在真正组请求时
     * 以 {@code AI_PROMPT_UNAVAILABLE} 快速失败，而不是把注册表构造搞崩或缓存一份旧文本。</p>
     */
    private record PromptBackedScenario(AgentScenario delegate, PromptCatalog prompts)
            implements AgentScenario {

        @Override public String code() { return delegate.code(); }
        @Override public String displayName() { return delegate.displayName(); }
        @Override public String description() { return delegate.description(); }
        @Override public Set<String> entryRoutes() { return delegate.entryRoutes(); }
        @Override public Set<String> grantedTools() { return delegate.grantedTools(); }
        @Override public JsonNode inputSchema() { return delegate.inputSchema(); }
        @Override public boolean allowWriteTools() { return delegate.allowWriteTools(); }
        @Override public boolean allowExternalTools() { return delegate.allowExternalTools(); }
        @Override public AgentScenarioCategory category() { return delegate.category(); }
        @Override public boolean childScope() { return delegate.childScope(); }
        @Override public Set<String> coreActionTools() { return delegate.coreActionTools(); }

        @Override public String instruction() {
            return prompts.require(PromptKeys.scenarioInstruction(delegate.code())).text();
        }

        @Override public String outputContract() {
            return prompts.require(PromptKeys.scenarioOutputContract(delegate.code())).text();
        }
    }
}
