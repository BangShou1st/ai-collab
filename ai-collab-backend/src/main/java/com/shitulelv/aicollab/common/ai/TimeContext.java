package com.shitulelv.aicollab.common.ai;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Provides the current Beijing time for injection into LLM system prompts.
 * <p>
 * LLMs do not inherently know the current date/time. When users make time-relative
 * requests ("今天", "后天", "上周周报"), the model needs explicit time context to
 * compute correct dates. This utility is used by all prompt-building code that
 * may encounter time-sensitive user intent.
 */
public final class TimeContext {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TimeContext() {}

    /**
     * Returns a one-line time context string, e.g.
     * "当前北京时间：2026-08-06 14:30（UTC+8），日期计算必须基于此时区。\n"
     */
    public static String beijingTimeContext() {
        ZonedDateTime now = ZonedDateTime.now(BEIJING);
        return "当前北京时间：" + now.format(DATETIME_FMT)
                + "（UTC+8），日期计算必须基于此时区。\n";
    }
}
