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
 *
 * <p>P3 起 {@code knowledge.system}/{@code knowledge.user} 随问答装配迁到 Python
 * （契约 §2.1 行 7、Q12=a），Java 侧不再预取这两个 key；scenario/decide 等其余 key
 * 仍由 Java 拉取。</p>
 */
public final class PromptKeys {

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
        List<String> keys = new ArrayList<>(SCENARIO_CODES.size() * 2);
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

    /** P3 冻结的 12 个 key，顺序稳定（六个 Scenario 各两条；knowledge 两条已随问答迁 Python）。 */
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
