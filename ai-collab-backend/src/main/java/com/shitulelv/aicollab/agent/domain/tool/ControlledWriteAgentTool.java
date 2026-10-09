package com.shitulelv.aicollab.agent.domain.tool;
/** A controlled draft/operation mutation, distinct from an approval proposal and from a read. */
public interface ControlledWriteAgentTool extends AgentTool {
    @Override default boolean writesBusinessData(){return true;}
}
