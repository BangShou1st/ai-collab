# Rollback triggers

Start a rollback if the API error rate remains above 2 percent for five continuous minutes, or if any critical write operation fails. Either condition is sufficient; the team does not need to wait for both conditions to occur.

# Rollback deadline

Complete the rollback within ten minutes after the first trigger is observed. Record which trigger occurred and the time rollback began so the incident can be reviewed afterward.
