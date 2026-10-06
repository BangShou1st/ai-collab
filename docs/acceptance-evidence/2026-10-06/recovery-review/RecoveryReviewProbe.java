import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.mockito.Mockito;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.application.runtime.AgentConvergencePolicy;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import com.fasterxml.jackson.databind.JsonNode;

public class RecoveryReviewProbe {
  static final Class<?> TEST;
  static { try { TEST=Class.forName("com.shitulelv.aicollab.agent.application.runtime.PersistedModelTurnRecoveryPostgresTest"); }
    catch(Exception e){throw new RuntimeException(e);} }
  static Object field(Object instance,String name) throws Exception {
    Field f=TEST.getDeclaredField(name); f.setAccessible(true); return f.get(instance);
  }
  static Object call(Object instance,String name,Object...args) throws Exception {
    Method m=Arrays.stream(TEST.getDeclaredMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();
    m.setAccessible(true);
    try{return m.invoke(instance,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}
  }
  static Object newFixture() throws Exception {
    Constructor<?> c=TEST.getDeclaredConstructor(); c.setAccessible(true);
    Object instance=c.newInstance(); call(instance,"setUp"); return instance;
  }
  static AgentWorkerOutcome recover(Object instance,AgentRunView run) throws Exception {
    JdbcTemplate jdbc=(JdbcTemplate)field(null,"jdbc");
    jdbc.update("UPDATE agent_run SET lease_expires_at=now()-INTERVAL '1 second' WHERE id=?",run.id());
    ClaimedAgentRun claim=((AgentRecoveryJob)field(instance,"recovery")).claim("review-worker",Duration.ofMinutes(6)).orElseThrow();
    AgentRepository repo=(AgentRepository)field(null,"repository");
    AgentRunView fresh=repo.findRun(run.projectId(),run.id()).orElseThrow();
    return ((AgentRuntimeCoordinator)field(instance,"coordinator")).advance(fresh);
  }
  static long modelCalls(Object instance) throws Exception {
    return Mockito.mockingDetails(field(instance,"modelExecutor")).getInvocations().stream()
       .filter(i->i.getMethod().getName().equals("callModel")||i.getMethod().getName().equals("resolveRequest")).count();
  }
  public static void main(String[] args) throws Exception {
    PostgreSQLContainer<?> pg=(PostgreSQLContainer<?>)field(null,"POSTGRES");
    try {
      pg.start(); call(null,"migrate");
      AgentRepository repo=(AgentRepository)field(null,"repository");
      JdbcTemplate jdbc=(JdbcTemplate)field(null,"jdbc");

      Object instance=newFixture();
      Object fixture=call(instance,"fixture","检查任务交付情况");
      AgentRunView run=(AgentRunView)call(instance,"runningRun",fixture);
      for(int i=0;i<7;i++) {
        run=repo.recordModelTurn(run,(ModelTurnResult)call(instance,"toolCallTurn","查询证据 "+i,"review-call-"+i));
        run=repo.recordToolResult(run,"list_tasks",(JsonNode)call(instance,"toolInput","review-call-"+i),
           ((com.fasterxml.jackson.databind.ObjectMapper)field(null,"json")).createObjectNode().put("status","SUCCEEDED"),false);
      }
      var policy=new AgentConvergencePolicy();
      var before=policy.decide(run,AgentRuntimeLimits.forSkill(run.skillCode()),repo.listSteps(run.projectId(),run.id()));
      String savedAnswer="完整回答：已核对全部任务，交付结论与建议如下。";
      run=repo.recordModelTurn(run,(ModelTurnResult)call(instance,"plainTextTurn",savedAnswer));
      var after=policy.decide(run,AgentRuntimeLimits.forSkill(run.skillCode()),repo.listSteps(run.projectId(),run.id()));
      AgentWorkerOutcome result=recover(instance,run);
      boolean retained=savedAnswer.equals(result.answer());
      System.out.println("PROBE_CAP before="+before.mode()+" after="+after.mode()+" recovered="+result.status()+" savedAnswerRetained="+retained+" modelCalls="+modelCalls(instance));
      if(before.mode()!=AgentConvergencePolicy.Mode.FINALIZE || after.mode()!=AgentConvergencePolicy.Mode.EXHAUSTED || retained)
        throw new AssertionError("Unexpected cap probe state");
      if(result.answer()==null || !result.answer().startsWith("模型未能在本次运行预算内生成完整总结"))
        throw new AssertionError("Expected replacement with evidence fallback");

      instance=newFixture();
      fixture=call(instance,"fixture","检查任务交付情况");
      run=(AgentRunView)call(instance,"runningRun",fixture);
      int effectiveCap=Math.min(run.maxInputTokens(),AgentRuntimeLimits.forSkill(run.skillCode()).maxInputTokens());
      String oversetAnswer="这个结果按正常路径应因实际输入超限而终止。";
      run=repo.recordModelTurn(run,new ModelTurnResult(oversetAnswer,List.of(),ModelFinishReason.STOP,
          new ModelUsage(effectiveCap+1,10),"OPENAI_COMPATIBLE","model-a",120));
      long actual=run.inputTokensActual();
      result=recover(instance,run);
      System.out.println("PROBE_INPUT cap="+effectiveCap+" actual="+actual+" recovered="+result.status()+" savedAnswerPublished="+oversetAnswer.equals(result.answer())+" modelCalls="+modelCalls(instance));
      if(actual<=effectiveCap || !result.status().name().equals("SUCCEEDED"))
        throw new AssertionError("Unexpected input overset probe state");
            instance=newFixture();
      fixture=call(instance,"fixture","请生成迭代规划草稿");
      run=(AgentRunView)call(instance,"runningRun",fixture);
      for(int i=0;i<5;i++) {
        ModelTurnResult evidence=(ModelTurnResult)call(instance,"toolCallTurn","核对背景 "+i,"phase-call-"+i);
        run=repo.recordModelTurn(run,new ModelTurnResult(evidence.content(),evidence.toolCalls(),evidence.finishReason(),
            new ModelUsage(300,3500),"OPENAI_COMPATIBLE","model-a",120));
        run=repo.recordToolResult(run,"list_tasks",(JsonNode)call(instance,"toolInput","phase-call-"+i),
            ((com.fasterxml.jackson.databind.ObjectMapper)field(null,"json")).createObjectNode().put("status","SUCCEEDED"),false);
      }
      var phaseSteps=repo.listSteps(run.projectId(),run.id());
      var phaseBefore=policy.decide(run,AgentRuntimeLimits.forSkill(run.skillCode()),phaseSteps);
      boolean finalRequest=policy.needsFinalRequest(run,AgentRuntimeLimits.forSkill(run.skillCode()),phaseSteps,1000,4000);
      String phaseAnswer="只完成了背景核对，规划草稿尚未生成。";
      run=repo.recordModelTurn(run,new ModelTurnResult(phaseAnswer,List.of(),ModelFinishReason.STOP,
          new ModelUsage(100,50),"OPENAI_COMPATIBLE","model-a",120));
      var phaseAfter=policy.decide(run,AgentRuntimeLimits.forSkill(run.skillCode()),repo.listSteps(run.projectId(),run.id()));
      result=recover(instance,run);
      long accepted=jdbc.queryForObject("SELECT count(*) FROM agent_tool_invocation WHERE run_id=? AND tool_name='start_task_plan' AND status='SUCCEEDED'",Long.class,run.id());
      System.out.println("PROBE_PHASE before="+phaseBefore.mode()+" needsFinalRequest="+finalRequest+" after="+phaseAfter.mode()+" coreAccepted="+accepted+" recovered="+result.status()+" modelCalls="+modelCalls(instance));
      if(phaseBefore.mode()!=AgentConvergencePolicy.Mode.CONTINUE || !finalRequest || phaseAfter.mode()!=AgentConvergencePolicy.Mode.CONTINUE || accepted!=0 || !result.status().name().equals("SUCCEEDED"))
        throw new AssertionError("Unexpected phase restoration probe state");
      System.out.println("REVIEW_PROBES_CONFIRMED=3");
    } finally { pg.stop(); }
  }
}