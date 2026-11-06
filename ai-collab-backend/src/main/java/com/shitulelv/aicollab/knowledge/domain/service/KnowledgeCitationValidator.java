package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class KnowledgeCitationValidator {
    public static final String INSUFFICIENT_ANSWER = "当前项目资料不足以回答该问题";
    private static final Pattern CITATION = Pattern.compile("\\[(S\\d+(?:\\s*,\\s*S\\d+)*)]");

    public ValidatedKnowledgeAnswer validate(String answer, List<KnowledgeSource> sources) {
        return validate(answer, sources, null);
    }

    public ValidatedKnowledgeAnswer validate(String answer, List<KnowledgeSource> sources, UUID expectedProjectId) {
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
                KnowledgeSource src = byRank.get(rank);
                if (src != null && isIdentityValid(src, expectedProjectId)) {
                    validRanks.add(rank);
                    validMembers.add(rank);
                } else {
                    invalid++;
                }
            }
            if (validMembers.isEmpty()) {
                matcher.appendReplacement(cleaned, "");
            } else if (validMembers.size() == 1 && members.length == 1) {
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

    private static boolean isIdentityValid(KnowledgeSource source, UUID expectedProjectId) {
        if (source == null || source.documentId() == null || source.chunkId() == null) {
            return false;
        }
        if (expectedProjectId != null && source.metadata() != null) {
            Object metaProjectId = source.metadata().get("projectId");
            if (metaProjectId != null && !expectedProjectId.toString().equalsIgnoreCase(metaProjectId.toString())) {
                return false;
            }
        }
        return true;
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
