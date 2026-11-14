package com.shitulelv.aicollab.common.ai.prompt;

import java.util.Map;

/**
 * Prompt 来源端口：domain / application 只依赖本接口，不感知 classpath 资源或 HTTP。
 *
 * <p>实现必须保证：不可用时抛
 * {@link com.shitulelv.aicollab.common.exception.BusinessException}
 * （{@link com.shitulelv.aicollab.common.exception.ErrorCode#AI_PROMPT_UNAVAILABLE}），
 * 不允许静默返回旧正文以外的任何降级内容，更不允许把 Prompt 正文写进日志或异常消息。</p>
 */
public interface PromptCatalog {

    /** 取当前版本；不可用时抛 {@code BusinessException(AI_PROMPT_UNAVAILABLE)}。 */
    PromptTemplate require(String key);

    /** 当前全部可用条目的 {@code key -> version}，供运行快照追溯。 */
    Map<String, String> versions();

    /** 来源标识：{@code BUNDLED} / {@code REMOTE}。 */
    String source();

    /** manifest 指纹（条目 {@code key@version:sha256} 的稳定摘要）。 */
    String manifestFingerprint();
}
