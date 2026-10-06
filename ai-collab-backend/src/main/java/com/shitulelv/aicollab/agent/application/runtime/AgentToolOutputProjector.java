package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 工具结果的确定性投影：输入工具输出与字符容量，输出模型可见结果。
 *
 * <p>超限时保留标量字段、每个数组前 {@value #PROJECTION_ITEMS} 项与计数，
 * 并附加投影标记；绝不伪造完整数据，模型需要更多数据时须用工具重新查询更小范围。</p>
 *
 * <p>列表页（{@code data.items}）走专用投影：模型可见记录、可见条数、
 * 覆盖总数与续页位置必须互相一致，不得只裁数组而保留指向未展示记录的游标。</p>
 *
 * <p>本组件是纯函数组件：不访问数据库、不读取模型配置、不决定执行下一步；
 * 资料是否过期由组装器/查询边界判定后传入。</p>
 */
public class AgentToolOutputProjector {
    /** 投影时每个数组保留的条目数。 */
    static final int PROJECTION_ITEMS = 3;

    /** 单条列表记录进入模型视图时的字符串字段上限。 */
    static final int LIST_ITEM_FIELD_CHARS = 200;

    /** 列表页投影可展示的最大记录数；超过时按可见记录重算续页位置。 */
    static final int LIST_PAGE_MAX_VISIBLE = 5;

    /** 极端压缩时单条列表记录的文本字段上限。 */
    static final int LIST_SINGLE_ITEM_FIELD_CHARS = 100;

    private final ObjectMapper json;

    public AgentToolOutputProjector(ObjectMapper json) {
        this.json = json;
    }

    JsonNode projectToolOutput(JsonNode output, int maxChars) {
        if (output == null || output.toString().length() <= maxChars) return output;
        if(output.path("data").has("baseVersionId") && output.path("data").has("draft")) return projectPlanningOutput(output,maxChars);
        if (isListPage(output)) return projectListPage(output, maxChars);
        ObjectNode projected = json.createObjectNode();
        output.fields().forEachRemaining(entry -> projected.set(entry.getKey(), boundNode(entry.getValue(),0)));
        projected.put("projection", "DETERMINISTIC");
        projected.put("fullDocumentRead", false);
        projected.put("evidenceScope", "PROJECTED_PARTIAL_OBSERVATION");
        projected.put("originalChars", output.toString().length());
        // 序列化大小与正文长度是不同语义：originalChars 是整个工具 JSON 的序列化长度，
        // 模型不得把它当作正文长度；modelVisibleChars 是本视图实际可见的序列化大小
        projected.put("originalCharsSemantics", "SERIALIZED_TOOL_RESULT_JSON_CHARS_NOT_BODY_LENGTH");
        if (projected.toString().contains("bodyProjection")) {
            projected.put("resumeHint", "正文在投影视图中被截断；用同一工具、相同 snapshotId/fromChunk，"
                    + "把 fromOffset 设为可见范围终点继续读取，不得跳过模型未见内容");
        }
        putSelfConsistentVisibleChars(projected);
        return projected;
    }

    /** modelVisibleChars 是本视图序列化大小；该字段自身也占长度，迭代到不动点保证
     *  声明值与实际序列化大小完全一致。 */
    private void putSelfConsistentVisibleChars(ObjectNode projected) {
        int estimate = projected.toString().length() + 30;
        for (int i = 0; i < 5; i++) {
            projected.put("modelVisibleChars", estimate);
            int actual = projected.toString().length();
            if (actual == estimate) return;
            estimate = actual;
        }
    }

    // ---------------------------------------------------------------- 列表页投影

    /** 工具结果是否是带稳定排序与续页契约的列表页（任务/里程碑）。 */
    private boolean isListPage(JsonNode output) {
        JsonNode data = output.path("data");
        return data.path("items").isArray() && data.has("nextCursor");
    }

    /**
     * 列表页专用投影。
     *
     * <p>保持模型可见记录与页契约一致：</p>
     * <ul>
     *   <li>{@code data.items} 只保留实际可见记录（每条按字段上限收紧）；</li>
     *   <li>{@code data.returned} / {@code data.taskFacts} 与可见记录同范围，
     *       并显式给出服务端本页条数与总数，模型不会把可见条数当成本页全部；</li>
     *   <li>{@code data.nextCursor} 始终指向本页之后第一条未展示记录（排除式续读），
     *       因此按游标续读不会跳过未见记录，也不会停滞在同一位置；</li>
     *   <li>本页记录全部可见时保留工具原始游标与 hasMore 语义，不做无谓改写。</li>
     * </ul>
     */
    private JsonNode projectListPage(JsonNode output, int maxChars) {
        ObjectNode root = json.createObjectNode();
        JsonNode source = output.path("data");
        ArrayNode originalItems = (ArrayNode) source.path("items");
        ArrayNode visible = json.createArrayNode();
        int budget = Math.max(400, maxChars - 900);
        int used = 0;
        for (JsonNode item : originalItems) {
            ObjectNode bounded = boundListItem(item);
            String serialized = bounded.toString();
            if (!visible.isEmpty() && used + serialized.length() > budget) break;
            if (visible.size() >= LIST_PAGE_MAX_VISIBLE) break;
            visible.add(bounded);
            used += serialized.length();
        }
        int visibleCount = visible.size();
        int pageCount = originalItems.size();

        ObjectNode data = json.createObjectNode();
        data.set("items", visible);
        data.put("returned", visibleCount);
        data.put("pageReturned", pageCount);
        data.put("total", source.path("total").isInt() ? source.path("total").asInt() : pageCount);
        if (source.hasNonNull("sort")) data.set("sort", source.get("sort"));
        if (source.hasNonNull("consistency")) data.set("consistency", source.get("consistency"));
        data.put("truncated", source.path("truncated").asBoolean(pageCount > visibleCount) || visibleCount < pageCount);
        if (source.hasNonNull("fieldGuide")) {
            data.put("fieldGuide", source.path("fieldGuide").asText() + " 本视图的记录已按可见范围重新投影："
                    + "data.returned 是本视图可见条数，data.pageReturned 是本次调用的完整一页条数，"
                    + "data.total 是当前过滤条件下的总量；data.nextCursor 是第一条未展示记录的位置，"
                    + "按它续读不会跳过未见记录。");
        }
        if (source.has("taskFacts") && source.path("taskFacts").isArray()) {
            ArrayNode facts = json.createArrayNode();
            for (int i = 0; i < visibleCount; i++) facts.add(boundNode(source.path("taskFacts").get(i), 1));
            data.set("taskFacts", facts);
        }
        output.fields().forEachRemaining(entry -> {
            if (entry.getKey().equals("data")) return;
            root.set(entry.getKey(), boundNode(entry.getValue(), 0));
        });
        root.set("data", data);
        root.put("projection", "DETERMINISTIC");
        root.put("projectionScope", "LIST_PAGE_WITH_ALIGNED_CURSOR");
        root.put("fullDocumentRead", false);
        root.put("evidenceScope", "PROJECTED_PARTIAL_OBSERVATION");
        root.put("originalChars", output.toString().length());
        root.put("originalCharsSemantics", "SERIALIZED_TOOL_RESULT_JSON_CHARS_NOT_BODY_LENGTH");
        applyPageContract(data, originalItems, source, visibleCount);
        putSelfConsistentVisibleChars(root);
        if (root.toString().length() > maxChars && visible.size() > 1) {
            // 仍超限：继续减少可见记录，同时重算续读位置，保持"可见范围↔游标"一致
            while (root.toString().length() > maxChars && visible.size() > 1) {
                visible.remove(visible.size() - 1);
                int shown = visible.size();
                if (data.path("taskFacts").isArray() && data.path("taskFacts").size() > shown) {
                    ((ArrayNode) data.path("taskFacts")).remove(data.path("taskFacts").size() - 1);
                }
                data.put("returned", shown);
                applyPageContract(data, originalItems, source, shown);
                putSelfConsistentVisibleChars(root);
            }
        }
        if (root.toString().length() > maxChars) {
            // 只剩一条可见记录仍超限：先放弃说明文字，再按同样规则收紧记录内长文本
            data.remove("fieldGuide");
            putSelfConsistentVisibleChars(root);
        }
        if (root.toString().length() > maxChars && visible.size() == 1) {
            // 单条记录也要收紧到极小文本上限，保证投影视图本身不超限
            ObjectNode single = boundListItem(originalItems.get(0), LIST_SINGLE_ITEM_FIELD_CHARS);
            visible.remove(0);
            visible.add(single);
            putSelfConsistentVisibleChars(root);
        }
        if (root.toString().length() > maxChars) {
            // 极端阈值下只保留页契约核心字段，避免元数据本身超出可见预算
            for (String key : List.of("pageReturned", "unshownInPage", "resumeScope", "pageRecordCount",
                    "projectedVisibleCount", "projectionScope", "consistency", "sort", "taskFacts")) {
                data.remove(key);
            }
            root.remove(List.of("projectionScope", "projectedVisibleCount", "pageRecordCount", "unshownInPage"));
        } else {
            root.put("projectedVisibleCount", visible.size());
            root.put("pageRecordCount", pageCount);
            root.put("unshownInPage", Math.max(0, pageCount - visible.size()));
        }
        putSelfConsistentVisibleChars(root);
        return root;
    }

    /**
     * 页契约：可见条数、覆盖总数、是否还有后续与续读位置必须来自同一可见范围。
     *
     * <p>工具发出的 {@code nextCursor} 是"第一条未返回记录"（续读含该记录本身），
     * 因此本页还有未展示记录时直接用本页第一条未展示记录作为新游标即可：
     * 它严格晚于可见的最后一条记录，续读既不跳过未见记录，也不重复已见记录。
     * 本页全部可见时保留工具原始游标（可能是下一页首条，也可能是最后一页）。</p>
     */
    private void applyPageContract(ObjectNode data, ArrayNode pageItems, JsonNode source, int shown) {
        String nextCursor;
        if (shown < pageItems.size()) {
            nextCursor = pageItems.get(shown).path("id").asText(null);
        } else {
            JsonNode serverCursor = source.path("nextCursor");
            nextCursor = serverCursor.isTextual() && !serverCursor.asText().isBlank() ? serverCursor.asText() : null;
        }
        boolean hasMore = nextCursor != null;
        data.put("hasMore", hasMore);
        if (nextCursor == null) data.putNull("nextCursor"); else data.put("nextCursor", nextCursor);
        if (shown < pageItems.size()) {
            data.put("unshownInPage", pageItems.size() - shown);
            data.put("resumeScope", "REMAINDER_OF_THIS_PAGE");
        } else {
            data.put("resumeScope", hasMore ? "NEXT_PAGE" : "END_OF_RESULT");
        }
    }

    /** 列表记录进入模型视图时的字段收紧：长文本截断，嵌套结构统一按深度上限处理。 */
    private ObjectNode boundListItem(JsonNode item) {
        return boundListItem(item, LIST_ITEM_FIELD_CHARS);
    }

    private ObjectNode boundListItem(JsonNode item, int textLimit) {
        ObjectNode bounded = json.createObjectNode();
        item.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual() && value.asText().length() > textLimit) {
                bounded.put(entry.getKey(), value.asText().substring(0, textLimit) + "… [projected]");
            } else {
                bounded.set(entry.getKey(), boundNode(value, 1));
            }
        });
        return bounded;
    }

    private JsonNode projectPlanningOutput(JsonNode output,int maxChars) {
        var result=json.createObjectNode();result.put("status",output.path("status").asText("SUCCEEDED"));
        var source=output.path("data");var data=result.putObject("data");
        for(String key:List.of("baseVersionId","expectedVersionNo","fromTask","totalTasks","hasMore","nextFromTask")) if(source.has(key)) data.set(key,source.get(key));
        data.set("version",source.path("version"));data.put("coverage","PROJECTED_TASK_PAGE");
        var draft=data.putObject("draft");var tasks=draft.putArray("tasks");int count=maxChars>=4000?3:1;
        var originals=source.path("draft").path("tasks");
        for(int i=0;i<Math.min(count,originals.size());i++) {
            var task=tasks.addObject();var original=originals.get(i);
            for(String key:List.of("tempKey","title","startDate","dueDate","suggestedAssigneeId","assigneeId","priority","milestoneTempKey","dependencyTempKeys"))
                if(original.has(key)) task.set(key,boundNode(original.get(key),0));
        }
        data.put("projectedTotalCount",originals.size());data.put("projectedOmitted",Math.max(0,originals.size()-tasks.size()));
        data.put("hasMore",source.path("hasMore").asBoolean() || tasks.size()<originals.size());data.put("nextFromTask",source.path("fromTask").asInt()+tasks.size());
        if(maxChars>=4000) {
            data.set("structuredIssues",boundNode(source.path("detail").path("structuredIssues"),0));
            draft.set("sources",boundNode(source.path("draft").path("sources"),0));
        }
        result.put("projection","DETERMINISTIC");result.put("originalChars",output.toString().length());
        result.put("originalCharsSemantics","SERIALIZED_TOOL_RESULT_JSON_CHARS_NOT_BODY_LENGTH");
        putSelfConsistentVisibleChars(result);
        if(result.toString().length()>maxChars) {draft.remove("sources");data.remove("structuredIssues");data.put("detailsOmitted",true);putSelfConsistentVisibleChars(result);}
        while(result.toString().length()>maxChars && tasks.size()>1) tasks.remove(tasks.size()-1);
        data.put("projectedOmitted",Math.max(0,originals.size()-tasks.size()));data.put("hasMore",source.path("hasMore").asBoolean() || tasks.size()<originals.size());data.put("nextFromTask",source.path("fromTask").asInt()+tasks.size());
        return result;
    }

    /** 文档正文条目：带 content 正文与 fromOffset/throughOffset 范围坐标。 */
    private boolean isBodyItem(JsonNode value) {
        return value.isObject()
                && value.path("content").isTextual() && value.path("content").asText().length() > 200
                && value.path("fromOffset").isIntegralNumber() && value.path("throughOffset").isIntegralNumber();
    }

    /** 正文条目投影：可见长度、可见终点与正文保持一致；原始范围终点显式保留为
     *  originalThroughOffset，模型可见视图与工具原始读取范围不再混用同一字段。 */
    private ObjectNode boundBodyItem(ObjectNode item) {
        ObjectNode projected = json.createObjectNode();
        String content = item.path("content").asText();
        int from = item.path("fromOffset").asInt();
        int originalThrough = item.path("throughOffset").asInt();
        int visible = 200;
        item.fields().forEachRemaining(entry -> {
            if (entry.getKey().equals("content") || entry.getKey().equals("throughOffset")) return;
            projected.set(entry.getKey(), boundNode(entry.getValue(),1));
        });
        projected.put("content", content.substring(0, visible) + "… [projected]");
        projected.put("fromOffset", from);
        projected.put("throughOffset", from + visible);
        projected.put("originalThroughOffset", originalThrough);
        projected.put("omittedChars", Math.max(0, originalThrough - (from + visible)));
        projected.put("bodyProjection", "MODEL_VISIBLE_ONLY");
        return projected;
    }

    private JsonNode boundNode(JsonNode value,int depth) {
        if(value!=null && value.isTextual() && value.asText().length()>200)
            return json.getNodeFactory().textNode(value.asText().substring(0,200)+"… [projected]");
        if (value == null || value.isValueNode()) return value;
        if(depth>6) return json.createObjectNode().put("projectedObject",true);
        if (value.isArray()) {
            ArrayNode array = json.createArrayNode();
            for (int i = 0; i < value.size() && i < PROJECTION_ITEMS; i++) {
                array.add(boundNode(value.get(i),depth+1));
            }
            ObjectNode marker = array.addObject();
            marker.put("projectedTotalCount", value.size());
            marker.put("projectedOmitted", Math.max(0, value.size() - PROJECTION_ITEMS));
            return array;
        }
        // 文档正文条目走专用的可见范围投影，不走通用对象递归
        if (isBodyItem(value)) return boundBodyItem((ObjectNode) value);
        ObjectNode object = json.createObjectNode();
        value.fields().forEachRemaining(entry -> {
            object.set(entry.getKey(), boundNode(entry.getValue(),depth+1));
        });
        object.put("projectedObject", true);
        return object;
    }
}
