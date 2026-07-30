<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { normalizeApiError } from '../../api/api-result'
import { taskStatusLabel } from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { visualizationApi, type GraphNode, type GraphEdge } from './visualization-api'

const route = useRoute()
const projectId = route.params.projectId as string

const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
const loading = ref(false)
const errorMessage = ref('')

const project = ref<Project | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [result, projectResult] = await Promise.all([
      visualizationApi.dependencyGraph(projectId),
      projectApi.get(projectId),
    ])
    nodes.value = result.data.nodes
    edges.value = result.data.edges
    project.value = projectResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
}

function getStatusColor(status: string): string {
  const colors: Record<string, string> = {
    'TODO': '#909399',
    'IN_PROGRESS': '#409eff',
    'BLOCKED': '#e6a23c',
    'DONE': '#67c23a',
    'CANCELED': '#f56c6c',
  }
  return colors[status] || '#909399'
}

const graphNodes = computed(() => {
  const levels = new Map(nodes.value.map(node => [node.id, 0]))
  for (let pass = 0; pass < nodes.value.length; pass++) {
    let changed = false
    for (const edge of edges.value) {
      const source = levels.get(edge.source) ?? 0
      const target = levels.get(edge.target) ?? 0
      if (target < source + 1 && source + 1 < nodes.value.length) {
        levels.set(edge.target, source + 1)
        changed = true
      }
    }
    if (!changed) break
  }
  const levelCounts = new Map<number, number>()
  return nodes.value.map(node => {
    const level = levels.get(node.id) ?? 0
    const position = levelCounts.get(level) ?? 0
    levelCounts.set(level, position + 1)
    return {
      ...node,
      x: 130 + level * 250,
      y: 75 + position * 100,
    }
  })
})

const graphWidth = computed(() => Math.max(760, ...graphNodes.value.map(node => node.x + 130)))
const graphHeight = computed(() => Math.max(360, ...graphNodes.value.map(node => node.y + 75)))

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目执行"
      title="依赖图"
      :context="project?.name"
    />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section v-loading="loading" class="graph-container">
      <el-empty v-if="!loading && nodes.length === 0" description="暂无任务数据" :image-size="64" />
      <div v-else class="graph-stage">
      <div class="graph-legend">
        <span><i class="todo" />待办</span><span><i class="progress" />进行中</span>
        <span><i class="blocked" />阻塞</span><span><i class="done" />完成</span>
      </div>
      <svg
        class="dependency-graph"
        :width="graphWidth"
        :height="graphHeight"
        :viewBox="`0 0 ${graphWidth} ${graphHeight}`"
        role="img"
        aria-label="任务依赖关系图，箭头从前置任务指向后续任务"
      >
        <defs>
          <marker
            id="arrowhead"
            markerWidth="10"
            markerHeight="7"
            refX="10"
            refY="3.5"
            orient="auto"
          >
            <polygon points="0 0, 10 3.5, 0 7" fill="#909399" />
          </marker>
        </defs>
        <g v-for="edge in edges" :key="`${edge.source}-${edge.target}`">
          <line
            v-if="graphNodes.find(n => n.id === edge.source) && graphNodes.find(n => n.id === edge.target)"
            :x1="(graphNodes.find(n => n.id === edge.source)?.x ?? 0) + 90"
            :y1="graphNodes.find(n => n.id === edge.source)?.y"
            :x2="(graphNodes.find(n => n.id === edge.target)?.x ?? 0) - 90"
            :y2="graphNodes.find(n => n.id === edge.target)?.y"
            stroke="#909399"
            stroke-width="2"
            marker-end="url(#arrowhead)"
          />
        </g>
        <g v-for="node in graphNodes" :key="node.id">
          <rect
            :x="node.x - 90"
            :y="node.y - 31"
            width="180"
            height="62"
            rx="10"
            :fill="getStatusColor(node.status)"
            opacity="0.9"
          />
          <text
            :x="node.x"
            :y="node.y - 5"
            text-anchor="middle"
            fill="white"
            font-size="12"
            font-weight="500"
          >
            {{ node.title.length > 14 ? node.title.substring(0, 14) + '…' : node.title }}
          </text>
          <text
            :x="node.x"
            :y="node.y + 12"
            text-anchor="middle"
            fill="white"
            font-size="10"
            opacity="0.8"
          >
            {{ node.assigneeName || '未分配' }} · {{ taskStatusLabel(node.status) }}
          </text>
          <title>{{ node.title }} · {{ taskStatusLabel(node.status) }}</title>
        </g>
      </svg>
      </div>
    </section>
  </main>
</template>

<style scoped>
.graph-container {
  padding: 0;
  overflow: auto;
}

.graph-stage { position: relative; width: max-content; min-width: 100%; }
.graph-legend {
  position: sticky;
  z-index: 2;
  top: 10px;
  left: 12px;
  display: flex;
  width: max-content;
  gap: 12px;
  padding: 7px 10px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: rgba(255, 255, 255, .94);
  font-size: 12px;
}
.graph-legend span { display: flex; align-items: center; gap: 5px; }
.graph-legend i { width: 9px; height: 9px; border-radius: 50%; background: #909399; }
.graph-legend .progress { background: #409eff; }
.graph-legend .blocked { background: #e6a23c; }
.graph-legend .done { background: #67c23a; }
.dependency-graph {
  display: block;
  background-color: #fafbfe;
  background-image: radial-gradient(#dfe3ec 1px, transparent 1px);
  background-size: 18px 18px;
  border: 1px solid #e4e7ed;
  border-radius: 12px;
}
</style>
