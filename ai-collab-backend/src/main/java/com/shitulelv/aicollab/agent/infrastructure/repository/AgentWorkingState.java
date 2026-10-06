package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话工作状态（agent_session.working_state JSONB）的结构化维护。
 *
 * <p>v2（schemaVersion=2）：区分持续约束（active/superseded + 作用域 + 来源消息）、
 * 本轮表达要求（不跨轮）、明确新目标（历史保留）；普通提问与寒暄不进 constraints。
 * 所有写入统一递增 stateRevision，供摘要 CAS 与并发校验使用。</p>
 *
 * <p>约束条目按作用域拆成独立事实：value 是只表达本作用域的规范化短值（如"最多6项"），
 * detail 是结构化数值/日期/对象，quote 保留原文片段供追溯；
 * 不再把整段请求存进每个作用域——旧要求不会经由其他条目串回当前约束。
 * 明确新目标（"新目标："）只替换目标本身，本消息中明确给出的约束仍会提取登记，
 * 归属新目标修订并绑定真实 sourceMessageId。</p>
 *
 * <p>确定性规则优先：疑问、否定或多处冲突数值的表述保守保留原文并标记
 * needsClarification，不编造结构化事实；"沿用此前日期/负责人"仅在能唯一定位
 * 既有依据时复用。旧格式（无 schemaVersion）与旧版"整段请求当 value"的条目
 * 在写入时渐进升级，读取端（Composer 渲染）同时兼容两种格式。</p>
 */
final class AgentWorkingState {
    static final int SCHEMA_VERSION = 2;
    private static final int MAX_CONSTRAINT_ENTRIES = 20;
    private static final int MAX_GOAL_HISTORY = 5;

    /** 明确的新目标标记（保守词表；不含模糊表述，避免文档引文/否定句误触发）。 */
    private static final String[] GOAL_REPLACE_PREFIXES = {"/replace ", "新目标：", "新目标:"};

    /** 约束作用域：仅收录能确定性识别的持续约束；数量上下界分开，可同时成立。 */
    static final String SCOPE_TASK_COUNT_MAX = "TASK_COUNT_MAX";
    static final String SCOPE_TASK_COUNT_MIN = "TASK_COUNT_MIN";
    static final String SCOPE_DATE_LOCK = "DATE_LOCK";
    static final String SCOPE_ASSIGNEE_LOCK = "ASSIGNEE_LOCK";
    private static final String SCOPE_LEGACY = "LEGACY";

    /** 通用主体词，不作为可区分的保护对象（"规划日期"的"规划"不是对象）。 */
    private static final java.util.Set<String> GENERIC_SUBJECTS = java.util.Set.of(
            "规划", "项目", "里程碑", "任务", "所有", "全部", "当前", "这些", "那些", "整体", "上述", "本次");

