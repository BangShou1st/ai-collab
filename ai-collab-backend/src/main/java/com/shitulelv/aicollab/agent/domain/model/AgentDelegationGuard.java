package com.shitulelv.aicollab.agent.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 委派防重台账的领域策略（纯函数，无数据库、无配置、可单测）。
 *
 * <p>目标：抓"换个说法重复委派"——父运行用大小写、标点、空白略有差异的
 * objective 反复开新子 Agent，绕开按 run 隔离的循环防护。归一化把这类
 * 表面变体折叠为同一键，哈希后作为 {@code agent_delegation_ledger}
 * 的判重依据（同一 session 下同哈希存在非 CANCELED 记录即拒绝）。</p>
 *
 * <p>归一化规则（设计文档 §3.2）：小写化 → 去标点与空白折叠（连续空白/标点
 * 压成单个空格）→ trim → 截断 512 字符。中文字符是字母（不被剔除），
 * 只剔除标点与空白类字符。</p>
 */
public final class AgentDelegationGuard {

    /** 归一化结果的最大长度：委派 objective 上限 2000 字符，哈希键统一截断到 512。 */
    public static final int MAX_NORMALIZED_LENGTH = 512;

    private AgentDelegationGuard() {}

    /**
     * 归一化委派 objective：小写化 → 非字母数字字符（标点/空白）压成空格并折叠
     * 连续空白 → trim → 截断 512 字符。null 或纯空白返回空串。
     */
    public static String normalizeObjective(String objective) {
        if (objective == null || objective.isBlank()) {
            return "";
        }
        String lowered = objective.toLowerCase(Locale.ROOT);
        StringBuilder collapsed = new StringBuilder(lowered.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (pendingSpace && collapsed.length() > 0) {
                    collapsed.append(' ');
                }
                pendingSpace = false;
                collapsed.append(ch);
            } else {
                // 标点与空白统一视为分隔符，连续出现压成一个空格
                pendingSpace = true;
            }
        }
        String result = collapsed.toString();
        if (result.length() > MAX_NORMALIZED_LENGTH) {
            return result.substring(0, MAX_NORMALIZED_LENGTH);
        }
        return result;
    }

    /**
     * objective 的判重哈希：sha256(normalize(objective)) 的小写十六进制串（64 字符）。
     * 空 objective 同样产生确定性哈希（sha256("")），由调用方决定是否放行。
     */
    public static String objectiveHash(String objective) {
        return sha256Hex(normalizeObjective(objective));
    }

    /**
     * 纯函数判重：给定"同 session 下同 objective_hash 的现存非 CANCELED 台账记录数"，
     * 返回本次委派是否重复（大于 0 即重复，包括 RUNNING/COMPLETED/FAILED）。
     */
    public static boolean isDuplicateObjective(int activeRecords) {
        return activeRecords > 0;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // JDK 必带 SHA-256，不可达
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
