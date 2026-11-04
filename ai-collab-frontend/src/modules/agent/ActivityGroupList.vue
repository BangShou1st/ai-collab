<script setup lang="ts">
import { computed } from 'vue'
import type { AgentActivity } from './agent-activity'

const props = defineProps<{ items: AgentActivity[] }>()

interface ActivityGroupEntry { kind: 'group'; key: string; title: string; count: number; items: AgentActivity[] }
type DisplayEntry = AgentActivity | ActivityGroupEntry

const CATEGORY_TITLES: Record<string, string> = {
  tasks: '查询任务与进度',
  milestones: '检查里程碑',
  people: '查看成员与项目记忆',
  knowledge: '检索项目知识',
  overview: '读取项目概况',
  misc: '查阅项目资料',
}

/** 展示层语义归类：相邻的已完成读取/检索操作按用户可理解的操作类型合并，
 *  组内保留全部真实事件行；失败、审批、等待与在途行永远独立可见。 */
function categoryOf(activity: AgentActivity): string {
  const tool = activity.tool
  if (/task|progress|dashboard/.test(tool)) return 'tasks'
  if (/milestone/.test(tool)) return 'milestones'
  if (/member|memor/.test(tool)) return 'people'
  if (/knowledge|question|answer/.test(tool)) return 'knowledge'
  if (/overview|audit/.test(tool)) return 'overview'
  return 'misc'
}

const entries = computed<DisplayEntry[]>(() => {
  const out: DisplayEntry[] = []
  let bucket: AgentActivity[] = []
  const flush = () => {
    if (bucket.length >= 3) {
      const categories = new Set(bucket.map(categoryOf))
      const first = [...categories][0]
      const category = categories.size === 1 && first ? first : 'misc'
      const title = CATEGORY_TITLES[category] ?? CATEGORY_TITLES.misc
      out.push({ kind: 'group', key: `group:${bucket[0].key}`, title, count: bucket.length, items: bucket })
    } else out.push(...bucket)
    bucket = []
  }
  for (const item of props.items) {
    if (item.kind === 'narration') { flush(); out.push(item); continue }
    if (item.status === 'done' && (item.kind === 'read' || item.kind === 'search')) { bucket.push(item); continue }
    flush(); out.push(item)
  }
  flush()
  return out
})

const STATUS_GLYPH: Record<string, string> = { done: '✓', running: '●', failed: '✕', waiting: '…' }

function activityTech(act: AgentActivity): string {
  return act.raw.map((e) => {
    const p = (e.payload ?? {}) as Record<string, unknown>
    const parts = [String(e.type)]
    if (typeof p.toolName === 'string' && p.toolName) parts.push(`tool=${p.toolName}`)
    if (typeof p.errorCode === 'string' && p.errorCode) parts.push(`errorCode=${p.errorCode}`)
    if (typeof p.durationMs === 'number' && p.durationMs > 0) parts.push(`${Math.round(p.durationMs)}ms`)
    return parts.join(' · ')
  }).join('\n')
}
</script>

<template>
  <template v-for="entry in entries" :key="entry.key">
    <div v-if="entry.kind === 'narration'" class="narration" :data-test="entry.key">
      <p>{{ entry.detail }}</p>
    </div>
    <details v-else-if="entry.kind === 'group'" class="activity-card" :data-test="entry.key">
      <summary>
        <span class="activity-glyph" aria-hidden="true">✓</span>
        <span class="activity-title">{{ entry.title }}</span>
        <span class="activity-meta">{{ entry.count }} 项查询</span>
      </summary>
      <div class="group-items">
        <div v-for="act in entry.items" :key="act.key" class="activity" :class="[act.kind, act.status]">
          <div class="activity-body">
            <div class="activity-title">{{ act.title }}</div>
            <div v-if="act.detail" class="activity-detail">{{ act.detail }}</div>
            <div v-if="act.count != null || act.durationMs != null" class="activity-meta">
              <span v-if="act.count != null">{{ act.count }} 条</span>
              <span v-if="act.durationMs != null">{{ (act.durationMs / 1000).toFixed(1) }}s</span>
            </div>
          </div>
        </div>
      </div>
    </details>
    <details v-else class="activity" :class="[entry.kind, entry.status]" :data-test="entry.key">
      <summary>
        <span class="activity-glyph" :aria-label="entry.status" aria-hidden="true">{{ STATUS_GLYPH[entry.status] ?? '·' }}</span>
        <span class="activity-body-inline">
          <span class="activity-title">{{ entry.title }}</span>
          <span v-if="entry.status === 'failed'" class="activity-badge failed">失败</span>
          <span v-else-if="entry.status === 'waiting'" class="activity-badge waiting">待处理</span>
        </span>
        <span v-if="entry.count != null || entry.durationMs != null" class="activity-meta">
          <span v-if="entry.count != null">{{ entry.count }} 条</span>
          <span v-if="entry.durationMs != null">{{ (entry.durationMs / 1000).toFixed(1) }}s</span>
        </span>
      </summary>
      <div v-if="entry.detail" class="activity-detail">{{ entry.detail }}</div>
      <details v-if="activityTech(entry)" class="tech-details"><summary>技术详情</summary><pre>{{ activityTech(entry) }}</pre></details>
    </details>
  </template>
