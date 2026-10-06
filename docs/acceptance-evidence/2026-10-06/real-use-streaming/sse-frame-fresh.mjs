// 登录→建会话→提交→立即订阅 SSE,完整捕获第一轮模型的正文帧时序。
import fs from 'node:fs'
const env = Object.fromEntries(fs.readFileSync('E:/project/ai-collab/.env','utf8').split(/\r?\n/).filter(l=>l.includes('=')&&!l.startsWith('#')).map(l=>[l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]))
const state = JSON.parse(fs.readFileSync('state.json','utf8'))
const login = await fetch('http://127.0.0.1:18080/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json',Origin:'http://localhost:15173'},body:JSON.stringify({username:env.DEMO_OWNER_USERNAME||'owner',password:env.DEMO_OWNER_PASSWORD})})
const token = (await login.json()).data.accessToken
const H = {'Content-Type':'application/json',Authorization:'Bearer '+token,Origin:'http://localhost:15173'}
const s = await fetch(`http://127.0.0.1:18080/api/v1/projects/${state.projectId}/agent/sessions`,{method:'POST',headers:H,body:JSON.stringify({title:'完整流捕获 '+new Date().toISOString().slice(11,19)})})
const session = (await s.json()).data
const t0 = Date.now()
// 先订阅 events? runId 未知——先提交拿 runId(通常 <1s),再订阅
const sub = await fetch(`http://127.0.0.1:18080/api/v1/projects/${state.projectId}/agent/sessions/${session.id}/messages`,{method:'POST',headers:H,body:JSON.stringify({content:'分段流式验收:请详细总结项目状态、风险与处理顺序,并逐项说明依据。'})})
const j = await (async()=>{ const txt = await sub.text(); try { return JSON.parse(txt) } catch { return {raw:txt.slice(0,200)} } })()
const run = j.data?.run ?? j.data
const submitAt = Date.now()-t0
const res = await fetch(`http://127.0.0.1:18080/api/v1/projects/${state.projectId}/agent/runs/${run.id}/events`,{headers:{Accept:'text/event-stream',Authorization:'Bearer '+token,Origin:'http://localhost:15173','Last-Event-ID':'0'}})
const frames = []
let buffer = ''
const decoder = new TextDecoder()
const reader = res.body.getReader()
while (true) {
  const { value, done } = await reader.read()
  if (done) break
  buffer += decoder.decode(value,{stream:true})
  let idx
  while ((idx = buffer.indexOf('\n\n')) >= 0) {
    const frame = buffer.slice(0, idx); buffer = buffer.slice(idx+2)
    const name = (frame.match(/^event:(.*)$/m)||[])[1]?.trim()
    const data = (frame.match(/^data:(.*)$/m)||[])[1]?.trim()
    if (!data) continue
    try {
      const ev = JSON.parse(data)
      if (name === 'MODEL_CONTENT') frames.push({ at: Date.now()-t0, kind:'content', revision: ev.revision, chars: ev.text.length, final: ev.final })
      else if (['MODEL_STARTED','MODEL_COMPLETED','RUN_SUCCEEDED','RUN_FAILED'].includes(ev.type))
        frames.push({ at: Date.now()-t0, kind:'event', type: ev.type, seq: ev.sequence })
    } catch {}
  }
  if (frames.some(f=>f.type==='RUN_SUCCEEDED'||f.type==='RUN_FAILED')) break
}
fs.writeFileSync('sse-frame-fresh.json', JSON.stringify({sessionId:session.id, runId:run.id, submitAt, capturedAt:new Date().toISOString(), frames}, null, 2))
console.log('submit->subscribe ms:', submitAt)
console.log(frames.map(f=>f.kind==='content'?`+${f.at}ms FRAME rev=${f.revision} chars=${f.chars} final=${f.final}`:`+${f.at}ms ${f.type}`).join('\n'))
