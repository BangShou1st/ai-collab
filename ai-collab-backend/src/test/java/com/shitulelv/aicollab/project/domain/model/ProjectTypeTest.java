package com.shitulelv.aicollab.project.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-06 业务中性化:项目类型枚举契约。
 * 通用类型供新建项目使用;历史类型仅因存量数据兼容而保留,任何一轮都不得删除。
 */
class ProjectTypeTest {

    @Test
    void 应包含全部通用业务类型() {
        assertThat(ProjectType.values())
            .contains(
                ProjectType.PRODUCT_DEVELOPMENT,
                ProjectType.CLIENT_DELIVERY,
                ProjectType.INTERNAL_PROJECT,
                ProjectType.RESEARCH_EXPLORATION,
                ProjectType.OTHER);
    }

    @Test
    void 历史类型必须保留以兼容存量数据() {
        assertThat(ProjectType.values())
            .contains(
                ProjectType.COMPETITION,
                ProjectType.COURSE_DESIGN,
                ProjectType.SOFTWARE_TRAINING);
    }
}
