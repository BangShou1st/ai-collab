package com.shitulelv.aicollab.agent.evals;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GoldenCaseLoader 契约测试（无需 Docker）：
 * 真实评估集资源必须能加载且达到起步规模、id 唯一、六个类目齐备；
 * 各类畸形 case 必须在加载阶段失败（启动即失败，绝不静默跳过）。
 */
class GoldenCaseLoaderTest {

    @Test
    void realResourceLoadsTwentyUniqueCasesAcrossAllCategories() {
        List<GoldenCase> cases = new GoldenCaseLoader().load();
        assertThat(cases).hasSize(20);
        assertThat(cases).extracting(GoldenCase::id).doesNotHaveDuplicates();
        // 六大类目齐备
        for (GoldenCase.Category category : GoldenCase.Category.values()) {
            assertThat(cases).extracting(GoldenCase::category).contains(category);
        }
        // 回归防线 case 齐备：判重、软阈值、loop guard 各一
        assertThat(cases).extracting(GoldenCase::id).contains(
                "delegation-duplicate-objective-rejected",
                "budget-soft-threshold-restrict-hint",
                "loop-guard-intercepts-repetition");
    }

    @Test
    void malformedCasesFailAtLoadTime() {
        GoldenCaseLoader loader = new GoldenCaseLoader();

        // 缺字段
        assertThatThrownBy(() -> loader.parse("{\"version\":1,\"cases\":[]}"))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class)
                .hasMessageContaining("$.cases");
        assertThatThrownBy(() -> loader.parse("""
                {"version":1,"cases":[{"id":"a","category":"LOOP_GUARD"}]}
                """))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class);

        // 非法枚举与重复 id
        assertThatThrownBy(() -> loader.parse("""
                {"version":1,"cases":[{"id":"a","category":"NOT_A_CATEGORY","title":"t","guards":"g",
                  "userRequest":"r","script":{"rootTurns":[{"content":"c"}]},
                  "assertions":{"finalStatus":"SUCCEEDED"}}]}
                """))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class)
                .hasMessageContaining("category");
        assertThatThrownBy(() -> loader.parse("""
                {"version":1,"cases":[
                  {"id":"a","category":"LOOP_GUARD","title":"t","guards":"g","userRequest":"r",
                   "script":{"rootTurns":[{"content":"c"}]},"assertions":{}},
                  {"id":"a","category":"TASK_QUERY","title":"t2","guards":"g2","userRequest":"r2",
                   "script":{"rootTurns":[{"content":"c"}]},"assertions":{}}]}
                """))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class)
                .hasMessageContaining("id 重复");

        // 非法事件类型与未知 DB probe（词表外一律拒绝）
        assertThatThrownBy(() -> loader.parse("""
                {"version":1,"cases":[{"id":"a","category":"TASK_QUERY","title":"t","guards":"g",
                  "userRequest":"r","script":{"rootTurns":[{"content":"c"}]},
                  "assertions":{"events":[{"type":"NOT_AN_EVENT"}]}}]}
                """))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class)
                .hasMessageContaining("NOT_AN_EVENT");
        assertThatThrownBy(() -> loader.parse("""
                {"version":1,"cases":[{"id":"a","category":"TASK_QUERY","title":"t","guards":"g",
                  "userRequest":"r","script":{"rootTurns":[{"content":"c"}]},
                  "assertions":{"db":[{"name":"n","probe":"FREE_SQL","sql":"DROP TABLE x"}]}}]}
                """))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class)
                .hasMessageContaining("FREE_SQL");

        // 非法 finalStatus / toolBehavior 键
        assertThatThrownBy(() -> loader.parse("""
                {"version":1,"cases":[{"id":"a","category":"TASK_QUERY","title":"t","guards":"g",
                  "userRequest":"r","script":{"rootTurns":[{"content":"c"}]},
                  "assertions":{"finalStatus":"FLYING"}}]}
                """))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class)
                .hasMessageContaining("FLYING");
        assertThatThrownBy(() -> loader.parse("""
                {"version":1,"cases":[{"id":"a","category":"TASK_QUERY","title":"t","guards":"g",
                  "userRequest":"r","toolBehavior":{"delete_users":"OK"},
                  "script":{"rootTurns":[{"content":"c"}]},"assertions":{}}]}
                """))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class)
                .hasMessageContaining("delete_users");

        // 非法 JSON
        assertThatThrownBy(() -> loader.parse("not json"))
                .isInstanceOf(GoldenCaseLoader.GoldenCaseFormatException.class);
    }

    @Test
    void validMinimalCaseParsesWithDefaults() {
        List<GoldenCase> cases = new GoldenCaseLoader().parse("""
                {"version":1,"cases":[{"id":"case-a","category":"TASK_QUERY","title":"标题","guards":"g",
                  "userRequest":"查询任务","script":{"rootTurns":[{"content":"c","toolCalls":[]}]},
                  "assertions":{"finalStatus":"SUCCEEDED"}}]}
                """);
        assertThat(cases).hasSize(1);
        GoldenCase c = cases.getFirst();
        assertThat(c.script().maxAdvances()).isEqualTo(12);
        assertThat(c.script().childTurns()).isEmpty();
        assertThat(c.assertions().events()).isEmpty();
        assertThat(c.assertions().finalStatus()).isEqualTo("SUCCEEDED");
    }
}
