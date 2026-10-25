CREATE INDEX idx_agent_tool_invocation_unknown_age
    ON agent_tool_invocation ((COALESCE(dispatch_started_at, updated_at)))
    WHERE status = 'UNKNOWN';
