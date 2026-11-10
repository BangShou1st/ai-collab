package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REFLECT 输出契约解析单测（§5.3）：严格 schema、畸形输出判失败（由编排器降级处理）。
 */
class TaskPlanReflectParserTest {

    private final TaskPlanOutputParser parser = new TaskPlanOutputParser(new ObjectMapper());

    @Test
    void parsesValidIssues() {
        String output = """
                {"issues":[
                  {"severity":"HARD","targetType":"TASK","targetTempKey":"t1","field":"dueDate",
                   "suggestion":"把截止日期提前到依赖任务之前"},
                  {"severity":"SOFT","targetType":"PLAN","targetTempKey":null,"field":null,
                   "suggestion":"任务粒度偏细，建议合并 t3/t4"}]}
                """;
        var parsed = parser.parseReflect(output);
        assertThat(parsed.issues()).hasSize(2);
        assertThat(parsed.issues().get(0).severity().name()).isEqualTo("HARD");
        assertThat(parsed.issues().get(0).targetTempKey()).isEqualTo("t1");
        assertThat(parsed.issues().get(1).targetType()).isEqualTo("PLAN");
    }

    @Test
    void emptyIssuesIsvalid() {
        assertThat(parser.parseReflect("{\"issues\":[]}").issues()).isEmpty();
    }

    @Test
    void unknownPropertyIsRejected() {
        assertThatThrownBy(() -> parser.parseReflect(
                "{\"issues\":[],\"extra\":1}"))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void invalidSeverityIsRejected() {
        assertThatThrownBy(() -> parser.parseReflect("""
                {"issues":[{"severity":"CRITICAL","targetType":"TASK","targetTempKey":"t1",
                "field":null,"suggestion":"x"}]}
                """))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void invalidTargetTypeIsRejected() {
        assertThatThrownBy(() -> parser.parseReflect("""
                {"issues":[{"severity":"HARD","targetType":"USER","targetTempKey":"u1",
                "field":null,"suggestion":"x"}]}
                """))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void missingSuggestionIsRejected() {
        assertThatThrownBy(() -> parser.parseReflect("""
                {"issues":[{"severity":"HARD","targetType":"TASK","targetTempKey":"t1",
                "field":"dueDate","suggestion":""}]}
                """))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void taskIssueWithoutTempKeyIsRejected() {
        assertThatThrownBy(() -> parser.parseReflect("""
                {"issues":[{"severity":"HARD","targetType":"TASK","targetTempKey":null,
                "field":null,"suggestion":"x"}]}
                """))
                .isInstanceOf(ModelOutputContractException.class);
    }

    @Test
    void trailingGarbageIsRejected() {
        assertThatThrownBy(() -> parser.parseReflect(
                "{\"issues\":[]} 尾随说明文字"))
                .isInstanceOf(ModelOutputContractException.class);
    }
}
