package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSummaryFormatterTest {

    private final AuditSummaryFormatter formatter = new AuditSummaryFormatter(new ObjectMapper());

    @ParameterizedTest
    @CsvSource({
            "PROJECT_INVITATION_CREATED,PROJECT_INVITATION,创建了项目邀请",
            "PROJECT_MEMBER_ROLE_CHANGED,PROJECT_MEMBER,变更了成员角色",
            "PROJECT_MEMBER_REMOVED,PROJECT_MEMBER,移除了项目成员",
            "TASK_COMMENT_CREATED,TASK_COMMENT,发表了任务评论",
            "TASK_COMMENT_UPDATED,TASK_COMMENT,更新了任务评论",
            "TASK_COMMENT_DELETED,TASK_COMMENT,删除了任务评论",
            "DOCUMENT_INDEXED,PROJECT_DOCUMENT,完成了文档处理",
            "DOCUMENT_PROCESSING_FAILED,PROJECT_DOCUMENT,文档处理失败",
            "DOCUMENT_RETRY_REQUESTED,PROJECT_DOCUMENT,重新处理了文档",
            "TASK_PLAN_CONFIRMED,AI_TASK_PLAN,确认了 AI 任务规划"
    })
    void formatsActualApplicationCodes(String action, String entityType, String phrase) {
        assertThat(formatter.format("张三", action, entityType, "{}"))
                .isEqualTo("张三" + phrase);
    }

    @Test
    void includesSafeEntityNameWhenDetailContainsTitle() {
        assertThat(formatter.format(
                "张三",
                "TASK_CREATED",
                "TASK",
                "{\"title\":\"实现登录\"}"))
                .isEqualTo("张三创建了任务「实现登录」");
    }

    @Test
    void unknownCodesDoNotLeakRawInternalValues() {
        String summary = formatter.format("张三", "SECRET_INTERNAL_ACTION", "RAW_ENTITY", "{}");

        assertThat(summary)
                .isEqualTo("张三执行了操作")
                .doesNotContain("SECRET_INTERNAL_ACTION", "RAW_ENTITY");
    }
}
