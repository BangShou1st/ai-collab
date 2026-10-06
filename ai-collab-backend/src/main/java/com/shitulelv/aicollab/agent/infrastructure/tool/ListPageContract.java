package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Function;

/**
 * 列表工具的续页契约。任务与里程碑列表共用同一份语义，避免两个工具各自算出
 * "可见记录、返回条数、覆盖范围、下一段位置"不一致的结果。
 *
 * <p>约定（模型可见视图必须与服务端原始结果保持同一语义）：</p>
 * <ul>
 *   <li>{@code items} 是按稳定排序（id ASC）取出的本页记录，条数为 {@code returned}；</li>
 *   <li>{@code total} 是当前过滤条件下尚未续读的记录总数，{@code hasMore} 表示是否还有后续；</li>
 *   <li>{@code nextCursor} 是<b>第一条未返回记录</b>的 id；续读时把它作为 {@code cursor}
 *       重新调用，服务端返回 {@code id >= cursor} 的记录。因此续读既不跳过未返回记录，
 *       也不重复已返回记录；{@code nextCursor} 为 null 表示已经取到最后一页。</li>
 * </ul>
 */
final class ListPageContract {
    /**
     * 单页目标体积（字符）。明显低于组装器的最新工具结果阈值（6000 字符），
     * 使"服务端本页记录 = 模型可见记录"：下游投影通常不再需要裁掉本页记录，
     * 续读语义不会被两层缩减割裂。超过预算时服务端就收窄本页条数。
     */
    static final int PAGE_BUDGET_CHARS = 3600;

    private ListPageContract() {
    }

    /**
     * 构建一页。
     *
     * @param json      序列化器
     * @param sorted    已按 id 升序排好的全部候选记录
     * @param limit     调用方请求的本页最多条数
     * @param projector 记录 → 模型可见字段（不得改变记录数量或顺序）
     * @param idOf      稳定排序 id
     * @param sortLabel 排序说明，写入 {@code sort} 供模型核对续读一致性
     * @param extraData 额外的页级字段（如 fieldGuide）；可为 null
     */
    static <T> ObjectNode page(
            ObjectMapper json, List<T> sorted, int limit,
            Function<T, ObjectNode> projector, Function<T, String> idOf,
            String sortLabel, java.util.function.Consumer<ObjectNode> extraData) {
        int pageSize = Math.min(Math.max(0, limit), sorted.size());
        ArrayNode items = json.createArrayNode();
        int used = 0;
        for (int i = 0; i < pageSize; i++) {
            ObjectNode projected = projector.apply(sorted.get(i));
            int size = projected.toString().length();
            // 至少保留一条：单条超过整页预算时仍返回它，由投影层继续收紧字段
            if (i > 0 && used + size > PAGE_BUDGET_CHARS) break;
            items.add(projected);
            used += size;
        }
        ObjectNode data = json.createObjectNode();
        data.set("items", items);
        data.put("returned", items.size());
        data.put("total", sorted.size());
        boolean hasMore = sorted.size() > items.size();
        data.put("hasMore", hasMore);
        data.put("truncated", hasMore);
        // 明确写第一条未返回记录：续读从它开始，既不跳过也不重复
        if (hasMore) data.put("nextCursor", idOf.apply(sorted.get(items.size())));
        else data.putNull("nextCursor");
        data.put("sort", sortLabel);
        data.put("consistency", "LIVE_KEYSET");
        if (extraData != null) extraData.accept(data);
        return data;
    }

    /** 续读过滤：从 {@code cursor}（第一条未返回记录）开始，含该记录本身。 */
    static <T> List<T> from(List<T> sorted, String cursor, Function<T, String> idOf) {
        if (cursor == null) return sorted;
        return sorted.stream().filter(item -> idOf.apply(item).compareTo(cursor) >= 0).toList();
    }
}
