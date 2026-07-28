package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class KnowledgeCitationValidator {
    public static final String INSUFFICIENT_ANSWER = "当前项目资料不足以回答该问题";
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");

    public ValidatedKnowledgeAnswer validate(String answer, List<KnowledgeSource> sources) {
        if (answer == null || answer.isBlank()) {
            return insufficient(0, true);
        }
        String normalizedAnswer = answer.strip();
        if (INSUFFICIENT_ANSWER.equals(normalizedAnswer)) {
            return insufficient(0, false);
        }

        Map<Integer, KnowledgeSource> byRank = sources.stream()
                .collect(Collectors.toMap(KnowledgeSource::rank, Function.identity()));
        Matcher matcher = CITATION.matcher(normalizedAnswer);
        StringBuffer cleaned = new StringBuffer();
        Set<Integer> validRanks = new LinkedHashSet<>();
        int invalid = 0;
        while (matcher.find()) {
            int rank;
            try {
                rank = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                rank = -1;
            }
            if (byRank.containsKey(rank)) {
                validRanks.add(rank);
                matcher.appendReplacement(
                        cleaned, Matcher.quoteReplacement("[S" + rank + "]"));
            } else {
                invalid++;
                matcher.appendReplacement(cleaned, "");
            }
        }
        matcher.appendTail(cleaned);

        List<KnowledgeSource> cited = new ArrayList<>(validRanks.size());
        for (Integer rank : validRanks) {
            cited.add(byRank.get(rank));
        }
        String cleanedAnswer = cleaned.toString().replaceAll("[ \\t]+\\n", "\n").strip();
        if (cleanedAnswer.isBlank()) {
            return insufficient(invalid, true);
        }
        // 即使没有引用来源，也展示 AI 的回答（不判为证据不足）
        return new ValidatedKnowledgeAnswer(
                cleanedAnswer,
                false,
                List.copyOf(cited),
                invalid,
                false);
    }

    private static ValidatedKnowledgeAnswer insufficient(int invalid, boolean invalidOutput) {
        return new ValidatedKnowledgeAnswer(
                INSUFFICIENT_ANSWER, true, List.of(), invalid, invalidOutput);
    }
}
