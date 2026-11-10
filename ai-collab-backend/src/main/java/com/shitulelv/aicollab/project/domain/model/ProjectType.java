package com.shitulelv.aicollab.project.domain.model;

public enum ProjectType {
    // 通用业务类型(新建项目优先)
    PRODUCT_DEVELOPMENT, // 产品研发
    CLIENT_DELIVERY,     // 客户交付
    INTERNAL_PROJECT,    // 内部项目
    RESEARCH_EXPLORATION, // 研究探索
    OTHER,               // 其他

    // 历史兼容类型(仅保留读与编辑能力,新建入口不再优先展示)
    COMPETITION,       // 竞赛项目
    COURSE_DESIGN,     // 课程设计
    SOFTWARE_TRAINING  // 软件实训
}
