// Test-only local HTTP model transport. Scripted output is never real-model quality evidence.
import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
const [control,log]=process.argv.slice(2);
if(!control||!log)throw Error('Provide private control and sanitized evidence paths');
const settings=()=>JSON.parse(fs.readFileSync(control,'utf8').replace(/^\uFEFF/,''));
const record=x=>fs.appendFileSync(log,JSON.stringify({at:new Date().toISOString(),...x})+'\n');
http.createServer(async(req,res)=>{
 const parts=[];for await(const part of req)parts.push(part);
 const body=JSON.parse(Buffer.concat(parts));const text=JSON.stringify(body.messages);
 const stage=body.tools?.length?'AGENT':text.includes('milestonePatches')?'REPAIR':text.includes('补全细节。')?'DETAIL':'SKELETON';
 const cfg=settings();record({stage,event:'REQUEST',mode:cfg.mode});
 if(stage==='DETAIL'&&['HOLD_DETAIL','TIMEOUT_DETAIL'].includes(cfg.mode)){
  while((cfg.mode==='TIMEOUT_DETAIL'||settings().mode===cfg.mode)&&!res.destroyed)await new Promise(r=>setTimeout(r,100));
 }
 if(res.destroyed){record({stage,event:'CLIENT_DISCONNECTED'});return;}
 let content='',calls;
 if(stage==='AGENT')calls=[{id:crypto.randomUUID(),type:'function',function:{name:'start_task_plan',arguments:JSON.stringify({title:cfg.title,goal:'隔离副本可靠性故障验收',constraints:'只生成草稿，不确认正式任务',planStartDate:'2026-10-05',planDueDate:'2026-10-25',maxTaskCount:1,documentIds:[]})}}];
 else if(stage==='DETAIL')content=JSON.stringify({milestones:[{tempKey:'m1',description:'故障验收里程碑',sourceRefs:[]}],tasks:[{tempKey:'t1',description:'受控模型生成的故障验收任务',priority:'HIGH',estimatedHours:8,startDate:'2026-10-05',dueDate:'2026-10-25',suggestedAssigneeId:null,dependencyTempKeys:[],sourceRefs:[]}]});
 else content=JSON.stringify({summary:'受控模型故障验收规划',assumptions:[],risks:[],milestones:[{tempKey:'m1',title:'验收里程碑',objective:'检查恢复不变量',targetDate:'2026-10-25',sortOrder:0}],tasks:[{tempKey:'t1',milestoneTempKey:'m1',title:'验收任务',objective:'中断保留草稿且不重复创建',sortOrder:0}]});
 const message={role:'assistant',content};if(calls)message.tool_calls=calls;
 const result={id:crypto.randomUUID(),model:'reliability-scripted-peer',choices:[{message,finish_reason:calls?'tool_calls':'stop'}],usage:{prompt_tokens:100,completion_tokens:100}};
 res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(result));record({stage,event:'RESPONSE'});
}).listen(18082,'127.0.0.1',()=>console.log('Controlled model peer on localhost:18082'));
