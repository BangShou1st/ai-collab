package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Test-only HTTP peer. Production adapters, runtime and business services remain unchanged. */
final class ScriptedAcceptanceModel implements AutoCloseable {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor=Executors.newFixedThreadPool(8);
    volatile boolean stopped;
    volatile String mode="NORMAL";
    ScriptedAcceptanceModel() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(executor);
        server.createContext("/control",e->{ mode=e.getRequestURI().getQuery()==null?"NORMAL":e.getRequestURI().getQuery(); if(mode.equals("STOP")) stopped=true; reply(e,200,"text/plain","OK"); });
        server.createContext("/v1/embeddings",e->{
            var input=json.readTree(e.getRequestBody()).path("input"); var data=json.createArrayNode();
            int count=input.isArray()?input.size():1;
            for(int i=0;i<count;i++) data.add(json.createObjectNode().put("index",i).set("embedding",json.createArrayNode().add(1).add(0).add(0)));
            reply(e,200,"application/json",json.createObjectNode().set("data",data).toString());
        });
        server.createContext("/v1/chat/completions",this::chat); server.start();
    }
    int port(){return server.getAddress().getPort();}
    String base(){return "http://127.0.0.1:"+port();}
    private void chat(HttpExchange e) throws java.io.IOException {
        var request=json.readTree(e.getRequestBody()); String model=request.path("model").asText();
        StringBuilder text=new StringBuilder(); for(var message:request.path("messages"))text.append(message.path("content").asText()).append('\n');
        String prompt=text.toString(), schemaText="";
        int schemaStart=prompt.lastIndexOf("<JSON_SCHEMA>"),schemaEnd=prompt.lastIndexOf("</JSON_SCHEMA>");
        if(schemaStart>=0&&schemaEnd>schemaStart)schemaText=prompt.substring(schemaStart+13,schemaEnd);
        if(model.equals("acceptance-auth-failure")) { reply(e,403,"application/json","{\"error\":{\"message\":\"synthetic authentication rejection\"}}"); return; }
        if(mode.equals("SLOW_NEXT")) { mode="NORMAL"; try{Thread.sleep(20000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();} }
        String answer; ArrayNode calls=json.createArrayNode();
        if(request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            boolean hasTool=false; String lastUser="";
            for(var message:request.path("messages")) { if(message.path("role").asText().equals("tool"))hasTool=true; if(message.path("role").asText().equals("user"))lastUser=message.path("content").asText(); }
            int workingStart=prompt.indexOf("<CURRENT_WORKING_STATE>"),workingEnd=prompt.indexOf("</CURRENT_WORKING_STATE>");
            if(workingStart>=0&&workingEnd>workingStart)
                lastUser=json.readTree(prompt.substring(workingStart+23,workingEnd)).path("latestRequest").asText();
            if(!hasTool) {
                boolean write=lastUser.contains("创建")||lastUser.contains("修订"); String name=write?"create_task_after_approval":"list_tasks";
                boolean exposed=false; for(var t:request.path("tools")) if(t.path("function").path("name").asText().equals(name))exposed=true;
                if(exposed) {
                    ObjectNode args=json.createObjectNode(); if(write)args.put("title",lastUser.contains("修订")?"浏览器验收任务已修订":"浏览器验收任务").put("description","合成验收提案").put("priority","HIGH");
                    if(write&&lastUser.contains("修订")) {
                        int start=prompt.indexOf("<TRUSTED_PROPOSALS>"),end=prompt.indexOf("</TRUSTED_PROPOSALS>");
                        if(start>=0&&end>start) for(var proposal:json.readTree(prompt.substring(start+19,end))) {
                            if(proposal.path("status").asText().equals("PENDING")) { args.put("approvalId",proposal.path("approvalId").asText());break; }
                        }
                    }
                    calls.add(call("call-acceptance",name,args));
                    if(mode.equals("PARTIAL")) { mode="NORMAL"; calls.add(call("call-invalid","list_tasks",json.createObjectNode().put("unexpected","invalid"))); }
                }
            }
            answer=hasTool?"已完成可用查询，提案等待项目管理员审批；失败步骤未计入事实。":"";
        } else if(prompt.contains("milestonePatches")) {
            answer="{\"milestonePatches\":[],\"taskPatches\":[{\"tempKey\":\"t1\",\"description\":\"局部修复后的合成任务说明\"}]}";
        } else if(schemaText.contains("estimatedHours")) {
            answer="{\"milestones\":[{\"tempKey\":\"m1\",\"description\":\"合成验收发布说明\",\"sourceRefs\":[]}],\"tasks\":[{\"tempKey\":\"t1\",\"description\":\"合成任务说明\",\"priority\":\"HIGH\",\"estimatedHours\":8,\"startDate\":null,\"dueDate\":null,\"suggestedAssigneeId\":null,\"dependencyTempKeys\":[],\"sourceRefs\":[]}]}";
        } else if(schemaText.contains("sortOrder")) {
            answer="{\"summary\":\"合成验收规划\",\"assumptions\":[],\"risks\":[],\"milestones\":[{\"tempKey\":\"m1\",\"title\":\"验收发布\",\"objective\":\"完成验收\",\"targetDate\":null,\"sortOrder\":0}],\"tasks\":[{\"tempKey\":\"t1\",\"milestoneTempKey\":\"m1\",\"title\":\"验收任务\",\"objective\":\"验证完整业务流程\",\"sortOrder\":0}]}";
        } else if(prompt.contains("probe")) answer="{\"probe\":true}";
        else answer="资料说明：项目采用 Java 架构，验收编号为 ACCEPTANCE-20261003。[S1]";
        if(model.equals("acceptance-b") && answer.startsWith("{")) answer="```json\n"+answer+"\n```";
        System.out.println("ACCEPTANCE_MODEL request model="+model+", tools="+calls.size()+", stream="+request.path("stream").asBoolean());
        if(request.path("stream").asBoolean()) {
            var delta=json.createObjectNode(); if(!calls.isEmpty()) { var fragments=json.createArrayNode(); for(int i=0;i<calls.size();i++)fragments.add(((ObjectNode)calls.get(i)).deepCopy().put("index",i)); delta.set("tool_calls",fragments); } else delta.put("content",answer);
            var choice=json.createObjectNode().put("finish_reason",calls.isEmpty()?"stop":"tool_calls").set("delta",delta);
            reply(e,200,"text/event-stream","data: "+json.createObjectNode().put("model",model).set("choices",json.createArrayNode().add(choice))+"\n\ndata: [DONE]\n\n");
        } else {
            var message=json.createObjectNode().put("role","assistant").put("content",answer); if(!calls.isEmpty())message.set("tool_calls",calls);
            var choice=json.createObjectNode().put("finish_reason",calls.isEmpty()?"stop":"tool_calls").set("message",message);
            reply(e,200,"application/json",json.createObjectNode().put("model",model).set("choices",json.createArrayNode().add(choice)).toString());
        }
    }
    private ObjectNode call(String id,String name,ObjectNode args) {
        return json.createObjectNode().put("id",id).put("type","function").set("function",json.createObjectNode().put("name",name).put("arguments",args.toString()));
    }
    private static void reply(HttpExchange e,int status,String type,String text) throws java.io.IOException {
        byte[] body=text.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type",type); e.sendResponseHeaders(status,body.length); e.getResponseBody().write(body); e.close();
    }
    public void close(){server.stop(0);executor.shutdownNow();}
}
