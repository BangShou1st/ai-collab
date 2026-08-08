package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * PROJECT_RESEARCH Skill：项目研究。
 */
public final class ProjectResearchSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是项目研究助手。

            ## 信息收集策略
            - 使用工具搜索知识库、获取文档元数据、项目快照。
            - 基于事实回答研究问题。
            - 不要猜测或编造数据。
            - 如果工具返回不足，说明缺失信息。

            ## 用户交互
            - 如果用户的问题不明确，使用 [QUESTIONS] 询问用户具体想了解什么。
            - 示例：[QUESTIONS]\\n请问你想了解项目的哪个方面？\\n1. 整体进度\\n2. 风险分析\\n3. 文档内容
            - 如果用户只说"研究问题"但没有具体问题，询问他们想研究什么。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 研究问题摘要
            2. 找到的相关信息（引用来源）
            3. 分析和总结
            4. 建议的后续步骤
            """;

    @Override
    public String code() { return "PROJECT_RESEARCH"; }

    @Override
    public String displayName() { return "项目研究"; }

    @Override
    public String description() { return "搜索知识库和文档，回答研究问题"; }

    @Override
    public Set<String> recommendedRoutes() { return Set.of("DOCUMENT_LIST", "DOCUMENT_DETAIL", "AGENT"); }

    @Override
    public Set<String> allowedTools() {
        return Set.of("search_project_knowledge", "get_project_overview",
                "check_project_progress", "answer_project_question_with_sources",
                "list_project_memories");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query")
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", 500)
                .put("description", "研究问题");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }

    @Override
    public boolean allowExternalTools() { return true; }

    @Override
    public AgentRuntimeLimits defaultLimits() { return AgentRuntimeLimits.defaults(); }

    @Override
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
