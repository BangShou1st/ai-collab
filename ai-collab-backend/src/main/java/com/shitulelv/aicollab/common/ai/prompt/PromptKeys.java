package com.shitulelv.aicollab.common.ai.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prompt key 常量与派生规则。
 *
 * <p>key 是跨语言契约的一部分（Python PromptPack 目录名 = Java key），
 * 因此这里冻结成常量而不是运行时拼装；{@link #all()} 的顺序与打包 manifest 一致，
 * 供远端预取与追溯快照使用。</p>
 */
public final class PromptKeys {

    /** 知识问答 system prompt。 */
    public static final String KNOWLEDGE_SYSTEM = "knowledge.system";

    /** 知识问答 user prompt。 */
    public static final String KNOWLEDGE_USER = "knowledge.user";

    private static final Pattern SCENARIO_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    /** 六个内置 Scenario code，按字典序；顺序稳定，参与 {@link #all()}。 */
    private static final List<String> SCENARIO_CODES = List.of(
            "delivery_readiness",
            "iteration_planning",
            "meeting_to_tasks",
            "project_health",
            "project_research",
            "weekly_report");

    private static final List<String> ALL;

    static {
        List<String> keys = new ArrayList<>(2 + SCENARIO_CODES.size() * 2);
        keys.add(KNOWLEDGE_SYSTEM);
        keys.add(KNOWLEDGE_USER);
        for (String code : SCENARIO_CODES) {
            keys.add(scenarioInstruction(code));
            keys.add(scenarioOutputContract(code));
        }
        ALL = List.copyOf(keys);
    }

    private PromptKeys() {
    }

    /** {@code scenario.<code>.instruction}；code 大小写不敏感，统一转小写。 */
    public static String scenarioInstruction(String scenarioCode) {
        return "scenario." + normalizeCode(scenarioCode) + ".instruction";
    }

    /** {@code scenario.<code>.output_contract}；code 大小写不敏感，统一转小写。 */
    public static String scenarioOutputContract(String scenarioCode) {
        return "scenario." + normalizeCode(scenarioCode) + ".output_contract";
    }

    /** J2 冻结的 14 个 key，顺序稳定（knowledge 两条 + 六个 Scenario 各两条）。 */
    public static List<String> all() {
        return ALL;
    }

    private static String normalizeCode(String scenarioCode) {
        if (scenarioCode == null || scenarioCode.isBlank()) {
            throw new IllegalArgumentException("scenario code 不能为空");
        }
        if (!SCENARIO_CODE.matcher(scenarioCode).matches()) {
            throw new IllegalArgumentException("scenario code 非法: " + scenarioCode);
        }
        return scenarioCode.toLowerCase(Locale.ROOT);
    }
}