</template>

<style scoped>
/* 紧凑活动行：details/summary 原生支持键盘操作 */
.activity,.activity-card{border-radius:8px;background:var(--el-fill-color-lighter)}
.activity{padding:0}
.activity>summary,.activity-card>summary{display:flex;gap:8px;align-items:center;padding:6px 10px;cursor:pointer;list-style:none;border-radius:8px;font-size:13px}
.activity>summary::-webkit-details-marker,.activity-card>summary::-webkit-details-marker{display:none}
.activity>summary:hover,.activity-card>summary:hover{background:var(--el-fill-color-light)}
.activity-glyph{flex:none;width:15px;height:15px;display:inline-flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:var(--color-success)}
.activity.running .activity-glyph{color:var(--color-primary);animation:pulse 1.2s infinite}
.activity.failed .activity-glyph,.activity.failure .activity-glyph{color:var(--color-danger)}
.activity.waiting .activity-glyph,.activity.approval .activity-glyph{color:var(--color-warning)}
.activity-title{font-size:13px;font-weight:550;flex:1;min-width:0}
.activity-body-inline{display:flex;align-items:center;gap:8px;flex:1;min-width:0}
.activity-badge{flex:none;font-size:11px;padding:1px 8px;border-radius:999px;border:1px solid}
.activity-badge.failed{color:var(--color-danger);border-color:rgba(199,79,89,.35);background:rgba(199,79,89,.06)}
.activity-badge.waiting{color:var(--color-warning);border-color:rgba(173,118,40,.35);background:rgba(173,118,40,.06)}
.activity-card{border:1px solid var(--color-border)}
.activity-card>summary .activity-title{flex:1}
.activity-card>summary::after{content:'▸';color:var(--color-text-muted);font-size:12px;transition:transform var(--transition-duration)}
.activity-card[open]>summary::after{transform:rotate(90deg)}
.group-items .group-items{padding:2px 0 6px}
.activity-detail{font-size:12px;color:var(--el-text-color-secondary);padding:0 10px 8px 33px}
.activity-meta{display:flex;gap:8px;font-size:12px;color:var(--el-text-color-secondary);flex:none}
.activity.proposal{background:rgba(37,99,235,.06);border:1px solid rgba(37,99,235,.18)}
.activity.approval{background:rgba(217,119,6,.08);border:1px solid rgba(217,119,6,.25)}
.activity.failure,.activity.failed{background:rgba(220,38,38,.06);border:1px solid rgba(220,38,38,.2)}
.narration{display:flex;gap:8px;align-items:flex-start;margin:8px 0;max-width:76ch}
.narration p{margin:0;font-size:13.5px;line-height:1.65;color:var(--color-text-secondary)}
.tech-details pre{background:var(--el-fill-color-lighter);border:1px solid var(--color-border);color:var(--color-text-secondary);padding:10px;border-radius:8px;overflow-x:auto;font-size:12px;margin:6px 0}
.tech-details summary{cursor:pointer;font-size:12px;color:var(--color-text-muted)}
@keyframes pulse{0%{opacity:1}50%{opacity:.35}100%{opacity:1}}
</style>
