// 订阅指定 run 的生产 SSE,记录 MODEL_CONTENT 帧的到达时间与 revision,直到终态。
import fs from 'node:fs'
const env = Object.fromEntries(fs.readFileSync('E:/project/ai-collab/.env','utf8').split(/\r?\n/).filter(l=>l.includes('=')&&!l.startsWith('#')).map(l=>[l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]))
const state = JSON.parse(fs.readFileSync('state.json','utf8'))
const runId = process.argv[2]
const login = await fetch('http://127.0.0.1:18080/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json',Origin:'http://localhost:15173'},body:JSON.stringify({username:env.DEMO_OWNER_USERNAME||'owner',password:env.DEMO_OWNER_PASSWORD})})
const token = (await login.json()).data.accessToken
const t0 = Date.now()
const res = await fetch(`http://127.0.0.1:18080/api/v1/projects/${state.projectId}/agent/runs/${runId}/events`,{
  headers:{Accept:'text/event-stream',Authorization:'Bearer '+token,Origin:'http://localhost:15173','Last-Event-ID':'0'}})
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
      const j = JSON.parse(data)
      if (name === 'MODEL_CONTENT') frames.push({ at: Date.now()-t0, revision: j.revision, chars: j.text.length, final: j.final })
      else if (['MODEL_STARTED','MODEL_COMPLETED','RUN_SUCCEEDED','RUN_FAILED'].includes(j.type))
        frames.push({ at: Date.now()-t0, type: j.type, seq: j.sequence })
    } catch {}
  }
  if (frames.some(f=>f.type==='RUN_SUCCEEDED'||f.type==='RUN_FAILED')) break
}
fs.writeFileSync('sse-frame-timeline.json', JSON.stringify({runId, capturedAt:new Date().toISOString(), frames}, null, 2))
const contentFrames = frames.filter(f=>f.revision!==undefined)
console.log('content frames:', contentFrames.length)
console.log(contentFrames.slice(0,40).map(f=>`+${f.at}ms rev=${f.revision} chars=${f.chars} final=${f.final}`).join('\n'))
console.log('events:', frames.filter(f=>f.type).map(f=>`${f.type}@+${f.at}ms`).join(' '))
