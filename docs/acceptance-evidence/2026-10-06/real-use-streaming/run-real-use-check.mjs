// A 阶段:真实文档检索成功链 + 三类正常使用检查(生产 API,无模型 mock)。
// 前置:RealAcceptanceHostTest 宿主(18080)、隔离副本 PG(55432)、Ollama(11434, qwen3-embedding:0.6b)。
// 用法:node run-real-use-check.mjs <step>   step ∈ {setup, q1, q2, q3}
// 证据落盘到本目录;凭据从仓库根 .env 读取,不入 Git。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '../../../..')
const env = Object.fromEntries(
  fs.readFileSync(path.join(repo, '.env'), 'utf8').split(/\r?\n/)
    .filter(l => l.includes('=') && !l.trim().startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
const API = 'http://127.0.0.1:18080/api/v1'
const step = process.argv[2]
const stateFile = path.join(here, 'state.json')

async function call(method, url, body, token, isForm) {
  const headers = { Origin: 'http://localhost:15173' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (body && !isForm) headers['Content-Type'] = 'application/json'
  const res = await fetch(`${API}${url}`, {
    method, headers,
    body: body ? (isForm ? body : JSON.stringify(body)) : undefined,
  })
  const text = await res.text()
  let data
  try { data = JSON.parse(text) } catch { data = text }
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status}: ${text.slice(0, 400)}`)
  return data
}
const save = (name, value) => fs.writeFileSync(path.join(here, name), JSON.stringify(value, null, 2))
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function login() {
  const res = await call('POST', '/auth/login', {
    username: env.DEMO_OWNER_USERNAME || 'owner', password: env.DEMO_OWNER_PASSWORD,
  })
  return res.data?.accessToken ?? res.data?.access_token ?? res.data?.token
}

async function pollRun(token, projectId, runId, label, timeoutMs = 360000) {
  const started = Date.now()
  let last = null
  while (Date.now() - started < timeoutMs) {
    await sleep(4000)
    last = (await call('GET', `/projects/${projectId}/agent/runs/${runId}`, null, token)).data
    const s = last.run?.status ?? last.status
    console.log(`[${label}] ${new Date().toISOString()} status=${s} steps=${last.run?.stepsUsed ?? '?'}`)
    if (['SUCCEEDED', 'FAILED', 'CANCELED', 'BUDGET_EXCEEDED', 'PAUSED', 'WAITING_FOR_APPROVAL', 'WAITING_FOR_USER_INPUT', 'FAILED_RETRYABLE'].includes(s)) {
      if (['SUCCEEDED', 'FAILED', 'CANCELED', 'BUDGET_EXCEEDED', 'WAITING_FOR_USER_INPUT'].includes(s)) break
    }
  }
  return last
}

async function ask(token, projectId, sessionId, content, label) {
  const res = await call('POST', `/projects/${projectId}/agent/sessions/${sessionId}/messages`, { content }, token)
  const run = res.data?.run ?? res.data
  const runId = run.id ?? run.runId
  console.log(`[${label}] run=${runId}`)
  save(`${label}-submit.json`, { label, content, runId })
  const detail = await pollRun(token, projectId, runId, label)
  save(`${label}-run.json`, detail)
  const events = (await call('GET', `/projects/${projectId}/agent/runs/${runId}/events-history?afterSequence=0`, null, token)).data
  save(`${label}-events.json`, events)
  const messages = (await call('GET', `/projects/${projectId}/agent/sessions/${sessionId}/messages`, null, token)).data
  save(`${label}-messages.json`, messages)
  const finalMsgs = messages.filter(m => m.role === 'ASSISTANT' && (m.runId ?? null) === runId)
  console.log(`[${label}] final status=${detail.run?.status ?? detail.status}; assistant messages=${finalMsgs.length}`)
  console.log(`[${label}] answer preview: ${(finalMsgs.at(-1)?.content ?? '').slice(0, 300).replaceAll('\n', ' ')}`)
}

if (step === 'setup') {
  const token = await login()
  save('login-ok.json', { at: new Date().toISOString(), username: env.DEMO_OWNER_USERNAME || 'owner' })
  // 1) 当前 AGENT 用途配置(keep-config 模式,不改动)
  try {
    const purposes = (await call('GET', '/user/ai-providers/purposes', null, token)).data
    save('config-purposes.json', { at: new Date().toISOString(), purposes })
    console.log('purposes:', JSON.stringify(purposes).slice(0, 400))
  } catch (e) { console.log('purposes query skipped:', e.message) }
  // 2) 隔离场景项目
  const project = (await call('POST', '/projects', {
    name: `文档检索与流式验收 20261006`,
    description: '本轮真实文档检索成功链与正常使用检查的隔离场景项目;基准日 2026-10-06。',
    type: 'SOFTWARE_TRAINING',
    startDate: '2026-09-28', dueDate: '2026-11-13',
  }, token)).data
  console.log('project:', project.id)
  // 3) 里程碑:1 个已逾期、1 个进行中
  const m1 = (await call('POST', `/projects/${project.id}/milestones`, {
    name: '数据同步服务联调冻结', description: '联调入口冻结,进入回归。',
    startDate: '2026-09-28', endDate: '2026-10-05', targetDate: '2026-10-05', status: 'ACTIVE',
  }, token)).data
  const m2 = (await call('POST', `/projects/${project.id}/milestones`, {
    name: '发布评审', description: '发布窗口评审与放行。',
    startDate: '2026-10-20', endDate: '2026-10-30', targetDate: '2026-10-30', status: 'PLANNED',
  }, token)).data
  // 4) 任务:混合状态,含 TODO 不算延期的控制项与缺日期控制项
  const tasks = []
  const mkTask = async (b) => (await call('POST', `/projects/${project.id}/tasks`, b, token)).data
  tasks.push(await mkTask({ title: '同步通道压测报告', milestoneId: m1.id, status: 'DONE', priority: 'HIGH', startDate: '2026-09-29', dueDate: '2026-10-03', description: '' }))
  tasks.push(await mkTask({ title: '权限模型评审', milestoneId: m1.id, status: 'TODO', priority: 'MEDIUM', startDate: '2026-10-01', dueDate: '2026-10-04', description: '' }))
  tasks.push(await mkTask({ title: '性能压测报告', milestoneId: m1.id, status: 'IN_PROGRESS', priority: 'MEDIUM', startDate: '2026-10-02', description: '' }))
  tasks.push(await mkTask({ title: '数据迁移脚本核对', status: 'BLOCKED', priority: 'HIGH', startDate: '2026-10-06', dueDate: '2026-10-08', description: '' }))
  console.log('milestones:', m1.id, m2.id, 'tasks:', tasks.map(t => t.id).length)
  // 5) 独特事实测试文档(内容在本目录 scenario-doc.md)
  const docMarkdown = fs.readFileSync(path.join(here, 'scenario-doc.md'))
  const form = new FormData()
  form.append('file', new Blob([docMarkdown], { type: 'text/markdown' }), '星桥发布值班手册-20261006.md')
  form.append('displayName', '星桥发布值班手册(验收专用 20261006)')
  const doc = await call('POST', `/projects/${project.id}/documents`, form, token, true)
  console.log('document:', JSON.stringify(doc.data ?? doc).slice(0, 300))
  const state = {
    at: new Date().toISOString(),
    token, projectId: project.id,
    milestoneIds: [m1.id, m2.id], taskIds: tasks.map(t => t.id),
    documentId: (doc.data ?? doc).id,
  }
  fs.writeFileSync(stateFile, JSON.stringify(state, null, 2))
  console.log('SETUP OK')
} else if (step === 'waitdoc') {
  const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
  const started = Date.now()
  while (Date.now() - started < 180000) {
    const d = (await call('GET', `/projects/${state.projectId}/documents/${state.documentId}`, null, state.token)).data
    const status = d.status ?? d.document?.status
    console.log('document status:', status)
    if (status === 'READY' || status === 'FAILED') { save('document-final.json', d); break }
    await sleep(3000)
  }
} else if (step === 'session') {
  const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
  const s = (await call('POST', `/projects/${state.projectId}/agent/sessions`, {
    title: `检索验收会话 ${new Date().toISOString().slice(0, 16)}`,
  }, state.token)).data
  state.sessionId = s.id ?? s.session?.id
  fs.writeFileSync(stateFile, JSON.stringify(state, null, 2))
  console.log('session:', state.sessionId)
} else if (step === 'q1' || step === 'q2' || step === 'q3') {
  const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
  const questions = {
    q1: '请基于当前项目数据做一次进度分析:哪些已逾期、哪些缺少截止日期或里程碑归属?给出结论依据和下一步建议。不要把没有截止日期的任务直接说成延期。',
    q2: '数据同步服务的发布窗口安排在什么时间?出现故障后的回滚时限是多少分钟?请给出依据来源。',
    q3: '数据同步服务的移动端 App 计划什么时候上线?上线前需要哪些审批?',
  }
  await ask(state.token, state.projectId, state.sessionId, questions[step], step)
  console.log(`${step.toUpperCase()} DONE`)
} else {
  console.log('unknown step; use setup|waitdoc|session|q1|q2|q3')
}
