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
    /**
     * 受限引用标记：单项 {@code [S1]} 与逗号分组 {@code [S1, S2]}（成员间可有空白）。
     * 分组内成员逐个校验：合法成员保留引用身份，非法成员移除；全部非法时移除整组。
     * 该校验只证明「引用编号在合法且与来源身份一致」（citation_number_valid），
     * 不证明引文正文语义上支持对应结论（citation_claim_supported，需人工审查）。
     */
    private static final Pattern CITATION = Pattern.compile("\\[(S\\d+(?:\\s*,\\s*S\\d+)*)]");

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
            String group = matcher.group();
            String[] members = group.substring(1, group.length() - 1).split(",");
            List<Integer> validMembers = new ArrayList<>(members.length);
            for (String member : members) {
                int rank = parseRank(member);
                if (byRank.containsKey(rank)) {
                    validRanks.add(rank);
                    validMembers.add(rank);
                } else {
                    invalid++;
                }
            }
            if (validMembers.isEmpty()) {
                matcher.appendReplacement(cleaned, "");
            } else if (validMembers.size() == 1 && members.length == 1) {
                // 单项引用维持历史归一化行为：[S01] -> [S1]。
                matcher.appendReplacement(cleaned,
                        Matcher.quoteReplacement("[S" + validMembers.getFirst() + "]"));
            } else if (validMembers.size() == members.length) {
                matcher.appendReplacement(cleaned, Matcher.quoteReplacement(group));
            } else {
                String survivors = validMembers.stream().map(rank -> "S" + rank)
                        .collect(Collectors.joining(", "));
                matcher.appendReplacement(cleaned, Matcher.quoteReplacement("[" + survivors + "]"));
            }
        }
        matcher.appendTail(cleaned);

        List<KnowledgeSource> cited = new ArrayList<>(validRanks.size());
        for (Integer rank : validRanks) {
            cited.add(byRank.get(rank));
        }
        String cleanedAnswer = cleaned.toString().replaceAll("[ \\t]+\\n", "\n").strip();
        if (cleanedAnswer.isBlank() || validRanks.isEmpty()) {
            return insufficient(invalid, true);
        }
        return new ValidatedKnowledgeAnswer(
                cleanedAnswer,
                false,
                List.copyOf(cited),
                invalid,
                false);
    }

    private static int parseRank(String member) {
        String value = member.strip();
        if (!value.startsWith("S")) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static ValidatedKnowledgeAnswer insufficient(int invalid, boolean invalidOutput) {
        return new ValidatedKnowledgeAnswer(
                INSUFFICIENT_ANSWER, true, List.of(), invalid, invalidOutput);
    }
}
