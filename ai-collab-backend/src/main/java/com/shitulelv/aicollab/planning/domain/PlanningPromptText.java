package com.shitulelv.aicollab.planning.domain;

import java.util.ArrayList;
import java.util.List;

public final class PlanningPromptText {
    private PlanningPromptText() {}

    public static String escapeUntrusted(String value) {
        if (value == null) return "";
        return value.replace("</SOURCES>", "<\\/SOURCES>")
                .replace("</PROJECT_DATA>", "<\\/PROJECT_DATA>")
                .replace("</PLAN_INPUT>", "<\\/PLAN_INPUT>");
    }

    public static List<String> withinCodePointBudget(List<String> values, int budget) {
        List<String> selected = new ArrayList<>();
        int used = 0;
        for (String value : values) {
            int length = value.codePointCount(0, value.length());
            if (used + length > budget) break;
            selected.add(value);
            used += length;
        }
        return List.copyOf(selected);
    }
}
