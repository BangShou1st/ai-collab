package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import org.springframework.stereotype.Component;

@Component
public class ProjectQuestionAgentTool implements AgentTool {
    private final KnowledgeSearchAgentTool search;

    public ProjectQuestionAgentTool(KnowledgeSearchAgentTool search) {
        this.search = search;
    }

    @Override public String name() { return "answer_project_question_with_sources"; }
    @Override public boolean writesBusinessData() { return false; }

    /**
     * search_project_knowledge 的兼容别名：参数与返回完全一致（历史调用名保留，
     * 不再另开问答模型）。新对话建议直接使用 search_project_knowledge。
     */
    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "带来源回答项目资料问题的兼容别名：参数与 search_project_knowledge 完全一致，"
                        + "返回相关摘录与来源身份；不启动独立问答模型。",
                """
                {"type":"object","additionalProperties":false,"required":["query"],
                 "properties":{
                   "query":{"type":"string","minLength":1,"maxLength":1000,"description":"检索问题或关键词"},
                   "documentIds":{"type":"array","maxItems":20,"uniqueItems":true,
                     "items":{"type":"string","format":"uuid"},
                     "description":"可选：只在指定文档内检索；不填则在全部已索引文档中检索"},
                   "limit":{"type":"integer","minimum":1,"maximum":12,"default":8,
                     "description":"返回的摘录条数上限，默认 8"}}}
                """,
                false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        return search.execute(context, arguments);
    }
}
