import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import com.shitulelv.aicollab.agent.application.*;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.application.runtime.*;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;

public class R2ToolRecoveryProbe {
  static final Class<?> TEST;
  static {try {TEST=Class.forName("com.shitulelv.aicollab.agent.application.runtime.PersistedModelTurnRecoveryPostgresTest");}
    catch(Exception e){throw new RuntimeException(e);}}
  static Object field(Object instance,String name) throws Exception {
    Field f=TEST.getDeclaredField(name);f.setAccessible(true);return f.get(instance);
  }
  static Object call(Object instance,String name,Object...args) throws Exception {
    Method m=Arrays.stream(TEST.getDeclaredMethods()).filter(x->{
      if(!x.getName().equals(name)||x.getParameterCount()!=args.length)return false;
      Class<?>[] p=x.getParameterTypes();
      for(int i=0;i<p.length;i++){if(args[i]!=null&&!p[i].isInstance(args[i]))return false;}
      return true;
    }).findFirst().orElseThrow();
    m.setAccessible(true);
    try{return m.invoke(instance,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}
  }
  static Object fixtureTest() throws Exception {
    Constructor<?> c=TEST.getDeclaredConstructor();c.setAccessible(true);
    Object test=c.newInstance();call(test,"setUp");return test;
  }
  static AgentWorkerOutcome recover(Object test,AgentRunView run) throws Exception {
    JdbcTemplate jdbc=(JdbcTemplate)field(null,"jdbc");
    jdbc.update("UPDATE agent_run SET lease_expires_at=now()-INTERVAL '1 second' WHERE id=?",run.id());
    ((AgentRecoveryJob)field(test,"recovery")).claim("tool-review-worker",Duration.ofMinutes(6)).orElseThrow();
    AgentRepository repo=(AgentRepository)field(null,"repository");
    return ((AgentRuntimeCoordinator)field(test,"coordinator")).advance(repo.findRun(run.projectId(),run.id()).orElseThrow());
  }
  static long executorCalls(Object test) throws Exception {
    return Mockito.mockingDetails(field(test,"toolExecutor")).getInvocations().stream()
       .filter(i->i.getMethod().getName().equals("executeCalls")).count();
  }
  static long modelCalls(Object test) throws Exception {
    return Mockito.mockingDetails(field(test,"modelExecutor")).getInvocations().stream()
       .filter(i->i.getMethod().getName().equals("callModel")||i.getMethod().getName().equals("resolveRequest")).count();
  }
  public static void main(String[] args) throws Exception {
    PostgreSQLContainer<?> pg=(PostgreSQLContainer<?>)field(null,"POSTGRES");
    try {
      pg.start();call(null,"migrate");
      AgentRepository repo=(AgentRepository)field(null,"repository");
      JdbcTemplate jdbc=(JdbcTemplate)field(null,"jdbc");
      ObjectMapper json=(ObjectMapper)field(null,"json");
      Object test=fixtureTest();
      Object fixture=call(test,"fixture","汇总这周进展");
      AgentRunView run=(AgentRunView)call(test,"baseStateWithToolEvidence",fixture,new int[]{4,4,4});
      List<ModelToolCall> calls=new ArrayList<>();
      for(int i=0;i<4;i++)calls.add(new ModelToolCall("partial-"+i,"list_tasks",json.createObjectNode().put("query","query-"+i)));
      new AgentConvergencePolicy().validateToolBatch(run,AgentRuntimeLimits.forSkill(run.skillCode()),calls.size());
      int beforeUsed=run.toolCallsUsed();
      ModelTurnResult response=new ModelTurnResult("继续核对资料",calls,ModelFinishReason.TOOL_CALLS,new ModelUsage(120,60),"OPENAI_COMPATIBLE","model-a",120);
      String callId=repo.beginModelCall(run.projectId(),run.id(),"MODEL_TURN");
      run=repo.recordModelTurnWithSettlement(run,response,callId,"MODEL_TURN",AgentRunEventRecorder.UsageSettlement.fromRaw(response.usage(),120,60,120L),"NATIVE_TOOLS",false);
      for(int i=0;i<2;i++)run=repo.recordToolResult(run,"list_tasks",(JsonNode)call(test,"toolInput",calls.get(i)),json.createObjectNode().put("status","SUCCEEDED"),false);
      int remaining=jdbc.queryForObject("SELECT count(*) FROM agent_tool_invocation WHERE run_id=? AND status='PENDING'",Integer.class,run.id());
      new AgentConvergencePolicy().validateToolBatch(run,AgentRuntimeLimits.forSkill(run.skillCode()),remaining);
      AgentWorkerOutcome result=recover(test,run);
      int skipped=jdbc.queryForObject("SELECT count(*) FROM agent_tool_invocation WHERE run_id=? AND tool_call_id LIKE 'partial-%' AND status='SKIPPED'",Integer.class,run.id());
      System.out.println("PROBE_PARTIAL_TOOL beforeUsed="+beforeUsed+" budget="+run.maxToolCalls()+" batch=4 completed=2 actualUsed="+run.toolCallsUsed()+" remaining="+remaining+" recovered="+result.status()+" executorCalls="+executorCalls(test)+" skipped="+skipped+" modelCalls="+modelCalls(test));
      if(beforeUsed!=12||run.toolCallsUsed()!=14||remaining!=2||result.status()!=AgentRunStatus.BUDGET_EXCEEDED||executorCalls(test)!=0||skipped!=2)throw new AssertionError("Unexpected partial recovery state");

      test=fixtureTest();
      fixture=call(test,"fixture","请生成迭代规划草稿");
      run=(AgentRunView)call(test,"runningRun",fixture);
      ModelToolCall planCall=new ModelToolCall("old-plan-call","start_task_plan",json.createObjectNode().put("title","迭代规划草稿").put("goal","生成迭代规划草稿").put("planStartDate","2026-10-07").put("planDueDate","2026-10-14").put("maxTaskCount",6));
      run=repo.recordModelTurn(run,new ModelTurnResult("现在生成规划草稿",List.of(planCall),ModelFinishReason.TOOL_CALLS,new ModelUsage(100,60),"OPENAI_COMPATIBLE","model-a",120),"NATIVE_TOOLS");
      jdbc.update("UPDATE agent_step SET output_json=output_json-'finalizing' WHERE run_id=? AND type='MODEL_TURN'",run.id());
      if(repo.pendingModelTurn(run).orElseThrow().finalizing()!=null)throw new AssertionError("Expected old metadata");
      result=recover(test,run);
      System.out.println("PROBE_OLD_TOOL finalizingMissing=true sourceMode=NATIVE_TOOLS corePending=true recovered="+result.status()+" executorCalls="+executorCalls(test)+" modelCalls="+modelCalls(test));
      if(executorCalls(test)!=0)throw new AssertionError("Unexpected old tool handling");
      System.out.println("TOOL_REVIEW_PROBES_CONFIRMED=2");
    } finally {pg.stop();}
  }
}