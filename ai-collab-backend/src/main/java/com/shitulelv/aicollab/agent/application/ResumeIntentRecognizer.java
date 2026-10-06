package com.shitulelv.aicollab.agent.application;

/**
 * 暂停运行下用户输入的续跑意图识别（纯函数，无模型请求、无关键词库平台）。
 *
 * <p>三值判定：</p>
 * <ul>
 *   <li>{@code RESUME}：输入整体就是一句明确的续跑表达（"继续""请继续""继续吧"
 *       "接着做""恢复执行""继续刚才的任务""把剩下的做完"等），可分流到 resume 用例；</li>
 *   <li>{@code AMBIGUOUS}：含有"继续/接着/恢复"等字样但带新要求、询问或否定语境
 *       （"继续刚才的任务，把风险也看一下""如何继续""继续检查另一个项目"），
 *       不能直接续跑，需要澄清；</li>
 *   <li>{@code UNRELATED}：与续跑无关的普通内容（含"不继续了"等否定表达）。</li>
 * </ul>
 *
 * <p>识别只是"是否为一句完整续跑话"的保守判断：只有整句匹配续跑短语才算 RESUME，
 * 看到子串一律不算，避免把新目标误当控制操作。</p>
 */
public final class ResumeIntentRecognizer {

    public enum Intent { RESUME, AMBIGUOUS, UNRELATED }

    private ResumeIntentRecognizer() {
    }

    /** 续跑动词：句首出现这些词才可能构成续跑表达。 */
    private static final String RESUME_VERBS = "继续|接着|恢复";

    /** 动词后允许出现的修饰/语气字符集：覆盖"吧/执行/做/任务/刚才的/剩下的"等组合，
     *  出现集合之外的实词（"检查""生成""另一个"）即视为带新要求。 */
    private static final String RESUME_TAIL_CHARS = "执行做干吧呀啊哦哈呗啦了嘞任务工作事情先头前刚才之那的剩下余未完成";

    /** 前置礼貌/过渡语：匹配前剥离（"好的，继续""可以的话继续吧"）。 */
    private static final java.util.regex.Pattern POLITE_PREFIX = java.util.regex.Pattern
            .compile("^(好了|好的|行|可以|那|麻烦|辛苦|劳烦|请|帮忙|帮我|给我)+(的话)?[，,。.\\s]*");

    /** 恢复类特殊句式：动词集合表达不了的"把剩下的做完"。 */
    private static final java.util.regex.Pattern FINISH_REST = java.util.regex.Pattern
            .compile("^把(剩下|剩余|未完成)的(任务|工作|事情)?(全部)?(做完|完成|干完|做完吧)$"
                    + "|^(做完|干完|完成)(剩下|剩余)的(任务|工作|事情)?(吧)?$");

    public static Intent classify(String input) {
        if (input == null) return Intent.UNRELATED;
        String normalized = input.replaceAll("\\s+", "").replaceAll("^[。．,!！?？~、;；:：]+|[。．,!！?？~、;；:：]+$", "");
        if (normalized.isEmpty()) return Intent.UNRELATED;
        String candidate = POLITE_PREFIX.matcher(normalized).replaceFirst("");
        if (candidate.isEmpty()) return Intent.UNRELATED;
        if (FINISH_REST.matcher(candidate).matches()) return Intent.RESUME;
        // 否定语境：明确不继续，属于普通内容（暂停态下走引导，不恢复）
        if (candidate.matches("^(先|暂)?不(要|再|用|必|需|须)?(继续|接着|恢复).*$")
                || candidate.contains("不继续") || candidate.contains("不再继续")
                || candidate.contains("不要继续") || candidate.contains("不用继续")
                || candidate.contains("先不继续") || candidate.contains("暂不继续")
                || candidate.contains("无需继续") || candidate.contains("无须继续")
                || candidate.contains("别继续")) {
            return Intent.UNRELATED;
        }
        if (!candidate.matches("(?s)^.*(" + RESUME_VERBS + ").*$")) return Intent.UNRELATED;
        // 疑问/询问语气：不是控制操作（"如何继续""可以继续吗"）
        if (candidate.contains("是否") || candidate.contains("吗") || candidate.contains("呢")
                || candidate.contains("能不能") || candidate.contains("可不可以") || candidate.contains("要不要")
                || candidate.contains("怎么") || candidate.contains("如何") || candidate.contains("怎样")
                || candidate.contains("?") || candidate.contains("？")) {
            return Intent.AMBIGUOUS;
        }
        // 整句必须由动词 + 允许的修饰字符组成：带新要求（"继续，把截止日期改成周五"）则澄清
        if (candidate.matches("^(" + RESUME_VERBS + ")[" + RESUME_TAIL_CHARS + "]*$")) {
            return Intent.RESUME;
        }
        return Intent.AMBIGUOUS;
    }
}
