<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { visualizationApi, type CalendarEvent } from './visualization-api'

const route = useRoute()
const projectId = route.params.projectId as string

const events = ref<CalendarEvent[]>([])
const loading = ref(false)
const currentDate = ref(new Date())

const project = ref<Project | null>(null)

const currentYear = computed(() => currentDate.value.getFullYear())
const currentMonth = computed(() => currentDate.value.getMonth() + 1)
const today = new Date()
const todayKey = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const monthName = computed(() => {
  const months = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月']
  return months[currentDate.value.getMonth()]
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const [result, projectResult] = await Promise.all([
      visualizationApi.calendar(projectId, currentYear.value, currentMonth.value),
      projectApi.get(projectId),
    ])
    events.value = result.data.events
    project.value = projectResult.data
  } catch (error) {
    showApiError(error, '日历加载')
  } finally {
    loading.value = false
  }
}

function previousMonth(): void {
  const date = new Date(currentDate.value)
  date.setMonth(date.getMonth() - 1)
  currentDate.value = date
  load()
}

function nextMonth(): void {
  const date = new Date(currentDate.value)
  date.setMonth(date.getMonth() + 1)
  currentDate.value = date
  load()
}

function goToday(): void {
  currentDate.value = new Date()
  load()
}

const calendarDays = computed(() => {
  const year = currentYear.value
  const month = currentMonth.value - 1
  const firstDay = new Date(year, month, 1)
  const lastDay = new Date(year, month + 1, 0)
  const daysInMonth = lastDay.getDate()
  const startDayOfWeek = firstDay.getDay()

  const days = []
  for (let i = 0; i < startDayOfWeek; i++) {
    days.push({ date: null, dateKey: '', events: [] as CalendarEvent[] })
  }
  for (let day = 1; day <= daysInMonth; day++) {
    const dateStr = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    const dayEvents = events.value.filter(e => e.date === dateStr)
    days.push({ date: day, dateKey: dateStr, events: dayEvents })
  }
  return days
})

function eventIcon(type: string): string {
  return type === 'MILESTONE' ? '◆' : '●'
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目执行"
      title="日历视图"
      :context="project?.name"
    />
    <section v-loading="loading" class="calendar-container">
      <div class="calendar-header">
        <div class="month-controls">
          <el-button circle aria-label="上个月" title="上个月" @click="previousMonth">&lt;</el-button>
          <el-button @click="goToday">今天</el-button>
        </div>
        <h2>{{ currentYear }}年 {{ monthName }}</h2>
        <el-button circle aria-label="下个月" title="下个月" @click="nextMonth">&gt;</el-button>
      </div>
      <div class="calendar-grid">
        <div class="calendar-weekday">日</div>
        <div class="calendar-weekday">一</div>
        <div class="calendar-weekday">二</div>
        <div class="calendar-weekday">三</div>
        <div class="calendar-weekday">四</div>
        <div class="calendar-weekday">五</div>
        <div class="calendar-weekday">六</div>
        <div
          v-for="(day, index) in calendarDays"
          :key="index"
          class="calendar-day"
          :class="{
            'other-month': !day.date,
            'has-events': day.events.length > 0,
            today: day.dateKey === todayKey,
          }"
        >
          <span v-if="day.date" class="day-number">
            {{ day.date }}<small v-if="day.dateKey === todayKey">今日</small>
          </span>
          <div v-if="day.events.length > 0" class="day-events">
            <div
              v-for="event in day.events"
              :key="event.id"
              class="calendar-event"
              :class="event.type.toLowerCase()"
              :title="event.title"
            >
              <span aria-hidden="true">{{ eventIcon(event.type) }}</span>
              <strong>{{ event.title }}</strong>
            </div>
          </div>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.calendar-container {
  padding: 0 24px;
}

.calendar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.month-controls { display: flex; gap: 8px; }

.calendar-header h2 {
  margin: 0;
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 1px;
  background-color: #e4e7ed;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.calendar-weekday {
  background-color: #f5f7fa;
  padding: 12px;
  text-align: center;
  font-weight: 500;
  color: #606266;
}

.calendar-day {
  background-color: white;
  min-height: 132px;
  padding: 8px;
  min-width: 0;
}

.calendar-day.other-month {
  background-color: #fafafa;
  color: #c0c4cc;
}

.calendar-day.has-events {
  background-color: #fbfdff;
}
.calendar-day.today {
  box-shadow: inset 0 0 0 2px var(--color-primary);
}

.day-number {
  font-size: 14px;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.day-number small {
  padding: 1px 6px;
  border-radius: 9px;
  color: #fff;
  background: var(--color-primary);
  font-size: 10px;
}

.day-events {
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.calendar-event {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 5px;
  padding: 3px 6px;
  border-radius: 6px;
  color: #245aa5;
  background: #eaf3ff;
  font-size: 11px;
}
.calendar-event.milestone { color: #87591a; background: #fff3dc; }
.calendar-event strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 760px) {
  .calendar-container { padding: 0; overflow-x: auto; }
  .calendar-grid { min-width: 840px; }
}
</style>
