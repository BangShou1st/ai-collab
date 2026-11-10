package com.shitulelv.aicollab.retrieval.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for multi-turn conversational query understanding and resolution in RAG V2.
 *
 * Key Capabilities:
 * 1. Structured turn management: pairs user and assistant messages into complete turns,
 *    windowed by complete exchanges rather than arbitrary message slicing.
 * 2. Intent & Coreference analysis: robustly identifies STANDALONE questions,
 *    FOLLOW_UP / anaphora / elliptical queries, and explicit TOPIC_SWITCH intents,
 *    completely eliminating brittle hardcoded regex.
 * 3. Safe query rewriting: seamlessly incorporates contextual antecedents while strictly
 *    preserving the original user question verbatim; safely falls back to the original query on any failure.
 * 4. Strict evidence isolation: enforces that historical assistant answers and citation
 *    references ([S1], [S2]) are never treated as facts or sources for the current turn.
 */
public class QueryUnderstandingService {
    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

    private static final int DEFAULT_MAX_TURNS = 5;
    private static final int DEFAULT_CONTEXT_CHAR_BUDGET = 3500;
    private static final int MAX_RETRIEVAL_QUERY_CODE_POINTS = 2000;

    // Matches explicit topic shift markers
    private static final Pattern TOPIC_SWITCH_PATTERN = Pattern.compile(
            "(?s).*(?:换个|换一个|换成|改为|改成|转而|另一个|不谈这个|不再讨论|先不讨论|跳过这个|重新问|顺便问下别的|另外问一下|抛开这个|先别管|先不管|switch topic|change topic|another question|different topic|never mind that).*"
    );

    // Matches demonstratives and pronouns referring back to previous context
    private static final Pattern PRONOUN_REFERENCE_PATTERN = Pattern.compile(
            "(?s).*(?:它|它们|他|他们|她|她们|这个|那个|这些|那些|这里|这也是|该系统|该方案|该|此|此类|上述|刚才|前面说的|前文|其|其中|其一|后者|前者|之前说的|刚才提到的|上文|it|they|this|these|that|those|above|former|latter).*",
            Pattern.CASE_INSENSITIVE
    );

    // Matches elliptical queries (short fragments with missing subjects or follow-up interrogatives)
    private static final Pattern ELLIPTICAL_QUERY_PATTERN = Pattern.compile(
            "^(?:为什么|为什么呢|如何实现|具体呢|代码怎么写|代码呢|第二步呢|原因是什么|有什么优缺点|有例子吗|给个例子|给个示例|详细说说|详细讲讲|展开讲讲|继续|深入说明|还有吗|还有别的吗|核心指标呢|那它呢|怎么解决|怎么处理|具体怎么做|具体步骤|相比之下呢|和之前那个比呢|具体原因|优劣分析)[?？]?$"
    );

