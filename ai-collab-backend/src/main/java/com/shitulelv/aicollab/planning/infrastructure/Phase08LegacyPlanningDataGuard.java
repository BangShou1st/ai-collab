package com.shitulelv.aicollab.planning.infrastructure;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

@Component
public final class Phase08LegacyPlanningDataGuard implements Callback {
    private static final MigrationVersion DESTRUCTIVE_MIGRATION = MigrationVersion.fromVersion("5");
    private static final String LEGACY_DATA_MESSAGE =
            "Phase 04 planning data must be exported or migrated before applying V5";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (context.getMigrationInfo() == null
                || !DESTRUCTIVE_MIGRATION.equals(context.getMigrationInfo().getVersion())) {
            return;
        }
        try {
            try (var statement = context.getConnection().prepareStatement(
                    "SELECT to_regclass('ai_task_plan')");
                 var result = statement.executeQuery()) {
                if (!result.next() || result.getObject(1) == null) {
                    return;
                }
            }
            try (var statement = context.getConnection().prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM ai_task_plan LIMIT 1)");
                 var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    return;
                }
                throw new FlywayException(LEGACY_DATA_MESSAGE);
            }
        } catch (SQLException exception) {
            throw new FlywayException("Unable to verify Phase 04 planning data before applying V5", exception);
        }
    }

    @Override
    public String getCallbackName() {
        return "Phase 08 legacy planning data guard";
    }
}
