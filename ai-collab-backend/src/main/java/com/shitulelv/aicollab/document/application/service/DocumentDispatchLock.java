package com.shitulelv.aicollab.document.application.service;

import java.util.UUID;

/**
 * 文档派发跨实例互斥锁（回落模式专用，Redis 深化 P2）。
 *
 * <p>仅用于进程内派发路径（{@link DocumentTaskDispatcher#dispatch}）：MQ 模式
 * （总闸 {@code document.mq.enabled=true}）下不装配本接口的实现 —— MQ 消费幂等
 * 三重保障已覆盖跨实例互斥，再加锁属于重复机制。</p>
 *
 * <p>实现必须满足两个约定：</p>
 * <ul>
 *   <li><b>非阻塞获取</b>：{@code tryLock(0)} 语义，拿不到立即返回 false，调用方跳过
 *       本次派发（不排队不等待 —— 文档状态行仍是 UPLOADED，恢复 Job 会重新派发）；</li>
 *   <li><b>失败降级放行</b>：锁服务不可用（Redis 掉线等）时返回 true 放行而非抛异常 ——
 *       正确性由 {@code DocumentProcessingService.claim} 的 CAS 条件更新兜底，
 *       锁不可用只是回到"多一次无效竞争"的旧回落模式，绝不能因锁失败阻断文档处理。</li>
 * </ul>
 */
public interface DocumentDispatchLock {

    /**
     * 尝试对同一 documentId 获取跨实例互斥锁（非阻塞，waitTime=0）。
     *
     * @return true —— 已获锁或锁服务不可用（降级放行），调用方继续执行；
     *         false —— 其他实例正持有该文档的处理权，调用方应跳过本次派发
     */
    boolean tryLockForDispatch(UUID projectId, UUID documentId);

    /** 释放派发锁；未持有锁时调用应为无害空操作。 */
    void unlockAfterDispatch(UUID projectId, UUID documentId);
}
