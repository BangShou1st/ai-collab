package com.shitulelv.aicollab.agent.infrastructure.repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Repository
public class AgentRuntimeStateRepository {
    private final JdbcTemplate jdbc;
    public AgentRuntimeStateRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Snapshot(Map<String,Long> counts,long expired,long unknown,Instant oldest,Instant refreshed) {
        public Snapshot { counts=Map.copyOf(counts); }
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read() {
        Map<String,Long> counts=new HashMap<>();
        jdbc.query("SELECT status,count(*) AS count FROM agent_run GROUP BY status",rs->{counts.put(rs.getString("status"),rs.getLong("count"));});
        long expired=jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE status='RUNNING' AND lease_expires_at < now()",Long.class);
        return jdbc.queryForObject("""
            SELECT count(*) AS unknown_count,min(COALESCE(dispatch_started_at,updated_at)) AS oldest_since
            FROM agent_tool_invocation WHERE status='UNKNOWN'
            """,(rs,n)->{
                OffsetDateTime oldest=rs.getObject("oldest_since",OffsetDateTime.class);
                return new Snapshot(counts,expired,rs.getLong("unknown_count"),oldest==null?null:oldest.toInstant(),Instant.now());
            });
    }
}
