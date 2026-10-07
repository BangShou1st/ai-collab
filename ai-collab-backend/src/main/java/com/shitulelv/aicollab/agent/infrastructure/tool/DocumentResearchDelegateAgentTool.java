package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * document_research 只读委派工具（V62）。
 *
 * <p>主 Agent 在需要多次检索/正文对照时把资料研究交给只读子 Agent：
 * 本工具受理委派（创建 depth=1 的 KNOWLEDGE_RESEARCHER 子运行）后立即返回，
 * 主运行重新排队；子运行复用现有持久执行引擎，终态经既有 resumeParent 链唤醒父运行，
 * 父运行从 DELEGATION_COMPLETED step 读取子运行发现并综合最终回答。</p>
 *
 * <p>边界：最多一层（子运行 depth=1 不能再委派，AgentRepository 层校验）；
 * 子工具白名单只含受限只读集合；不新增业务写路径、审批能力或规划受理；
 * 普通查询不应委派——直接使用 search_project_knowledge 等工具成本更低。</p>
 */
@Component
public class DocumentResearchDelegateAgentTool implements AgentTool {
    public static final String NAME = "delegate_document_research";
    /** 子运行的受限只读白名单：文档目录/提纲/正文、知识检索；不含任务/成员/审批/规划。 */
    public static final Set<String> CHILD_ALLOWED_TOOLS = Set.of(
            "list_project_documents", "get_document_outline", "read_document_section",
            "search_project_knowledge", "answer_project_question_with_sources");

    private final ObjectMapper json;

    public DocumentResearchDelegateAgentTool(ObjectMapper json) {
        this.json = json;
    }

    @Override public String name() { return NAME; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "把需要多次检索与正文对照的文档研究任务委派给只读研究子 Agent；"
                        + "受理后本运行暂停等待，子 Agent 完成后其发现、来源与覆盖缺口会返回给你，由你综合回答。"
                        + "只在确需多轮资料研究时使用：简单检索直接用 search_project_knowledge，单个任务事实用 list_tasks/get_task。",
                """
                {"type":"object","additionalProperties":false,"required":["objective"],
                 "properties":{"objective":{"type":"string","minLength":10,"maxLength":2000,
                   "description":"研究任务的完整自包含描述：要查证什么问题、需要哪些来源、怎样算完成；子 Agent 看不到与用户的对话历史"}}}
                """,
                false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("objective"));
        String objective = AgentToolArguments.text(arguments, "objective", 2000, true);
        if (context.invocationId() == null) {
            throw new IllegalArgumentException("委派调用缺少 invocation 身份");
        }
        // 防御边界（R4）：受理只经 AgentToolCallExecutor.executeDelegation → repository
        // 的单一持久化事务完成。普通批次执行到达这里说明委派已被拒绝（如混合批次），
        // 必须如实拒绝，不得制造"没有创建子运行的成功 DELEGATED 回执"。
        throw new IllegalStateException(
                "委派受理只能由运行执行器的委派分派路径完成；本次调用未获受理（如混合批次被拒绝），不产生成功回执");
    }
}
