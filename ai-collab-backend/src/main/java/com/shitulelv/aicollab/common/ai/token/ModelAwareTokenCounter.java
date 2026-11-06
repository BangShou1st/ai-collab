package com.shitulelv.aicollab.common.ai.token;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ModelAwareTokenCounter implements TokenCounter {

    @Override
    public TokenCount countText(String text, String providerType, String modelName) {
        if (text == null || text.isEmpty()) {
            return TokenCount.zero();
        }

        String normalizedModel = modelName != null ? modelName.toLowerCase(Locale.ROOT) : "";
        String normalizedProvider = providerType != null ? providerType.toUpperCase(Locale.ROOT) : "";

        // 模型特定权重系数
        double cjkFactor = 0.95;
        double asciiWordFactor = 1.3;
        double codeSymbolFactor = 0.6;
        String basis = "CALIBRATED_HEURISTIC_V2";

        if (normalizedModel.contains("qwen") || normalizedModel.contains("deepseek")) {
            // 中文大模型原生分词器对中文压缩率极高，1汉字约 0.6-0.75 Token
            cjkFactor = 0.75;
            basis = "QWEN_DEEPSEEK_CALIBRATED";
        } else if (normalizedModel.contains("claude")) {
            // Claude 对中文 1汉字约 1.0-1.3 Token
            cjkFactor = 1.15;
            basis = "CLAUDE_CALIBRATED";
        } else if (normalizedModel.contains("gemini")) {
            // Gemini 对中文 1汉字约 0.8-1.0 Token
            cjkFactor = 0.90;
            basis = "GEMINI_CALIBRATED";
        } else if (normalizedModel.contains("gpt-4") || normalizedModel.contains("o1") || normalizedModel.contains("o3")) {
            // OpenAI o1/gpt-4o
            cjkFactor = 0.85;
            basis = "OAI_O_SERIES_CALIBRATED";
        }

        int cjkCount = 0;
        int asciiAlphaNumCount = 0;
        int symbolAndWhitespaceCount = 0;

        int len = text.length();
        for (int i = 0; i < len; ) {
            int cp = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjkCount++;
            } else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')) {
                asciiAlphaNumCount++;
            } else {
                symbolAndWhitespaceCount++;
            }
            i += Character.charCount(cp);
        }

        // 英文按平均 4 个字母一词计算 Token，标点和空白按 0.4 Token
        double tokenEstimate = (cjkCount * cjkFactor)
                + (asciiAlphaNumCount / 4.0 * asciiWordFactor)
                + (symbolAndWhitespaceCount * codeSymbolFactor);

        int count = Math.max(1, (int) Math.ceil(tokenEstimate));
        return TokenCount.estimated(count, basis);
    }

    @Override
    public TokenCount countMessages(List<?> messages, String providerType, String modelName) {
        if (messages == null || messages.isEmpty()) {
            return TokenCount.zero();
        }

        int total = 0;
        boolean estimated = true;
        for (Object msg : messages) {
            total += 4; // 协议包装开销 per message (role, headers)
            if (msg != null) {
                String text = msg.toString();
                TokenCount tc = countText(text, providerType, modelName);
                total += tc.count();
            }
        }
        total += 3; // primer overhead
        return TokenCount.estimated(total, "MESSAGE_PROTOCOL_CALIBRATED");
    }

    @Override
    public TokenCount countToolDefinitions(List<?> tools, String providerType, String modelName) {
        if (tools == null || tools.isEmpty()) {
            return TokenCount.zero();
        }
        int total = 0;
        for (Object tool : tools) {
            total += 20; // 基本 schema 开销
            if (tool != null) {
                total += countText(tool.toString(), providerType, modelName).count();
            }
        }
        return TokenCount.estimated(total, "TOOL_SCHEMA_ESTIMATED");
    }
}
