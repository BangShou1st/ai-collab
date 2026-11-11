package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.common.ai.TimeContext;
import com.shitulelv.aicollab.common.ai.prompt.PromptCatalog;
import com.shitulelv.aicollab.common.ai.prompt.PromptKeys;
import com.shitulelv.aicollab.common.ai.prompt.PromptRenderer;
import com.shitulelv.aicollab.common.ai.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识问答 Prompt 的<b>唯一装配点</b>（J2 起）。
 *
 * <p>J1-4 曾把三份问答 Prompt 收敛到 {@code KnowledgePromptProvider} 这个临时单例；
 * J2 把正文迁到 Python PromptPack（{@code knowledge.system} / {@code knowledge.user}）后，
 * 本类取代它，只负责三件<b>不能</b>搬走的事：</p>
 * <ol>
 *   <li>时间行：{@code TimeContext.beijingTimeContext()} 由 Java 生成，作为
 *       {@code {{ timeContext }}} 的值原样注入（保持与旧实现逐字节相同的三换行节奏）。</li>
 *   <li>不可信文本转义：问题与证据块先经 {@link KnowledgePromptText#escapeXmlText}，
 *       模板本身<b>不含</b>任何转义逻辑——安全不依赖 Prompt 资产的正确性。</li>
 *   <li>会话上下文前缀：非空时以 {@code context + "\n\n"} 前置（旧实现语义不变）。</li>
 * </ol>
 *
 * <p>Prompt 不可用（worker 不可达、manifest 校验失败、sha256 不一致）时由
 * {@link PromptCatalog#require(String)} 抛 {@code AI_PROMPT_UNAVAILABLE}：
 * 快速失败，绝不静默改用旧 Prompt。本类不缓存、不打印 Prompt 正文。</p>
 */
@Component
public class KnowledgePromptAssembler {

    private final PromptCatalog prompts;

    public KnowledgePromptAssembler(PromptCatalog prompts) {
        this.prompts = prompts;
    }

    /** 装配后的 Prompt 正文与其在 PromptPack 中的身份（key/version 用于调用链追溯）。 */
    public record RenderedPrompt(String text, String key, String version) {}

    /** 统一知识问答 System Prompt（PromptPack 模板 + 动态北京时间）。 */
    public RenderedPrompt system() {
        PromptTemplate template = prompts.require(PromptKeys.KNOWLEDGE_SYSTEM);
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("timeContext", TimeContext.beijingTimeContext());
        return new RenderedPrompt(PromptRenderer.render(template, variables), template.key(), template.version());
    }

    /**
     * 格式化注入模型可见的用户问题与证据块。
     *
     * <p>结构性隔离：问题与文档正文均经 {@link KnowledgePromptText#escapeXmlText} 转义，
     * 无法伪造 {@code </SOURCES>} 等标签提前闭合数据区；数据区之后立即附加信任边界重申
     * （该重申段是 PromptPack 正文的一部分）。</p>
     */
    public String userPayload(String userQuestion, String promptSources, String conversationContext) {
        PromptTemplate template = prompts.require(PromptKeys.KNOWLEDGE_USER);
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("question", KnowledgePromptText.escapeXmlText(userQuestion));
        variables.put("sources", promptSources != null ? promptSources : "");
        String body = PromptRenderer.render(template, variables);
        if (conversationContext == null || conversationContext.isBlank()) {
            return body;
        }
        return conversationContext.strip() + "\n\n" + body;
    }
}