    /** 用户提交新请求。messageId 为刚入库的 USER 消息真实 ID，同事务内关联来源。 */
    static void appendUser(JdbcTemplate jdbc, ObjectMapper json, UUID session, String request, UUID messageId) {
        ObjectNode state=load(jdbc,json,session);
        upgrade(json,state);
        repairLegacyV2Entries(state);
        // 显式更正与旧状态的优先级：不解析更正语义、不改写 activeGoal，
        // 只把更正事实登记下来，由读取端（Composer/摘要）声明"以更正为准"
        recordGoalCorrection(state,request,messageId);
        if (isGoalReplacement(request)) {
            replaceGoal(json,state,request);
            // 新目标生效后，本消息明确给出的约束仍要提取登记：
            // 只 replaceGoal 会撤销旧约束却不登记新约束（如"最多6项，日期不改"）
            classifyAndMerge(json,state,request,messageId);
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

    /**
     * 旧版 v2 条目的渐进修复：早期实现把整段请求存进每个作用域的 value，
     * 导致"日期条目携带数量/阶段要求"串值。写入时按原文重新规范化拆分；
     * 无法确定性重提取的保守保留原值并标记 legacy，不静默丢弃、不要求清空历史。
     */
    private static void repairLegacyV2Entries(ObjectNode state) {
        JsonNode constraints=state.path("constraints");
        if (!constraints.isArray()) return;
        for (JsonNode entry : constraints) {
            if (!entry.isObject() || entry.has("detail") || entry.has("legacy")) continue;
            String scope=entry.path("scope").asText("");
            String raw=entry.path("value").asText("");
            if (raw.isBlank() || SCOPE_LEGACY.equals(scope)) continue;
            ObjectNode normalized=jsonNode();
            switch (scope) {
                case SCOPE_TASK_COUNT_MAX -> { CountFact fact=countFact(raw,true); if (fact!=null) fact.applyTo(normalized); else ((ObjectNode) entry).put("legacy",true); }
                case SCOPE_TASK_COUNT_MIN -> { CountFact fact=countFact(raw,false); if (fact!=null) fact.applyTo(normalized); else ((ObjectNode) entry).put("legacy",true); }
                case SCOPE_DATE_LOCK -> { DateFact fact=dateFact(raw,null); if (fact!=null) fact.applyTo(normalized); else ((ObjectNode) entry).put("legacy",true); }
                case SCOPE_ASSIGNEE_LOCK -> { AssigneeFact fact=assigneeFact(raw,null); if (fact!=null) fact.applyTo(normalized); else ((ObjectNode) entry).put("legacy",true); }
                default -> { }
            }
            if (normalized.has("value")) {
                ((ObjectNode) entry).put("value",normalized.path("value").asText());
                if (normalized.has("detail")) ((ObjectNode) entry).set("detail",normalized.path("detail"));
                // quote 是来源消息中的原文证据；重提取只能从 value 再生（丢失来源语境），
                // 已有 quote 不覆盖，否则溯源信息每轮被改写（真实案例：分析请求误触发
                // DATE_LOCK 后，quote 从"列出…截止日期…"被改写成"不改日期"）
                if (normalized.has("quote") && !entry.hasNonNull("quote")) ((ObjectNode) entry).put("quote",normalized.path("quote").asText());
            }
            // 旧版对象只存在于 value 前缀 "[X] "：渐进补进 detail.object，
            // 让按对象的替代/复用规则同样保护历史条目
            if ((SCOPE_DATE_LOCK.equals(scope) || SCOPE_ASSIGNEE_LOCK.equals(scope))
                    && entry.path("detail").isObject()
                    && entry.path("detail").path("object").asText("").isEmpty()) {
                var objectMatcher=OBJECT_PREFIX.matcher(entry.path("value").asText());
                if (objectMatcher.find()) ((ObjectNode) entry.path("detail")).put("object",objectMatcher.group(1).trim());
            }
        }
    }

    static boolean isGoalReplacement(String request) {
        if (request==null) return false;
        for (String prefix : GOAL_REPLACE_PREFIXES) {
            if (request.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 显式更正记录上限：只保留最近几条，超限淘汰最旧。 */
    private static final int MAX_GOAL_CORRECTIONS = 5;

    /**
     * 用户显式更正登记（保守词表，子句级匹配）：
     * "更正…"/"修正…"开头的子句，或"更正：/修正:"冒号形式。更正不解析语义、
     * 不自动改写 activeGoal（新目标仍走显式"新目标："分支），只记录事实供
     * 读取端声明"旧状态与更正冲突时以更正为准"，避免旧目标被当成最新口径。
     */
    private static void recordGoalCorrection(ObjectNode state,String request,UUID messageId) {
        String marker=firstCorrectionClause(request);
        if (marker==null) return;
        ArrayNode corrections;
        JsonNode previous=state.path("goalCorrections");
        if (previous instanceof ArrayNode array) corrections=array;
        else { corrections=state.putArray("goalCorrections"); }
        ObjectNode entry=corrections.addObject();
        entry.put("quote",bounded(marker,200));
        if (messageId!=null) entry.put("sourceMessageId",messageId.toString());
        while (corrections.size()>MAX_GOAL_CORRECTIONS) corrections.remove(0);
    }

    private static String firstCorrectionClause(String request) {
        if (request==null) return null;
        for (String clause : clauses(request)) {
            if (clause.startsWith("更正") || clause.startsWith("修正") || clause.startsWith("再更正") || clause.startsWith("再修正")
                    || clause.contains("更正：") || clause.contains("更正:")
                    || clause.contains("修正：") || clause.contains("修正:")) {
                return clause;
            }
        }
        return null;
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
     * 一条消息可同时携带多条约束，按作用域分别落条目、各自只表达自己的事实。
     */
    private static void classifyAndMerge(ObjectMapper json,ObjectNode state,String request,UUID messageId) {
        int goalRevision=state.path("goalRevision").asInt(0);
        for (String scope : constraintScopes(request)) {
            mergeConstraint(json,state,request,messageId,scope,goalRevision);
        }
    }

    private static void mergeConstraint(ObjectMapper json,ObjectNode state,String request,UUID messageId,
            String scope,int goalRevision) {
        ArrayNode constraints=constraints(state,json);
        String newId=UUID.randomUUID().toString();
        ObjectNode created=jsonNode();
        created.put("scope",scope);
        Fact fact;
        switch (scope) {
            case SCOPE_TASK_COUNT_MAX -> fact=countFact(request,true);
            case SCOPE_TASK_COUNT_MIN -> fact=countFact(request,false);
            case SCOPE_DATE_LOCK -> fact=dateFact(request,constraints);
            case SCOPE_ASSIGNEE_LOCK -> fact=assigneeFact(request,constraints);
            default -> { return; }
        }
        if (fact==null) return; // 疑问/否定/无法确定：不产生结构化条目，原文留在 latestRequest
        fact.applyTo(created);
        created.put("id",newId);
        if (messageId!=null) created.put("sourceMessageId",messageId.toString());
        created.put("status","active");
        created.put("goalRevision",goalRevision);
        boolean countScope = SCOPE_TASK_COUNT_MAX.equals(scope) || SCOPE_TASK_COUNT_MIN.equals(scope);
        // 数量上限含糊（多处数值待澄清）时保守：只登记待澄清条目，不替代已知的 active 数量
        boolean maySupersede = !created.path("needsClarification").asBoolean(false);
        for (JsonNode entry : constraints) {
            if (!"active".equals(entry.path("status").asText())) continue;
            if (!scope.equals(entry.path("scope").asText())) continue;
            if (entry.path("value").asText("").equals(created.path("value").asText())
                    && String.valueOf(entry.path("detail")).equals(String.valueOf(created.path("detail")))) {
                return; // 完全相同的同作用域约束不重复累积
            }
            // 替代规则：数量上下界同作用域直接替代（显式取代关系由 detail.supersedes 溯源）；
            // 日期/负责人按被约束对象判断——同一对象明确修改才替代，不同对象并存，
            // 对象不明不覆盖已有明确对象的约束
            if (maySupersede && (countScope || supersedesProtectedScope(entry, created))) {
                ((ObjectNode) entry).put("status","superseded");
                ((ObjectNode) entry).put("supersededBy",newId);
                ((ObjectNode) entry).put("supersededReason","SCOPE_UPDATED");
            }
        }
        constraints.add(created);
        trimConstraints(json,state);
    }

    /** 旧值前缀中的被约束对象："任务A 日期不改" 存为 "[任务A] 日期不改…"。 */
    private static final java.util.regex.Pattern OBJECT_PREFIX =
            java.util.regex.Pattern.compile("^\\[([^\\]]{1,20})\\]\\s*");

    /**
     * 被约束对象：优先取 detail.object，其次从旧版 value 前缀 "[X] " 渐进解析。
     * 空串表示泛指（整个规划），不是可区分的具体对象。
     */
    private static String objectOf(JsonNode entry) {
        String explicit=entry.path("detail").path("object").asText("");
        if (!explicit.isEmpty()) return explicit;
        var matcher=OBJECT_PREFIX.matcher(entry.path("value").asText(""));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * 日期/负责人保护条目的替代判断（依据对象，不依据值是否不同）：
     * 双方对象明确且相同 → 同一对象的明确修改/撤销，替代；
     * 一方对象明确、另一方泛指或不明 → 并存，不自动覆盖；
     * 双方都泛指 → 同作用域的更新，替代。
     */
    private static boolean supersedesProtectedScope(JsonNode oldEntry, JsonNode newEntry) {
        String oldObject=objectOf(oldEntry);
        String newObject=objectOf(newEntry);
        if (!newObject.isEmpty() && !oldObject.isEmpty()) return newObject.equals(oldObject);
        if (!newObject.isEmpty() || !oldObject.isEmpty()) return false;
        return true;
    }

    // ---------- 规范化事实提取 ----------

    private abstract static class Fact {
        String value;
        String quote;
        ObjectNode detail=JsonNodeFactory.instance.objectNode();
        boolean needsClarification;

        void applyTo(ObjectNode entry) {
            entry.put("value",bounded(value,200));
            if (detail.size()>0) entry.set("detail",detail);
            if (quote!=null) entry.put("quote",bounded(quote,200));
            if (needsClarification) entry.put("needsClarification",true);
        }
    }

    private static final class CountFact extends Fact { }
    private static final class DateFact extends Fact { }
    private static final class AssigneeFact extends Fact { }

    private static ObjectNode jsonNode() { return JsonNodeFactory.instance.objectNode(); }

    /** 子句切分：逗号/句号/分号/换行/叹号/问号切开的短句，逐句判断。 */
    private static List<String> clauses(String text) {
        return java.util.Arrays.stream(text.split("[，。;；\\n！？]"))
                .map(String::trim)
                .filter(s->!s.isEmpty())
                .toList();
    }

    /** 疑问/核查语气的子句不是重新设定约束（"请检查是否仍为最多6项"、"能否按目标日期完成"）。 */
    private static boolean interrogative(String clause) {
        if (clause.contains("是否") || clause.contains("会不会") || clause.contains("要不要")
                || clause.contains("能不能") || clause.contains("能否") || clause.contains("行不行") || clause.contains("好不好")
                || clause.endsWith("?") || clause.endsWith("吗")) return true;
        return clause.startsWith("请检查") || clause.startsWith("请确认") || clause.startsWith("检查")
                || clause.startsWith("确认") || clause.startsWith("核实");
    }

    /** 否定旧值的子句不作为当前约束来源（"不是最多10项"）。 */
    private static boolean negated(String clause) {
        return clause.contains("不是") || clause.contains("不再是") || clause.contains("取消")
                || clause.contains("作废");
    }

    /** 限定词模式：最多N项 / 至少N项（当前值候选）。 */
    private static java.util.regex.Pattern countQualifierPattern(boolean max) {
        String qualifiers=max ? "最多|不超过|至多|不能超过" : "至少|最少|不少于";
        return java.util.regex.Pattern.compile(
                "("+qualifiers+")[^0-9一二三四五六七八九十两]{0,8}?([0-9]+|[一二三四五六七八九十两]+)(项|条|个|件)");
    }

    /** 修改动词模式：改成N项 / 调整为N项（明确的新值）。 */
    private static final java.util.regex.Pattern COUNT_CHANGE =
            java.util.regex.Pattern.compile(
                    "(改成|改为|调整成|调整为|设为|设置为|定为|限制在|控制在)[^0-9一二三四五六七八九十两]{0,8}?([0-9]+|[一二三四五六七八九十两]+)(项|条|个|件)");

    /** "从N项改成M项"中的旧值：从N项（后接改/调/变）。 */
    private static final java.util.regex.Pattern COUNT_FROM_OLD =
            java.util.regex.Pattern.compile(
                    "从[^0-9一二三四五六七八九十两]{0,6}?([0-9]+|[一二三四五六七八九十两]+)(项|条|个|件)\\s*(?=改|调|变)");

    /** 显式取代标记：子句中的数值是"被退位的旧值"，不是当前候选。 */
    private static final java.util.regex.Pattern SUPERSEDE_MARKER =
            java.util.regex.Pattern.compile("取代|替代|代替");

    /** 假设/虚拟语气的子句不是设定约束（"如果改成8项会怎样"）。 */
    private static boolean hypothetical(String clause) {
        return clause.contains("如果") || clause.contains("假如") || clause.contains("假设")
                || clause.contains("要是") || clause.endsWith("的话") || clause.endsWith("呢")
                || clause.endsWith("怎样") || clause.endsWith("如何");
    }

    /**
     * 数量事实：按子句区分新旧值。含"取代/替代/代替"的子句中的数值是被退位的旧值
     * （记入 detail.supersedes 保留溯源），不作为当前候选；"从N改成M"同样拆分。
     * 多个不同新值仍保守待澄清，不贪婪选数。
     */
    private static CountFact countFact(String request,boolean max) {
        java.util.regex.Pattern qualifier=countQualifierPattern(max);
        java.util.LinkedHashSet<Integer> newValues=new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<Integer> oldValues=new java.util.LinkedHashSet<>();
        String quote=null;
        for (String clause : clauses(request)) {
            if (interrogative(clause) || negated(clause) || hypothetical(clause)) continue;
            boolean replacement=SUPERSEDE_MARKER.matcher(clause).find();
            boolean matched=false;
            var qm=qualifier.matcher(clause);
            while (qm.find()) {
                Integer value=parseNumber(qm.group(2));
                if (value==null) continue;
                (replacement?oldValues:newValues).add(value);
                matched=true;
            }
            if (max) {
                var cm=COUNT_CHANGE.matcher(clause);
                while (cm.find()) {
                    Integer value=parseNumber(cm.group(2));
                    if (value==null) continue;
                    newValues.add(value);
                    matched=true;
                }
            }
            if (replacement) {
                var fm=COUNT_FROM_OLD.matcher(clause);
                while (fm.find()) {
                    Integer value=parseNumber(fm.group(1));
                    if (value==null) continue;
                    oldValues.add(value);
                    matched=true;
                }
            }
            if (matched && quote==null) quote=clause;
        }
        newValues.removeAll(oldValues); // 同一数值既是新旧也无从谈起，不当新值重复登记
        if (newValues.isEmpty() && oldValues.isEmpty()) return null;
        CountFact fact=new CountFact();
        String word=max ? "最多" : "至少";
        if (newValues.size()==1) {
            int value=newValues.iterator().next();
            fact.value=word+value+"项";
            fact.detail.put(max?"max":"min",value);
            if (!oldValues.isEmpty()) {
                var superseded=fact.detail.putArray("supersedes");
                for (int v : oldValues) superseded.add(v);
            }
        } else if (newValues.isEmpty()) {
            // 只有被退位旧值、没有新值：保守不产生结构化条目（原文留在 latestRequest）
            return null;
        } else {
            // 多个不同数值无法确定哪个生效：保守保留原文待澄清，不贪婪选数
            StringBuilder joined=new StringBuilder();
            for (int v : newValues) { if (joined.length()>0) joined.append('/'); joined.append(v); }
            fact.value="数量"+(max?"上限":"下限")+"待澄清（"+joined+"）";
            fact.needsClarification=true;
        }
        fact.quote=quote!=null ? quote : firstMentioning(request,"项");
        return fact;
    }

    private static final java.util.regex.Pattern DATE_PATTERN = java.util.regex.Pattern.compile(
            "\\d{4}[-/年.]\\s*\\d{1,2}[-/月.]\\s*\\d{1,2}");

    /** 日期限定动词：子句不带"日期"关键词但带这些动词时，其中的日期仍是新值候选。 */
    private static final java.util.regex.Pattern DATE_BINDING = java.util.regex.Pattern.compile(
            "固定|保持|落在|安排|区间|改为|改成|调整为|设为|设置为|定在|限定期");

    /**
     * 日期保护事实：只表达日期保护，不携带数量/阶段等其他要求。
     * priorConstraints 用于"沿用此前日期"——仅当与当前对象同范围的
     * 历史条目（含已替代条目）中能唯一定位一组日期时复用；
     * 跨对象或无法唯一确定时保守不猜，不能把别的对象的日期套过来。
     */
    private static DateFact dateFact(String request, JsonNode priorConstraints) {
        var dates=new LinkedHashSet<String>();
        boolean keywordSeen=false;
        boolean allInterrogative=true;
        for (String clause : clauses(request)) {
            if (!clause.contains("日期")) continue;
            keywordSeen=true;
            if (!interrogative(clause) && !hypothetical(clause)) allInterrogative=false;
        }
        if (!keywordSeen || allInterrogative) return null;
        // 日期收集不限"日期"子句：'任务A的日期不变，固定为2026-10-06至2026-10-11' 的
        // 新日期在后续子句中。只收集包含"日期"或带日期限定动词的子句，
        // 并跳过疑问/假设子句，避免把问题或其他语境里的日期当作新值
        for (String clause : clauses(request)) {
            if (interrogative(clause) || hypothetical(clause)) continue;
            if (!DATE_PATTERN.matcher(clause).find()) continue;
            if (!clause.contains("日期") && !DATE_BINDING.matcher(clause).find()) continue;
            var matcher=DATE_PATTERN.matcher(clause);
            while (matcher.find()) dates.add(normalizeDate(matcher.group()));
        }
        DateFact fact=new DateFact();
        String subject=protectionSubject(request,"日期",true);
        if (subject!=null) fact.detail.put("object",subject);
        if (dates.isEmpty()) {
            JsonNode reused=reuseUniquePriorDetail(priorConstraints,SCOPE_DATE_LOCK,"dates",subject);
            if (reused!=null) {
                StringBuilder joined=new StringBuilder();
                for (JsonNode date : reused) { if (joined.length()>0) joined.append(" 至 "); joined.append(date.asText()); }
                fact.value=(subject==null?"":"["+subject+"] ")+"不改日期（沿用 "+joined+"）";
                fact.detail.set("dates",reused.deepCopy());
            } else if (subject!=null) {
                fact.value="["+subject+"] 日期不改";
            } else {
                fact.value="不改日期";
                fact.needsClarification=priorConstraints!=null && hasPriorDetail(priorConstraints,SCOPE_DATE_LOCK);
            }
        } else {
            fact.value=(subject==null?"":"["+subject+"] ")+"日期不改（"+String.join(" 至 ",dates)+"）";
            var array=fact.detail.putArray("dates");
            for (String date : dates) array.add(date);
        }
        fact.quote=firstMentioning(request,"日期");
        return fact;
    }

    private static final java.util.regex.Pattern ASSIGNEE_TARGET = java.util.regex.Pattern.compile(
            "(?:负责人)?(?:固定为|固定成|统一为|统一由|都用|都由|均为|改为|改成|调整为|换为|换成|定为)\\s*([^，。;；\\n]{1,40})");

    private static AssigneeFact assigneeFact(String request, JsonNode priorConstraints) {
        String target=null;
        boolean keywordSeen=false;
        boolean allInterrogative=true;
        for (String clause : clauses(request)) {
            if (!clause.contains("负责人")) continue;
            keywordSeen=true;
            if (!interrogative(clause) && !hypothetical(clause)) allInterrogative=false;
            if (interrogative(clause) || negated(clause) || hypothetical(clause)) continue;
            var matcher=ASSIGNEE_TARGET.matcher(clause);
            if (matcher.find() && target==null) {
                String candidate=matcher.group(1).trim();
                if (!candidate.isBlank()) target=candidate;
            }
        }
        if (!keywordSeen || allInterrogative) return null;
        AssigneeFact fact=new AssigneeFact();
        // 无明确新负责人时才识别保护对象（"任务A负责人保持不变"→"任务A"）
        String subject=protectionSubject(request,"负责人",target!=null);
        if (subject!=null) fact.detail.put("object",subject);
        if (target==null) {
            JsonNode reused=reuseUniquePriorDetail(priorConstraints,SCOPE_ASSIGNEE_LOCK,"assignee",subject);
            if (reused!=null) {
                fact.value=(subject==null?"":"["+subject+"] ")+"负责人不改（沿用 "+reused.asText()+"）";
                fact.detail.put("assignee",reused.asText());
            } else if (subject!=null) {
                fact.value="["+subject+"] 负责人不改";
            } else {
                fact.value="不改负责人";
                fact.needsClarification=priorConstraints!=null && hasPriorDetail(priorConstraints,SCOPE_ASSIGNEE_LOCK);
            }
        } else {
            fact.value=(subject==null?"":"["+subject+"] ")+"负责人固定为 "+target;
            fact.detail.put("assignee",target);
        }
        fact.quote=firstMentioning(request,"负责人");
        return fact;
    }

    /**
     * 历史条目中唯一定位同名称细节（日期组/负责人）时返回，否则 null。
     * 复用范围限定在被约束对象内：subject 非空时只在该对象的历史条目里找，
     * subject 为空（泛指）时只在同样泛指的条目里找；
     * 不能在全部历史里找到唯一日期就套给另一个任务。
     */
    private static JsonNode reuseUniquePriorDetail(JsonNode constraints,String scope,String key,String subject) {
        if (constraints==null || !constraints.isArray()) return null;
        JsonNode unique=null;
        for (JsonNode entry : constraints) {
            if (!scope.equals(entry.path("scope").asText())) continue;
            String entryObject=objectOf(entry);
            if (subject!=null ? !subject.equals(entryObject) : !entryObject.isEmpty()) continue;
            JsonNode value=entry.path("detail").path(key);
            if (value.isMissingNode()) continue;
            if (unique==null) unique=value;
            else if (!String.valueOf(unique).equals(String.valueOf(value))) return null; // 多组不同值：无法唯一定位
        }
        return unique;
    }

    private static boolean hasPriorDetail(JsonNode constraints,String scope) {
        if (constraints==null || !constraints.isArray()) return false;
        for (JsonNode entry : constraints) {
            if (scope.equals(entry.path("scope").asText())
                    && entry.path("detail").isObject()
                    && entry.path("detail").size()>0) return true;
        }
        return false;
    }

    /** 保护对象识别："任务A的日期"→"任务A"、"任务A负责人"→"任务A"；
     *  通用主体（规划/项目等）不算对象。裸对象形式（"任务A负责人固定为甲"，无"的"）
     *  统一接受，但仅限以字母/数字结尾的短对象（任务A/任务2），
     *  避免把"所有任务建议""统一负责人"当对象。 */
    private static String protectionSubject(String request,String keyword,boolean requireParticle) {
        for (String clause : clauses(request)) {
            int index=clause.indexOf("的"+keyword);
            boolean particleFound=index>0;
            if (!particleFound) {
                index=clause.indexOf(keyword);
                if (index<=0) continue;
                String before=clause.substring(Math.max(0,index-12),index).trim();
                if (before.isEmpty()) continue;
                // 无"的"连接时，仅接受以字母/数字结尾的短对象（任务A/任务2），不接受普通语句片段
                if (!before.matches(".*[0-9A-Za-z]$") || before.length()>8 || GENERIC_SUBJECTS.contains(before)) continue;
                if (interrogative(clause)) continue;
                return before;
            }
            String before=clause.substring(Math.max(0,index-12),index).trim();
            if (before.isEmpty() || GENERIC_SUBJECTS.contains(before)) continue;
            if (interrogative(clause)) continue;
            return before;
        }
        return null;
    }

    private static String firstMentioning(String request,String keyword) {
        for (String clause : clauses(request)) {
            if (clause.contains(keyword)) return clause;
        }
        return request.length()<=200 ? request : request.substring(0,200);
    }

    private static String normalizeDate(String raw) {
        return raw.trim().replaceAll("[年月]","-").replaceAll("[日/.]","-").replaceAll("-+","-");
    }

    private static Integer parseNumber(String raw) {
        try { return Integer.valueOf(raw); }
        catch (NumberFormatException notArabic) {
            int total=parseChineseNumber(raw);
            return total<0 ? null : total;
        }
    }

    /** 简体中文数字 1-99；无法解析返回 -1（保守不猜）。 */
    private static int parseChineseNumber(String raw) {
        Map<Character,Integer> digits=Map.of(
                '一',1,'二',2,'两',2,'三',3,'四',4,'五',5,'六',6,'七',7,'八',8,'九',9);
        if ("十".equals(raw)) return 10;
        if (raw.length()==2 && raw.charAt(0)=='十' && digits.containsKey(raw.charAt(1))) return 10+digits.get(raw.charAt(1));
        if (raw.length()==2 && digits.containsKey(raw.charAt(0)) && raw.charAt(1)=='十') return digits.get(raw.charAt(0))*10;
        if (raw.length()==3 && digits.containsKey(raw.charAt(0)) && raw.charAt(1)=='十' && digits.containsKey(raw.charAt(2)))
            return digits.get(raw.charAt(0))*10+digits.get(raw.charAt(2));
        if (raw.length()==1 && digits.containsKey(raw.charAt(0))) return digits.get(raw.charAt(0));
        return -1;
    }

    /** 只保留确定性可识别的持续约束；其余表述（含格式/范围要求）归入本轮表达要求。
     *  数量限制区分上界/下界，二者可同时成立、互不替代。 */
    private static java.util.List<String> constraintScopes(String request) {
        String text=request==null ? "" : request;
        java.util.List<String> scopes=new java.util.ArrayList<>();
        if (text.matches(".*(最多|不超过|至多|只能)[^，。;；\\n]{0,8}[0-9一二三四五六七八九十]+[项条个].*")
                || text.matches(".*(改成|改为|调整成|调整为|设为|设置为|定为|限制在|控制在)[^，。;；\\n]{0,8}[0-9一二三四五六七八九十]+[项条个].*")) {
            scopes.add(SCOPE_TASK_COUNT_MAX);
        }
        if (text.matches(".*(至少|最少)[^，。;；\\n]{0,8}[0-9一二三四五六七八九十]+[项条个].*")) {
            scopes.add(SCOPE_TASK_COUNT_MIN);
        }
        if (text.contains("日期") && text.matches(".*(日期[^。；\\n]{0,6}(不要|不用|不能|禁止|保持|固定|不变|别动|别改|不改)|不要改日期|别改日期|不改日期|日期不变|日期保持).*")) {
            // 交替词不含单字"别"："日期…分别/差别"会经 ≤6 字桥接误命中（真实案例：
            // "按目标日期完成,分别需要什么条件"被登记为"不改日期"约束）
            scopes.add(SCOPE_DATE_LOCK);
        }
        if (text.contains("负责人") && text.matches(".*(负责人不要|负责人别|负责人不能|不改负责人|别改负责人|不要改负责人|负责人保持|负责人固定|负责人不改|负责人不变|负责人不动|负责人改为|负责人改成|负责人调整为|负责人换为|负责人换成|负责人定为|负责人统一).*")) {
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
