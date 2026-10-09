package com.shitulelv.aicollab.infrastructure.ai.turn;

/**
 * 模型正文流式观察通道：适配器在 SSE 聚合时把"到目前为止的累计正文"推给已激活的观察者，
 * 供上层做实时展示。观察者由调用方（Agent 运行时）在发起模型请求的同一线程上激活，
 * 请求结束后必须 {@link #clear()}；未激活时推送是无操作。
 *
 * <p>本通道只服务于临时展示：不影响 {@link ModelTurnResult} 的聚合、校验与落库，
 * 观察者抛出的异常在这里被吞掉，不能把展示侧失败误判为 provider 失败。</p>
 */
public final class ModelContentPreview {

    /** 接收累计正文快照；finalFrame 表示流已正常读到结尾（聚合结果随后原样返回）。 */
    public interface Observer {
        void onContent(String cumulativeText, boolean finalFrame);
    }

    private static final ThreadLocal<Observer> OBSERVER = new ThreadLocal<>();

    private ModelContentPreview() {
    }

    /** 在即将发起模型请求的线程上激活观察者；同一线程内必须 finally {@link #clear()}。 */
    public static void activate(Observer observer) {
        OBSERVER.set(observer);
    }

    public static void clear() {
        OBSERVER.remove();
    }

    /**
     * 捕获当前线程激活的观察者。适配器的流式回调通常在 HttpClient 的读流线程上执行，
     * ThreadLocal 不可见——须在发起请求的线程先 capture，再在回调线程用
     * {@link #push(Observer, String)}/{@link #finish(Observer, String)} 显式传递。
     */
    public static Observer capture() {
        return OBSERVER.get();
    }

    /** 流式聚合过程中的累计正文快照；观察者为空或空文本时无操作。 */
    public static void push(Observer observer, String cumulativeText) {
        notify(observer, cumulativeText, false);
    }

    /** 流结束时的最终累计正文；观察者为空或空文本时无操作。 */
    public static void finish(Observer observer, String cumulativeText) {
        notify(observer, cumulativeText, true);
    }

    /** 当前线程激活时的便捷推送（回调与激活同线程的路径）。 */
    public static void push(String cumulativeText) {
        notify(OBSERVER.get(), cumulativeText, false);
    }

    private static void notify(Observer observer, String text, boolean finalFrame) {
        if (observer == null || text == null || text.isEmpty()) return;
        try {
            observer.onContent(text, finalFrame);
        } catch (RuntimeException ignored) {
            // 展示侧失败不影响模型调用
        }
    }
}
