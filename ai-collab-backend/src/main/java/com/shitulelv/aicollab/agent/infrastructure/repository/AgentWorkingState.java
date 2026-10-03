package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** Small durable user constraints; observations remain paired with their durable tool steps. */
final class AgentWorkingState {
    static void appendUser(JdbcTemplate jdbc, ObjectMapper json, UUID session, String request) {
        ObjectNode state=load(jdbc,json,session);
        boolean replace=request.startsWith("/replace ") || request.startsWith("新目标：");
        if (replace) state=json.createObjectNode();
        if (!state.has("goal")) state.put("goal",bounded(request,2000));
        ArrayNode constraints=json.createArrayNode();
        JsonNode previous=state.path("constraints");
        for (int i=Math.max(0,previous.size()-7); i<previous.size(); i++) constraints.add(previous.get(i));
        constraints.add(bounded(request,1000));
        state.set("constraints",constraints);
        state.put("latestRequest",bounded(request,2000));
        state.put("goalVersion",state.path("goalVersion").asInt()+1);
        state.remove("pendingQuestion");
        save(jdbc,session,state);
    }
    static void question(JdbcTemplate jdbc,ObjectMapper json,UUID session,String question) {
        ObjectNode state=load(jdbc,json,session); state.put("pendingQuestion",bounded(question,2000)); save(jdbc,session,state);
    }
    private static ObjectNode load(JdbcTemplate jdbc,ObjectMapper json,UUID session) {
        String value=jdbc.queryForObject("SELECT working_state::text FROM agent_session WHERE id=? FOR UPDATE",String.class,session);
        try { return (ObjectNode)json.readTree(value); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void save(JdbcTemplate jdbc,UUID session,ObjectNode state) { jdbc.update("UPDATE agent_session SET working_state=?::jsonb WHERE id=?",state.toString(),session); }
    private static String bounded(String value,int length) { return value.length()<=length ? value : value.substring(0,length); }
}
