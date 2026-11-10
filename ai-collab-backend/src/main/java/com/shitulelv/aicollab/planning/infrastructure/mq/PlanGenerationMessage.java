package com.shitulelv.aicollab.planning.infrastructure.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * 规划 dispatch 消息体（M2 §3.1 第 2 行）：projectId + planId + actor + detailOnly
 * + dispatch 时刻的身份快照（generationSeq / activeAttemptId）。
 *
 * <p>身份快照必须随消息走：消费侧用 {@code repository.require} 重建记录后，把
 * generationSeq/activeAttemptId 替换回 dispatch 时刻的值再交给编排器执行入口 ——
 * 编排器的幂等链（{@code repository.active()} 校验 generationSeq+attemptId+状态）
 * 依赖"快照身份 = dispatch 身份"这一不变量。若消费时以 DB 当前身份执行，
 * "取消→重新生成"后迟到的旧消息会错误地以新身份启动重复生成。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanGenerationMessage(UUID projectId, UUID planId, UUID actor, boolean detailOnly,
                                    long generationSeq, UUID activeAttemptId) {

    /** 消息身份属性（与 M1 文档链路同款：便于 broker 侧按属性检索/诊断）。 */
    public static final String PROP_PLAN_ID = "planId";
    public static final String PROP_DETAIL_ONLY = "detailOnly";
}
