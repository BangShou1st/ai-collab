package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/**
 * 会话工作状态（agent_session.working_state JSONB）的结构化维护。
 *
 * <p>v2（schemaVersion=2）：区分持续约束（active/superseded + 作用域 + 来源消息）、
 * 本轮表达要求（不跨轮）、明确新目标（历史保留）；普通提问与寒暄不进 constraints。
 * 所有写入统一递增 stateRevision，供摘要 CAS 与并发校验使用。</p>
 *
 * <p>确定性规则优先：只有能明确归类的消息才进入约束；不确定时保留原始消息，
 * 不强行归类、不默默覆盖旧约束。旧格式（无 schemaVersion）在写入时渐进升级，
 * 读取端（Composer 渲染）同时兼容两种格式。</p>
 */
final class AgentWorkingState {
    static final int SCHEMA_VERSION = 2;
    private static final int MAX_CONSTRAINT_ENTRIES = 20;
    private static final int MAX_GOAL_HISTORY = 5;

    /** 明确的新目标标记（保守词表；不含模糊表述，避免文档引文/否定句误触发）。 */
    private static final String[] GOAL_REPLACE_PREFIXES = {"/replace ", "新目标：", "新目标:"};

    /** 约束作用域：仅收录能确定性识别的持续约束；数量上下界分开，可同时成立。 */
    private static final String SCOPE_TASK_COUNT_MAX = "TASK_COUNT_MAX";
    private static final String SCOPE_TASK_COUNT_MIN = "TASK_COUNT_MIN";
    private static final String SCOPE_DATE_LOCK = "DATE_LOCK";
    private static final String SCOPE_ASSIGNEE_LOCK = "ASSIGNEE_LOCK";

    /** 用户提交新请求。messageId 为刚入库的 USER 消息真实 ID，同事务内关联来源。 */
    static void appendUser(JdbcTemplate jdbc, ObjectMapper json, UUID session, String request, UUID messageId) {
        ObjectNode state=load(jdbc,json,session);
        upgrade(json,state);
        if (isGoalReplacement(request)) {
            replaceGoal(json,state,request);
        } else {
            ensureActiveGoal(state,request);
            classifyAndMerge(json,state,request,messageId);
        }
        replaceTurnRequirements(json,state,request);
        state.put("latestRequest",bounded(request,2000));
        state.put("goalVersion",state.path("goalVersion").asInt(0)+1);
        if (messageId!=null) state.put("lastProcessedMessageId",messageId.toString());
        state.remove("pendingQuestion");
        bumpRevision(state);
        save(jdbc,session,state);
    }

    static void question(JdbcTemplate jdbc,ObjectMapper json,UUID session,String question) {
        ObjectNode state=load(jdbc,json,session);
        upgrade(json,state);
        state.put("pendingQuestion",bounded(question,2000));
        bumpRevision(state);
        save(jdbc,session,state);
    }