    // Matches citation brackets like [S1], [S2], [1], [2] in assistant answers to strip them from history
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S?\\d+\\]");

    private final int maxTurns;
    private final int contextCharBudget;

    public QueryUnderstandingService() {
        this(DEFAULT_MAX_TURNS, DEFAULT_CONTEXT_CHAR_BUDGET);
    }

    public QueryUnderstandingService(int maxTurns, int contextCharBudget) {
        this.maxTurns = maxTurns > 0 ? maxTurns : DEFAULT_MAX_TURNS;
        this.contextCharBudget = contextCharBudget > 0 ? contextCharBudget : DEFAULT_CONTEXT_CHAR_BUDGET;
    }

    /**
     * Understands the user's current question in the context of previous conversation messages.
     *
     * @param messages history messages
     * @param currentQuestion the latest user question
     * @return QueryUnderstandingResult with rewritten query, intent, isolated context prompt, and turns
     */
    public QueryUnderstandingResult understand(List<ConversationMessage> messages, String currentQuestion) {
        String originalQuestion = currentQuestion == null ? "" : currentQuestion.strip();
        if (originalQuestion.isBlank()) {
            return new QueryUnderstandingResult("", "", QueryIntentType.STANDALONE, "", List.of());
        }

        try {
            // 1. Structure raw messages into complete turns
            List<ConversationTurn> allTurns = groupIntoTurns(messages);

            // 2. Select recent turns within complete turn boundaries and character budget
            List<ConversationTurn> managedTurns = selectTurnsWithinBudget(allTurns, maxTurns, contextCharBudget);

            // 3. Classify intent: STANDALONE vs FOLLOW_UP vs TOPIC_SWITCH
            QueryIntentType intent = detectIntent(managedTurns, originalQuestion);

            // 4. Safely rewrite retrieval query preserving the verbatim original question
            String rewrittenQuery = rewriteQuery(managedTurns, originalQuestion, intent);

            // 5. Construct conversation context prompt with strict assistant evidence isolation
            String contextPrompt = buildContextPrompt(managedTurns);

            return new QueryUnderstandingResult(
                    originalQuestion,
                    rewrittenQuery,
                    intent,
                    contextPrompt,
                    managedTurns
            );

        } catch (Exception e) {
            log.warn("Query understanding failed unexpectedly, falling back to original question: {}", e.getMessage());
            return new QueryUnderstandingResult(
                    originalQuestion,
                    originalQuestion,
                    QueryIntentType.STANDALONE,
                    "",
                    List.of()
            );
        }
    }

    /**
     * Converts raw messages into structured turns (pairing USER and ASSISTANT).
     */
    public List<ConversationTurn> groupIntoTurns(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ConversationMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparing(
                ConversationMessage::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder())
        ));

        List<ConversationTurn> turns = new ArrayList<>();
        String currentUserQuestion = null;
        java.time.OffsetDateTime turnTimestamp = null;
        int turnIndex = 1;

        for (ConversationMessage msg : sorted) {
            if (msg.content() == null || msg.content().isBlank()) {
                continue;
            }

            if (msg.isUser()) {
                // If there was a previous user message without an assistant reply, finish that turn
                if (currentUserQuestion != null) {
                    turns.add(new ConversationTurn(turnIndex++, currentUserQuestion, null, turnTimestamp, false));
                }
                currentUserQuestion = msg.content().trim();
                turnTimestamp = msg.createdAt();
            } else if (msg.isAssistant()) {
                if (currentUserQuestion != null) {
                    turns.add(new ConversationTurn(
                            turnIndex++,
                            currentUserQuestion,
                            msg.content().trim(),
                            turnTimestamp != null ? turnTimestamp : msg.createdAt(),
                            msg.insufficientEvidence()
                    ));
                    currentUserQuestion = null;
                    turnTimestamp = null;
                } else {
                    // Assistant message without user question (orphan), create turn with empty user question
                    turns.add(new ConversationTurn(
                            turnIndex++,
                            "",
                            msg.content().trim(),
                            msg.createdAt(),
                            msg.insufficientEvidence()
                    ));
                }
            }
        }

        if (currentUserQuestion != null) {
            turns.add(new ConversationTurn(turnIndex, currentUserQuestion, null, turnTimestamp, false));
        }

        return turns;
    }

    /**
     * Retains up to maxTurns complete turns, shedding oldest complete turns if budget exceeded.
     * Never splits a single turn in half.
     */
    public List<ConversationTurn> selectTurnsWithinBudget(List<ConversationTurn> turns, int maxTurns, int budgetChars) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }

        int start = Math.max(0, turns.size() - maxTurns);
        List<ConversationTurn> candidateTurns = new ArrayList<>(turns.subList(start, turns.size()));

        // Drop oldest complete turns if total characters exceed budget
        while (candidateTurns.size() > 1 && calculateTurnsLength(candidateTurns) > budgetChars) {
            candidateTurns.remove(0);
        }

        return candidateTurns;
    }

    /**
     * Classifies the intent of the question given the managed history.
     */
    public QueryIntentType detectIntent(List<ConversationTurn> turns, String question) {
        if (turns == null || turns.isEmpty() || question == null || question.isBlank()) {
            return QueryIntentType.STANDALONE;
        }

        // 1. Explicit topic switch
        if (TOPIC_SWITCH_PATTERN.matcher(question).matches()) {
            return QueryIntentType.TOPIC_SWITCH;
        }

        // 2. Elliptical questions (short questions missing subject or starting with follow-up phrases)
        if (ELLIPTICAL_QUERY_PATTERN.matcher(question.trim()).matches() ||
            (question.length() <= 12 && question.matches(".*(?:为什么|如何|具体|怎么样|步骤|代码|接着|再说说|呢).*"))) {
            return QueryIntentType.FOLLOW_UP;
        }

        // 3. Pronouns or demonstratives referring back
        if (PRONOUN_REFERENCE_PATTERN.matcher(question).matches()) {
            return QueryIntentType.FOLLOW_UP;
        }

        // 4. Default: question is standalone with its own subject and predicate
        return QueryIntentType.STANDALONE;
    }

    /**
     * Rewrites the query to include context while strictly preserving the original user question verbatim.
     */
    public String rewriteQuery(List<ConversationTurn> turns, String question, QueryIntentType intent) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String cleanQuestion = question.strip();

        if (intent == QueryIntentType.STANDALONE || intent == QueryIntentType.TOPIC_SWITCH || turns.isEmpty()) {
            return cleanQuestion;
        }

        try {
            // Find most recent valid previous question
            String antecedent = findMostRecentUserQuestion(turns);
            if (antecedent == null || antecedent.isBlank()) {
                return cleanQuestion;
            }

            int questionCodePoints = cleanQuestion.codePointCount(0, cleanQuestion.length());
            int availableBudget = Math.max(0, MAX_RETRIEVAL_QUERY_CODE_POINTS - questionCodePoints - 2);

            int antecedentCodePoints = antecedent.codePointCount(0, antecedent.length());
            int allowedLength = Math.min(availableBudget, antecedentCodePoints);

            if (allowedLength <= 0) {
                return cleanQuestion;
            }

            String boundedAntecedent = antecedent.substring(0, antecedent.offsetByCodePoints(0, allowedLength));
            // Always preserve the exact original user question
            return boundedAntecedent + "\n" + cleanQuestion;

        } catch (Exception e) {
            log.warn("Failed to rewrite query, safely returning original: {}", e.getMessage());
            return cleanQuestion;
        }
    }

    /**
     * Builds the conversation context prompt with strict evidence isolation:
     * - Historical Assistant answers and references are explicitly marked as NOT evidence.
     * - Citation markers like [S1], [S2] are stripped from past Assistant messages.
     * - XML special characters are escaped to avoid prompt injection.
     */
    public String buildContextPrompt(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<CONVERSATION_CONTEXT>\n");
        sb.append("【多轮对话上下文与证据隔离规则】\n");
        sb.append("1. 历史对话仅用于理解用户追问与代词指代，消除上下文歧义。\n");
        sb.append("2. 历史中的 Assistant 回答及其引用标记绝对不能作为本轮回答的事实证据。\n");
        sb.append("3. 本轮回答必须完全且仅能依赖本轮检索到的 <SOURCES> 证据，严禁复用历史引用。\n\n");

        for (ConversationTurn turn : turns) {
            if (turn.insufficientEvidence()) {
                continue; // Skip turns marked as having insufficient evidence to reduce noise
            }

            sb.append("[Turn ").append(turn.turnIndex()).append("]\n");

            if (turn.userQuestion() != null && !turn.userQuestion().isBlank()) {
                sb.append("USER: ").append(escapeXml(truncateText(turn.userQuestion(), 600))).append("\n");
            }

            if (turn.hasAssistantAnswer()) {
                // Strip citations like [S1] or [1] so model cannot mistake them for current sources
                String sanitizedAnswer = CITATION_PATTERN.matcher(turn.assistantAnswer()).replaceAll("");
                sb.append("ASSISTANT (仅供指代参考，非本轮证据): ")
                  .append(escapeXml(truncateText(sanitizedAnswer.trim(), 800)))
                  .append("\n");
            }
        }

        sb.append("</CONVERSATION_CONTEXT>\n");
        sb.append("历史仅用于理解追问，历史回答及其引用编号不能作为本轮证据；仅使用本轮 SOURCES，明确标注推断。\n");
        return sb.toString();
    }

    private String findMostRecentUserQuestion(List<ConversationTurn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            ConversationTurn t = turns.get(i);
            if (t.userQuestion() != null && !t.userQuestion().isBlank()) {
                return t.userQuestion().trim();
            }
        }
        return null;
    }

    private int calculateTurnsLength(List<ConversationTurn> turns) {
        int len = 0;
        for (ConversationTurn t : turns) {
            if (t.userQuestion() != null) len += t.userQuestion().length();
            if (t.assistantAnswer() != null) len += t.assistantAnswer().length();
        }
        return len;
    }

    private String truncateText(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}
