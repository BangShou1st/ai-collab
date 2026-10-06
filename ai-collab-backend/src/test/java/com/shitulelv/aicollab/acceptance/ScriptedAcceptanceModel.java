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
    /** SLOW_NEXT 睡眠结束后恢复的模式：让慢速与 NARRATE 等脚本模式可以叠加使用。 */
    volatile String resumeMode="NORMAL";
    /** NARRATE 模式的阶段计数：0=说明+查任务，1=说明+查成员，之后进入收尾回答。 */
    final java.util.concurrent.atomic.AtomicInteger narrateStage=new java.util.concurrent.atomic.AtomicInteger();
    ScriptedAcceptanceModel() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(executor);
        server.createContext("/control",e->{
            String next=e.getRequestURI().getQuery()==null?"NORMAL":e.getRequestURI().getQuery();
            if(next.equals("STOP")) stopped=true;
            if(next.startsWith("NARRATE")) narrateStage.set(0);
            if(next.equals("SLOW_NEXT")) { resumeMode=mode; mode="SLOW_NEXT"; }
            else mode=next;
            reply(e,200,"text/plain","OK");
        });
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
        byte[] raw=e.getRequestBody().readAllBytes();
        try {
            chatInner(e, raw);
        } catch (Exception failure) {
            dumpFailure(raw, failure);
        }
    }

    private void chatInner(HttpExchange e, byte[] raw) throws java.io.IOException {
        {
            var request=json.readTree(raw); String model=request.path("model").asText();
        StringBuilder text=new StringBuilder(); for(var message:request.path("messages"))text.append(message.path("content").asText()).append('\n');
        String prompt=text.toString(), schemaText="";
        int schemaStart=prompt.lastIndexOf("<JSON_SCHEMA>"),schemaEnd=prompt.lastIndexOf("</JSON_SCHEMA>");
        if(schemaStart>=0&&schemaEnd>schemaStart)schemaText=prompt.substring(schemaStart+13,schemaEnd);
        if(model.equals("acceptance-auth-failure")) { reply(e,403,"application/json","{\"error\":{\"message\":\"synthetic authentication rejection\"}}"); return; }
        if(mode.equals("SLOW_NEXT")) { mode=resumeMode; try{Thread.sleep(20000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();} }
        if(mode.equals("SLOW_EACH")) { try{Thread.sleep(8000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();} }
        String answer; ArrayNode calls=json.createArrayNode();
        if(request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            boolean hasTool=false; String lastUser="";
            for(var message:request.path("messages")) { if(message.path("role").asText().equals("tool"))hasTool=true; if(message.path("role").asText().equals("user"))lastUser=message.path("content").asText(); }
            int workingStart=prompt.indexOf("<CURRENT_WORKING_STATE>"),workingEnd=prompt.indexOf("</CURRENT_WORKING_STATE>");
            if(workingStart>=0&&workingEnd>workingStart) {
                String block=prompt.substring(workingStart+23,workingEnd);
                try {
                    lastUser=json.readTree(block).path("latestRequest").asText();
                } catch (Exception notJson) {
                    // 工作状态块是格式化中文文本时，按行提取最新请求
                    var matcher=java.util.regex.Pattern.compile("最新请求[:：]\\s*(.*)").matcher(block);
                    if(matcher.find()) lastUser=matcher.group(1).strip();
                }
            }
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
            if(mode.startsWith("NARRATE")) {
                // 过渡说明验收：按历史工具结果条数分阶段——说明+查任务 → 说明+查里程碑 → 最终回答
                int toolMessages=0; for(var message:request.path("messages")) if(message.path("role").asText().equals("tool"))toolMessages++;
                if(toolMessages==0) { calls.removeAll(); calls.add(call("call-acceptance-narrate-0","list_tasks",json.createObjectNode())); answer="我先读取项目任务，再核对里程碑。"; }
                else if(toolMessages==1) { calls.removeAll(); calls.add(call("call-acceptance-narrate-1","list_milestones",json.createObjectNode())); answer="发现两项延期任务，我再核对里程碑状态。"; }
                else { calls.removeAll(); answer="核对完成：两项任务延期，建议尽快确认负责人并同步里程碑风险。"; }
            } else {
                answer=hasTool?"已完成可用查询，提案等待项目管理员审批；失败步骤未计入事实。":"";
            }
        } else if(prompt.contains("milestonePatches")) {
            answer="{\"milestonePatches\":[],\"taskPatches\":[{\"tempKey\":\"t1\",\"description\":\"局部修复后的合成任务说明\"}]}";
        } else if(schemaText.contains("estimatedHours")) {
            answer="{\"milestones\":[{\"tempKey\":\"m1\",\"description\":\"合成验收发布说明\",\"sourceRefs\":[]}],\"tasks\":[{\"tempKey\":\"t1\",\"description\":\"合成任务说明\",\"priority\":\"HIGH\",\"estimatedHours\":8,\"startDate\":null,\"dueDate\":null,\"suggestedAssigneeId\":null,\"dependencyTempKeys\":[],\"sourceRefs\":[]}]}";
        } else if(schemaText.contains("sortOrder")) {
            answer="{\"summary\":\"合成验收规划\",\"assumptions\":[],\"risks\":[],\"milestones\":[{\"tempKey\":\"m1\",\"title\":\"验收发布\",\"objective\":\"完成验收\",\"targetDate\":null,\"sortOrder\":0}],\"tasks\":[{\"tempKey\":\"t1\",\"milestoneTempKey\":\"m1\",\"title\":\"验收任务\",\"objective\":\"验证完整业务流程\",\"sortOrder\":0}]}";
        } else if(prompt.contains("probe")) answer="{\"probe\":true}";
        else answer="资料说明：项目采用 Java 架构，验收编号为 ACCEPTANCE-20261003。[S1]";
        if(model.equals("acceptance-b") && answer.startsWith("{")) answer="```json\n"+answer+"\n```";
        StringBuilder exposedNames=new StringBuilder(); for(var t:request.path("tools")) exposedNames.append(t.path("function").path("name").asText()).append(',');
        System.out.println("ACCEPTANCE_MODEL request model="+model+", tools="+calls.size()+", exposed=["+exposedNames+"], promptChars="+prompt.length()+", answerChars="+answer.length()+", hasToolHistory="+(request.path("messages").path(0).isObject())+", stream="+request.path("stream").asBoolean());
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
    }
    private ObjectNode call(String id,String name,ObjectNode args) {
        return json.createObjectNode().put("id",id).put("type","function").set("function",json.createObjectNode().put("name",name).put("arguments",args.toString()));
    }
    /** 验收诊断：把无法处理的请求原文与堆栈落盘，避免只看到连接被关闭。 */
    private static void dumpFailure(byte[] raw, Exception failure) {
        try {
            var dir=java.nio.file.Path.of("target/acceptance-dump");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("failure-"+System.nanoTime()+".txt"),
                "request_bytes="+raw.length+"\n"+new String(raw,java.nio.charset.StandardCharsets.UTF_8)+"\n\n=== STACK ===\n"+failure);
        } catch (Exception ignored) { }
    }
    private static void reply(HttpExchange e,int status,String type,String text) throws java.io.IOException {
        byte[] body=text.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type",type); e.sendResponseHeaders(status,body.length); e.getResponseBody().write(body); e.close();
    }
    public void close(){server.stop(0);executor.shutdownNow();}
}
