package com.shitulelv.aicollab.agent.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 暂停运行下的续跑意图识别（纯函数）。
 * 边界：多种明确续跑说法（含礼貌语气、空白、标点）恢复；否定、询问、
 * 带新要求与普通新内容不得直接续跑。
 */
class ResumeIntentRecognizerTest {

    @Test
    void explicitResumePhrasesAreRecognized() {
        String[] resumes = {
                "继续", "继续吧", "继续。", "请继续", "麻烦继续",
                "  继续  ", "继续执行", "继续做", "继续干", "接着做", "接着干",
                "恢复执行", "恢复", "继续任务", "继续工作", "继续刚才的任务",
                "继续之前的任务", "继续剩下的", "继续未完成的任务",
                "好的，继续", "可以的话继续吧", "继续!", "继续？",
                "把剩下的做完", "把剩余的任务做完", "做完剩下的", "把未完成的做完",
        };
        for (String input : resumes) {
            assertThat(ResumeIntentRecognizer.classify(input))
                    .as("应识别为续跑意图: %s", input)
                    .isEqualTo(ResumeIntentRecognizer.Intent.RESUME);
        }
    }

    @Test
    void negationIsNeverAResume() {
        String[] declines = {
                "不继续了", "不要继续", "先不继续", "暂不继续", "不用继续",
                "无需继续", "不再继续了", "别继续了", "我不继续了",
        };
        for (String input : declines) {
            assertThat(ResumeIntentRecognizer.classify(input))
                    .as("否定表达不得恢复: %s", input)
                    .isEqualTo(ResumeIntentRecognizer.Intent.UNRELATED);
        }
    }

    @Test
    void questionsAndCapabilityAsksAreAmbiguous() {
        String[] asks = {
                "如何继续", "怎么继续", "能不能继续", "可以继续吗", "继续吗",
                "是否要继续", "继续吗？", "继续还是重新开始？",
        };
        for (String input : asks) {
            assertThat(ResumeIntentRecognizer.classify(input))
                    .as("询问能力不得直接恢复: %s", input)
                    .isEqualTo(ResumeIntentRecognizer.Intent.AMBIGUOUS);
        }
    }

    @Test
    void newGoalAttachedToResumeWordIsAmbiguous() {
        String[] mixed = {
                "继续检查另一个项目", "继续刚才的任务，把风险也看一下",
                "继续，并把截止日期改成周五", "继续生成另一份规划",
                "继续另外整理一下进度",
        };
        for (String input : mixed) {
            assertThat(ResumeIntentRecognizer.classify(input))
                    .as("带新要求的输入不得直接恢复: %s", input)
                    .isEqualTo(ResumeIntentRecognizer.Intent.AMBIGUOUS);
        }
    }

    @Test
    void unrelatedContentIsNotRoutedToResume() {
        String[] unrelated = {
                "", "   ", "检查本周风险，并给出来源", "整理项目进度",
                "生成下一步行动建议", "暂停是什么意思", "任务状态如何",
        };
        for (String input : unrelated) {
            assertThat(ResumeIntentRecognizer.classify(input))
                    .as("普通内容不得分流到 resume: %s", input)
                    .isEqualTo(ResumeIntentRecognizer.Intent.UNRELATED);
        }
    }
}