    private static ObjectNode load(JdbcTemplate jdbc,ObjectMapper json,UUID session) {
        String value=jdbc.queryForObject("SELECT working_state::text FROM agent_session WHERE id=? FOR UPDATE",String.class,session);
        try { return (ObjectNode)json.readTree(value); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static void save(JdbcTemplate jdbc,UUID session,ObjectNode state) { jdbc.update("UPDATE agent_session SET working_state=?::jsonb WHERE id=?",state.toString(),session); }

    private static String bounded(String value,int length) { return value.length()<=length ? value : value.substring(0,length); }

    /** 所有写入路径统一维护的 JSONB 内状态版本。 */
    private static void bumpRevision(ObjectNode state) { state.put("stateRevision",state.path("stateRevision").asInt(0)+1); }

    /** v1 → v2 渐进升级：仅写入时转换，读取端另行兼容，不批量改写历史数据。 */
    private static void upgrade(ObjectMapper json,ObjectNode state) {
        if (state.path("schemaVersion").asInt(0)>=SCHEMA_VERSION) return;
        ObjectNode upgraded=json.createObjectNode();
        upgraded.put("schemaVersion",SCHEMA_VERSION);
        upgraded.put("stateRevision",state.path("stateRevision").asInt(0));
        String goal=state.path("activeGoal").asText(state.path("goal").asText(null));
        if (goal!=null) upgraded.put("activeGoal",goal);
        upgraded.put("goalRevision",state.path("goalRevision").asInt(0));
        upgraded.put("goalVersion",state.path("goalVersion").asInt(0));
        ArrayNode constraints=upgraded.putArray("constraints");
        JsonNode previous=state.path("constraints");
        for (JsonNode entry : previous) {
            ObjectNode node=constraints.addObject();
            node.put("id",UUID.randomUUID().toString());
            if (entry.isTextual()) {
                node.put("value",bounded(entry.asText(),1000));
            } else {
                node.setAll((ObjectNode) entry);
                if (!node.hasNonNull("id")) node.put("id",UUID.randomUUID().toString());
            }
            node.put("scope","LEGACY");
            node.put("status","active");
        }
        if (state.hasNonNull("latestRequest")) upgraded.set("latestRequest",state.path("latestRequest").deepCopy());
        if (state.hasNonNull("pendingQuestion")) upgraded.set("pendingQuestion",state.path("pendingQuestion").deepCopy());
        if (state.hasNonNull("lastProcessedMessageId")) upgraded.set("lastProcessedMessageId",state.path("lastProcessedMessageId").deepCopy());
        state.removeAll();
        state.setAll(upgraded);
    }

    static boolean isGoalReplacement(String request) {
        if (request==null) return false;
        for (String prefix : GOAL_REPLACE_PREFIXES) {
            if (request.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 明确新目标：旧目标与约束历史保留，active 约束批量标记 superseded（不删除）。 */
    private static void replaceGoal(ObjectMapper json,ObjectNode state,String request) {
        String oldGoal=state.path("activeGoal").asText(state.path("goal").asText(null));
        if (oldGoal!=null && !oldGoal.isBlank()) {
            ArrayNode history=json.createArrayNode();
            JsonNode previous=state.path("goalHistory");
            for (int i=Math.max(0,previous.size()-(MAX_GOAL_HISTORY-1)); i<previous.size(); i++) history.add(previous.get(i));
            ObjectNode entry=history.addObject();
            entry.put("goal",bounded(oldGoal,2000));
            entry.put("supersededByRequest",bounded(request,2000));
            state.set("goalHistory",history);
        }
        supersedeAll(state);
        state.put("activeGoal",bounded(request,2000));
        if (!state.hasNonNull("goal")) state.put("goal",bounded(request,2000));
        state.put("goalRevision",state.path("goalRevision").asInt(0)+1);
    }

    private static void supersedeAll(ObjectNode state) {
        JsonNode constraints=state.path("constraints");
        for (JsonNode entry : constraints) {
            if ("active".equals(entry.path("status").asText())) {
                ((ObjectNode) entry).put("status","superseded");
                ((ObjectNode) entry).put("supersededReason","GOAL_REPLACED");
            }
        }
    }

    private static void ensureActiveGoal(ObjectNode state,String request) {
        if (!state.hasNonNull("activeGoal")) {
            String goal=state.path("goal").asText(request);
            state.put("activeGoal",bounded(goal,2000));
            if (!state.hasNonNull("goal")) state.put("goal",bounded(goal,2000));
        }
    }

    /**
     * 约束归类与合并：能确定性识别的持续约束按作用域合并（同作用域新值替代旧值）；
     * 识别不了的普通消息不进 constraints，原始内容保留在 latestRequest 与消息表中。
     * 一条消息可同时携带多条约束（如“最多十项，不改日期”），按作用域分别落条目。
     */
    private static void classifyAndMerge(ObjectMapper json,ObjectNode state,String request,UUID messageId) {
        for (String scope : constraintScopes(request)) {
            mergeConstraint(json,state,request,messageId,scope);
        }
    }

    private static void mergeConstraint(ObjectMapper json,ObjectNode state,String request,UUID messageId,String scope) {
        ArrayNode constraints=constraints(state,json);
        String newId=UUID.randomUUID().toString();
        // 仅数量上下界允许同作用域替代（同一限制被修改，如 最多十项 → 最多八项）；
        // 日期/负责人等保护类约束无法确定性区分对象（任务 A/任务 B），
        // 一律并存，不因限制类型相同而互相覆盖
        boolean countScope = SCOPE_TASK_COUNT_MAX.equals(scope) || SCOPE_TASK_COUNT_MIN.equals(scope);
        for (JsonNode entry : constraints) {
            if (!"active".equals(entry.path("status").asText())) continue;
            if (scope.equals(entry.path("scope").asText()) && entry.path("value").asText("").equals(request)) {
                return; // 完全相同的同作用域约束不重复累积
            }
            if (countScope && scope.equals(entry.path("scope").asText())) {
                ((ObjectNode) entry).put("status","superseded");
                ((ObjectNode) entry).put("supersededBy",newId);
                ((ObjectNode) entry).put("supersededReason","SCOPE_UPDATED");
            }
        }
        ObjectNode created=constraints.addObject();
        created.put("id",newId);
        created.put("value",bounded(request,1000));
        if (messageId!=null) created.put("sourceMessageId",messageId.toString());
        created.put("status","active");
        created.put("scope",scope);
        trimConstraints(json,state);
    }

    /** 只保留确定性可识别的持续约束；其余表述（含格式/范围要求）归入本轮表达要求。
     *  数量限制区分上界/下界，二者可同时成立、互不替代。 */
    private static java.util.List<String> constraintScopes(String request) {
        String text=request==null ? "" : request;
        java.util.List<String> scopes=new java.util.ArrayList<>();
        if (text.matches(".*(最多|不超过|至多|只能)[^，。;；\\n]{0,8}[0-9一二三四五六七八九十]+[项条个].*")) {
            scopes.add(SCOPE_TASK_COUNT_MAX);
        }
        if (text.matches(".*(至少|最少)[^，。;；\\n]{0,8}[0-9一二三四五六七八九十]+[项条个].*")) {
            scopes.add(SCOPE_TASK_COUNT_MIN);
        }
        if (text.contains("日期") && text.matches(".*(日期[^。；\\n]{0,6}(不要|不用|别|不能|禁止|保持|固定|不变|别动|不改)|不要改日期|别改日期|不改日期|日期不变|日期保持).*")) {
            scopes.add(SCOPE_DATE_LOCK);
        }
        if (text.contains("负责人") && text.matches(".*(负责人不要|负责人别|负责人不能|不改负责人|别改负责人|不要改负责人|负责人保持|负责人固定).*")) {
            scopes.add(SCOPE_ASSIGNEE_LOCK);
        }
        return scopes;
    }

    /** 本轮表达要求：每次请求重算，不跨轮累积（如“只回答三个字段”“改成表格”）。 */
    private static void replaceTurnRequirements(ObjectMapper json,ObjectNode state,String request) {
        ArrayNode requirements=json.createArrayNode();
        String text=request==null ? "" : request;
        if (text.matches(".*(只(回答|显示|输出|返回|列出|包含)|只看|只需要).*")) {
            requirements.add(bounded(text,200));
        } else if (text.matches(".*(改成|改为|换成|用)（?(表格|列表|清单|分点|要点).*")) {
            requirements.add(bounded(text,200));
        }
        state.set("turnRequirements",requirements);
    }

    private static ArrayNode constraints(ObjectNode state,ObjectMapper json) {
        JsonNode constraints=state.path("constraints");
        if (constraints instanceof ArrayNode array) return array;
        ArrayNode created=json.createArrayNode();
        state.set("constraints",created);
        return created;
    }

    private static void trimConstraints(ObjectMapper json,ObjectNode state) {
        JsonNode constraints=state.path("constraints");
        if (constraints.size()<=MAX_CONSTRAINT_ENTRIES) return;
        // 超限时只归档最旧的 superseded 历史条目；active 约束永不淘汰——
        // 历史条目可归档，当前仍有效的要求必须完整保留
        ArrayNode trimmed=json.createArrayNode();
        for (int i=0; i<constraints.size(); i++) {
            JsonNode entry=constraints.get(i);
            boolean active="active".equals(entry.path("status").asText());
            boolean oldestSuperseded=i<constraints.size()-MAX_CONSTRAINT_ENTRIES;
            if (!active && oldestSuperseded) continue;
            trimmed.add(entry);
        }
        state.set("constraints",trimmed);
    }
}
