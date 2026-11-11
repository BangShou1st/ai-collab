package com.shitulelv.aicollab.common.ai.prompt;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 版本工具：格式 {@code ^\d{4}-\d{2}-\d{2}\.\d+$}，语义为
 * <b>按 (日期, 序号) 数值比较</b>。
 *
 * <p>典型陷阱：字符串序会让 {@code 2026-11-11.10} 小于 {@code 2026-11-11.2}。
 * 本类把日期解析为 {@link LocalDate}、把序号解析为 {@link BigInteger} 后比较，
 * 因此 {@code 2026-11-11.10 > 2026-11-11.2 > 2026-11-11.1} 恒成立。</p>
 *
 * <p>格式非法（含不存在的日期，如 {@code 2026-02-30}）一律视为资产损坏：
 * {@link #compare} / {@link #max} 抛 {@link IllegalArgumentException}，
 * {@link #isValid} 返回 {@code false}。用严格日期解析比冻结文档的纯格式约束更严一点，
 * 这是刻意的安全收紧——避免畸形日期被排序成"最新版本"。</p>
 */
public final class PromptVersion {

    /** 冻结格式：{@code YYYY-MM-DD.N}。 */
    public static final Pattern PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.\\d+$");

    /** 升序比较器；遇到非法版本抛 {@link IllegalArgumentException}。 */
    public static final Comparator<String> COMPARATOR = PromptVersion::compare;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);

    private PromptVersion() {
    }

    /** 格式（含日期合法性）是否有效；{@code null} 视为无效。 */
    public static boolean isValid(String version) {
        if (version == null) {
            return false;
        }
        try {
            parse(version);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * 按 (日期, 序号) 数值比较两个版本。
     *
     * @throws IllegalArgumentException 任一版本格式非法
     */
    public static int compare(String left, String right) {
        Parsed a = parse(left);
        Parsed b = parse(right);
        int byDate = a.date().compareTo(b.date());
        return byDate != 0 ? byDate : a.sequence().compareTo(b.sequence());
    }

    /**
     * 返回集合中的最大版本（按 {@link #COMPARATOR}）。
     *
     * @throws IllegalArgumentException 集合为空或含非法版本
     */
    public static String max(Collection<String> versions) {
        Objects.requireNonNull(versions, "versions");
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("版本集合不能为空");
        }
        // Stream.max 在单元素时不会调用比较器，必须先显式校验，避免非法版本被静默接受。
        for (String version : versions) {
            if (!isValid(version)) {
                throw new IllegalArgumentException("Prompt 版本格式非法: " + version);
            }
        }
        return versions.stream()
                .max(COMPARATOR)
                .orElseThrow(() -> new IllegalArgumentException("版本集合不能为空"));
    }

    private static Parsed parse(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Prompt 版本不能为空");
        }
        Matcher matcher = PATTERN.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Prompt 版本格式非法: " + version);
        }
        int dot = version.indexOf('.');
        String datePart = version.substring(0, dot);
        String sequencePart = version.substring(dot + 1);
        LocalDate date;
        try {
            date = LocalDate.parse(datePart, DATE_FORMAT);
        } catch (DateTimeParseException invalidDate) {
            throw new IllegalArgumentException("Prompt 版本日期非法: " + version);
        }
        return new Parsed(date, new BigInteger(sequencePart));
    }

    private record Parsed(LocalDate date, BigInteger sequence) {
    }
}
