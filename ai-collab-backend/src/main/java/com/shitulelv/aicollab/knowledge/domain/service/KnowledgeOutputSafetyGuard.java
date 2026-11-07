package com.shitulelv.aicollab.knowledge.domain.service;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 知识问答<b>有界输出校验</b>（P10 安全收尾）：对最终回答做确定性的系统提示词泄漏检测。
 *
 * <p>设计：以 k-gram（默认 16 个 code point）窗口把 System Prompt 切成指纹集合，再对回答滑窗比对。
 * 命中即认为回答复述了系统提示词片段——这是文档注入攻击最典型的目标行为之一（"输出系统提示词的完整内容"），
 * 不能只依赖提示词约束，需要确定性校验兜底。</p>
 *
 * <p>误报控制：k=16 意味着偶然重合概率极低；知识问答的统一拒答文案
 * （"当前项目资料不足以回答该问题"，14 字）短于 k，普通拒答不会误伤；引用文档原文不受影响
 * （比对对象仅是 System Prompt，而文档正文来自数据库，与本类提示词模板无文本重合）。</p>
 *
 * <p>流式边界：SSE 已发出的增量内容无法撤回；本守卫在流式链路中作用于<b>累积输出</b>，
 * 命中时通过事件通道发出安全提示并中止后续输出，已发送内容的限制在链路层如实呈现。</p>
 */
@Component
public class KnowledgeOutputSafetyGuard {

    /** k-gram 窗口长度（code point）。须大于任何正常的统一拒答/引用文案长度。 */
    public static final int K_GRAM = 16;

    /** 命中时返回给用户的统一拦截文案。 */
    public static final String BLOCKED_ANSWER =
            "抱歉，该回答因包含不应披露的内部信息，已被安全策略拦截。请重新提问或联系管理员。";

    public GuardResult inspect(String systemPrompt, String answer) {
        if (systemPrompt == null || systemPrompt.isBlank()
                || answer == null || answer.isBlank()) {
            return GuardResult.clean();
        }
        Set<String> fingerprints = fingerprintSet(systemPrompt);
        if (fingerprints.isEmpty()) {
            return GuardResult.clean();
        }
        int answerCodePoints = answer.codePointCount(0, answer.length());
        for (int start = 0; start + K_GRAM <= answerCodePoints; start++) {
            int begin = answer.offsetByCodePoints(0, start);
            int end = answer.offsetByCodePoints(0, start + K_GRAM);
            if (fingerprints.contains(answer.substring(begin, end))) {
                return new GuardResult(true, answer.substring(begin, end));
            }
        }
        return GuardResult.clean();
    }

    /**
     * 供流式链路预检使用：先一次性生成 System Prompt 指纹集合，再对每个累积增量做廉价比对，
     * 使守卫可以在内容发送之前拦截泄漏（已发送内容无法撤回）。
     */
    public Set<String> fingerprintSet(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return Set.of();
        }
        return fingerprints(systemPrompt);
    }

    /** 判定文本中是否包含指纹集合中的任一 k-gram。 */
    public boolean containsAnyFingerprint(String text, Set<String> fingerprints) {
        if (text == null || text.isBlank() || fingerprints.isEmpty()) {
            return false;
        }
        int textCodePoints = text.codePointCount(0, text.length());
        for (int start = 0; start + K_GRAM <= textCodePoints; start++) {
            int begin = text.offsetByCodePoints(0, start);
            int end = text.offsetByCodePoints(0, start + K_GRAM);
            if (fingerprints.contains(text.substring(begin, end))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> fingerprints(String text) {
        int total = text.codePointCount(0, text.length());
        Set<String> set = new HashSet<>();
        for (int start = 0; start + K_GRAM <= total; start++) {
            int begin = text.offsetByCodePoints(0, start);
            int end = text.offsetByCodePoints(0, start + K_GRAM);
            set.add(text.substring(begin, end));
        }
        return set;
    }

    public record GuardResult(boolean leaked, String matchedSample) {
        private static final GuardResult CLEAN = new GuardResult(false, "");

        public static GuardResult clean() {
            return CLEAN;
        }
    }
}
