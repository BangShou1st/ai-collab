# P2 Frontend Information Architecture and Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the application readable and task-oriented with fixed navigation, bounded chat layouts, equal conversation rows, consolidated project insights, and understandable calendar, Gantt, dependency, and load graphics.

**Architecture:** Keep existing Vue routes as backward-compatible redirects while introducing grouped landing routes for Work, Knowledge, AI, Insights, and Settings. Use a `100dvh` shell with fixed-height navigation and `min-height:0` content slots. Extract pure visualization layout helpers so date placement, dependency coordinates, and load scales are testable without a browser.

**Tech Stack:** Vue 3, TypeScript, Vue Router, Element Plus, SVG, CSS Grid/Flex, Vitest, Vue Test Utils.

## Global Constraints

- System and project navigation remain fixed while page content changes.
- The top project navigation contains only `概览`, `工作`, `知识`, `AI`, `项目洞察`, and `设置`.
- Knowledge Q&A and Agent pages have no body/page vertical scrollbar; only message regions scroll.
- Conversation history rows are equal height from first item to last item.
- User-facing copy is concise and action-oriented; promotional and implementation-language descriptions are removed.
- Calendar renders every task instead of collapsing to `+N`.
- Existing deep links remain valid through redirects or preserved child routes.
- Visual changes preserve keyboard focus, accessible labels, status text, and backend authorization.

---

### Task 1: Build a fixed application shell and grouped project navigation

**Files:**
- Create: `ai-collab-frontend/src/shared/project-navigation.ts`
- Create test: `ai-collab-frontend/src/shared/project-navigation.test.ts`
- Modify: `ai-collab-frontend/src/shared/AppShell.vue`
- Modify: `ai-collab-frontend/src/App.vue`
- Modify: `ai-collab-frontend/src/styles.css`
- Modify: `ai-collab-frontend/src/router.ts`
- Create test: `ai-collab-frontend/src/shared/AppShell.test.ts`

**Interfaces:**
- Produces: `projectNavigation(projectId, role)` with six items.
- Produces CSS variables: `--system-bar-height`, `--project-bar-height`.
- Preserves old visualization URLs as redirects to `/projects/:projectId/insights?view=...`.

- [ ] **Step 1: Write navigation grouping tests**

```ts
it('returns six task-oriented project entries', () => {
  expect(projectNavigation('p1', 'OWNER').map(item => item.label))
    .toEqual(['概览', '工作', '知识', 'AI', '项目洞察', '设置'])
})
```

Assert member settings omit admin-only subitems while the settings entry remains available for permitted account/project actions.

- [ ] **Step 2: Run and observe the missing module**

```powershell
Set-Location ai-collab-frontend
pnpm test -- src/shared/project-navigation.test.ts
```

Expected: FAIL at import time.

- [ ] **Step 3: Implement grouped routes and compatibility redirects**

Add:

```text
/projects/:projectId/work
/projects/:projectId/knowledge
/projects/:projectId/ai
/projects/:projectId/insights
/projects/:projectId/settings
```

Map existing board, milestone, document, Q&A, planning, Agent, member, and audit routes into group selections without losing browser history. Redirect old Gantt/dependency/calendar/load/report/risk/comparison URLs to the matching `view` query.

- [ ] **Step 4: Implement the fixed shell**

Use:

```css
html, body, #app { height: 100%; margin: 0; }
.app-shell {
  min-height: 100dvh;
  display: grid;
  grid-template-rows: var(--system-bar-height) auto minmax(0, 1fr);
}
.app-topbar { position: sticky; top: 0; z-index: 40; }
.project-nav { position: sticky; top: var(--system-bar-height); z-index: 35; }
.app-content { min-height: 0; overflow: auto; }
```

The project row is absent on non-project routes; the content row still fills remaining height.

- [ ] **Step 5: Remove nonessential shell copy**

Shorten the brand to the product name, remove the small promotional subtitle, and label global actions `项目`, `通知`, `系统管理`, and the user menu.

- [ ] **Step 6: Add shell behavior tests**

Assert admin link visibility, six project links, active group state for nested routes, and preserved notification/account navigation.

- [ ] **Step 7: Run frontend checks**

```powershell
pnpm test -- src/shared/project-navigation.test.ts src/shared/AppShell.test.ts
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 8: Commit fixed navigation**

```powershell
git add ai-collab-frontend/src/shared ai-collab-frontend/src/App.vue ai-collab-frontend/src/styles.css ai-collab-frontend/src/router.ts
git commit -m "feat(frontend): group and fix navigation"
```

### Task 2: Bound knowledge and Agent chat layouts

**Files:**
- Create: `ai-collab-frontend/src/shared/ChatWorkspace.vue`
- Create test: `ai-collab-frontend/src/shared/ChatWorkspace.test.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.vue`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.test.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.test.ts`

**Interfaces:**
- Produces: `ChatWorkspace` slots `history`, `messages`, `composer`, `status`.
- Produces stable selectors `data-test=chat-workspace`, `chat-history-item`, and `chat-message-scroll`.

- [ ] **Step 1: Write a component structure test**

Assert the root has class `chat-workspace`, each of three sample history entries has the same `chat-history-item` class, the messages slot is the only element with `overflow-y:auto`, and the composer is outside that scroll element.

- [ ] **Step 2: Run and observe failure**

```powershell
Set-Location ai-collab-frontend
pnpm test -- src/shared/ChatWorkspace.test.ts
```

Expected: FAIL because the shared workspace does not exist.

- [ ] **Step 3: Implement the viewport-bound workspace**

Use:

```css
.chat-page { height: 100%; min-height: 0; overflow: hidden; }
.chat-workspace {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
  overflow: hidden;
}
.chat-history, .chat-conversation { min-height: 0; }
.chat-message-scroll { min-height: 0; overflow-y: auto; }
.chat-history-item { height: 56px; min-height: 56px; }
```

At the mobile breakpoint, collapse history behind one icon button with `aria-expanded`.

- [ ] **Step 4: Migrate both pages**

Keep messages, feedback, Agent run cards, approvals, and composers in their existing feature components. Remove `PageHeader` descriptions and text such as “带来源的项目问答、进度检查、审批式写入与定时运行”. Use short empty-state prompts only when the conversation has no messages.

- [ ] **Step 5: Add equal-row and no-page-scroll assertions**

In component tests, assert all history buttons expose the same class and no ancestor inside the feature page uses `overflow-y:auto` except `chat-message-scroll`.

- [ ] **Step 6: Run chat tests and build**

```powershell
pnpm test -- src/shared/ChatWorkspace.test.ts src/modules/agent/AgentView.test.ts src/modules/knowledge/KnowledgeView.test.ts
pnpm typecheck
pnpm build
```

Expected: PASS.

- [ ] **Step 7: Commit chat layout**

```powershell
git add ai-collab-frontend/src/shared/ChatWorkspace.vue ai-collab-frontend/src/shared/ChatWorkspace.test.ts ai-collab-frontend/src/modules/knowledge ai-collab-frontend/src/modules/agent
git commit -m "fix(frontend): bound chat workspaces"
```

### Task 3: Consolidate project insights

**Files:**
- Create: `ai-collab-frontend/src/modules/work/ProjectInsightsView.vue`
- Create: `ai-collab-frontend/src/modules/work/insight-tabs.ts`
- Create test: `ai-collab-frontend/src/modules/work/ProjectInsightsView.test.ts`
- Modify: `ai-collab-frontend/src/router.ts`
- Modify: `ai-collab-frontend/src/modules/work/GanttView.vue`
- Modify: `ai-collab-frontend/src/modules/work/DependencyGraphView.vue`
- Modify: `ai-collab-frontend/src/modules/work/CalendarView.vue`
- Modify: `ai-collab-frontend/src/modules/work/MemberLoadView.vue`
- Modify: `ai-collab-frontend/src/modules/work/WeeklyReportView.vue`
- Modify: `ai-collab-frontend/src/modules/work/RiskAnalysisView.vue`
- Modify: `ai-collab-frontend/src/modules/work/PlanComparisonView.vue`

**Interfaces:**
- Produces query values: `gantt`, `dependencies`, `calendar`, `load`, `weekly`, `risk`, `comparison`.
- Child views accept `embedded?: boolean` to omit duplicate page headers.

- [ ] **Step 1: Write tab routing tests**

Assert an absent or invalid `view` selects `gantt`; changing a tab replaces only the query; browser back restores the prior tab; all seven labels are present.

- [ ] **Step 2: Run and observe failure**

```powershell
Set-Location ai-collab-frontend
pnpm test -- src/modules/work/ProjectInsightsView.test.ts
```

Expected: FAIL because the consolidated view does not exist.

- [ ] **Step 3: Implement the insights shell**

Use compact icon-plus-label tabs, one shared title `项目洞察`, and lazy-render only the active view. Preserve child loading and errors. Remove repeated project API calls by loading project context from the existing store.

- [ ] **Step 4: Run focused tests**

```powershell
pnpm test -- src/modules/work/ProjectInsightsView.test.ts
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 5: Commit consolidated insights**

```powershell
git add ai-collab-frontend/src/modules/work ai-collab-frontend/src/router.ts
git commit -m "feat(frontend): consolidate project insights"
```

### Task 4: Rebuild the calendar as an all-task month view

**Files:**
- Create: `ai-collab-frontend/src/modules/work/calendar-layout.ts`
- Create test: `ai-collab-frontend/src/modules/work/calendar-layout.test.ts`
- Modify: `ai-collab-frontend/src/modules/work/CalendarView.vue`
- Create test: `ai-collab-frontend/src/modules/work/CalendarView.test.ts`
- Modify: `ai-collab-frontend/src/modules/work/visualization-api.ts`

**Interfaces:**
- Produces: `buildCalendarMonth(year, month, events, today): CalendarCell[]`
- `CalendarCell` contains ISO date, day number, in-current-month, isToday, and all events.

- [ ] **Step 1: Write pure month-layout tests**

Test a month starting Sunday and a month spanning six rows. Supply five events on one date and assert all five remain in the cell array. Assert exactly one cell has `isToday=true` when today is in the rendered grid.

- [ ] **Step 2: Run and observe failure**

```powershell
Set-Location ai-collab-frontend
pnpm test -- src/modules/work/calendar-layout.test.ts
```

Expected: FAIL because the helper does not exist.

- [ ] **Step 3: Implement full month cells**

Render leading and trailing dates, not blank boxes. Sort events by overdue, priority, status, and title. Do not slice events and do not render `+N`.

- [ ] **Step 4: Render task chips and graphical controls**

Use `<` and `>` button text with `aria-label="上个月"` and `aria-label="下个月"`. Add `今天` action. Each event chip shows a status dot/icon, title, and priority color; overdue tasks have a visible overdue symbol and text available to screen readers. Add a clear `今日` badge.

- [ ] **Step 5: Add component tests**

Assert five task titles render in one date cell, `+2` does not appear, one today badge exists, and navigation buttons emit a single reload with the correct year/month.

- [ ] **Step 6: Run calendar tests**

```powershell
pnpm test -- src/modules/work/calendar-layout.test.ts src/modules/work/CalendarView.test.ts
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 7: Commit calendar**

```powershell
git add ai-collab-frontend/src/modules/work/calendar-layout.ts ai-collab-frontend/src/modules/work/calendar-layout.test.ts ai-collab-frontend/src/modules/work/CalendarView.vue ai-collab-frontend/src/modules/work/CalendarView.test.ts ai-collab-frontend/src/modules/work/visualization-api.ts
git commit -m "feat(frontend): show every calendar task"
```

### Task 5: Make the Gantt timeline date-accurate

**Files:**
- Create: `ai-collab-frontend/src/modules/work/gantt-layout.ts`
- Create test: `ai-collab-frontend/src/modules/work/gantt-layout.test.ts`
- Modify: `ai-collab-frontend/src/modules/work/GanttView.vue`
- Create test: `ai-collab-frontend/src/modules/work/GanttView.test.ts`

**Interfaces:**
- Produces: `buildGanttLayout(tasks, milestones, today)` with day columns, task bars, milestone positions, today offset, and undated tasks.

- [ ] **Step 1: Write date-placement tests**

Assert a task from `2026-08-03` to `2026-08-05` begins at the correct day index and spans three inclusive columns. Assert a due-date-only task is one day, an undated task is returned separately, and today offset is stable.

- [ ] **Step 2: Run and observe current hard-coded timeline failure**

```powershell
Set-Location ai-collab-frontend
pnpm test -- src/modules/work/gantt-layout.test.ts
```

Expected: FAIL because current view renders days 1–30 independent of real dates.

- [ ] **Step 3: Implement the layout helper**

Determine min/max dates from tasks and milestones, pad by two days, cap the initial visible range while retaining horizontal scrolling, and generate ISO day columns. Calculate percentages or grid-column indices from actual dates.

- [ ] **Step 4: Render the timeline**

Use a sticky 220px task column, weekday/weekend headers, a red today line, status-colored bars, progress fill, milestone diamonds, and SVG dependency arrows. Keep dates and task titles available as text/tooltips.

- [ ] **Step 5: Add component tests**

Assert real ISO dates appear, today marker renders, undated tasks are listed, and dependency arrow count matches response dependencies.

- [ ] **Step 6: Run Gantt tests**

```powershell
pnpm test -- src/modules/work/gantt-layout.test.ts src/modules/work/GanttView.test.ts
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 7: Commit Gantt**

```powershell
git add ai-collab-frontend/src/modules/work/gantt-layout.ts ai-collab-frontend/src/modules/work/gantt-layout.test.ts ai-collab-frontend/src/modules/work/GanttView.vue ai-collab-frontend/src/modules/work/GanttView.test.ts
git commit -m "feat(frontend): render accurate gantt timeline"
```

### Task 6: Improve dependency and member-load graphics

**Files:**
- Create: `ai-collab-frontend/src/modules/work/dependency-layout.ts`
- Create test: `ai-collab-frontend/src/modules/work/dependency-layout.test.ts`
- Modify: `ai-collab-frontend/src/modules/work/DependencyGraphView.vue`
- Create: `ai-collab-frontend/src/modules/work/load-scale.ts`
- Create test: `ai-collab-frontend/src/modules/work/load-scale.test.ts`
- Modify: `ai-collab-frontend/src/modules/work/MemberLoadView.vue`
- Create test: `ai-collab-frontend/src/modules/work/MemberLoadView.test.ts`

**Interfaces:**
- Produces: deterministic layered DAG positions from nodes and edges.
- Produces: `buildLoadRows(members)` with comparable widths and severity.

- [ ] **Step 1: Write dependency layout tests**

For `A -> B -> C` plus independent `D`, assert A, B, and C occupy increasing layers, all coordinates are finite, and repeated calls return identical positions. Assert cycle input is rendered in a fallback layer and marked conflicted rather than causing recursion.

- [ ] **Step 2: Write load scale tests**

Given 4h, 8h, and 16h loads, assert bar widths are 25%, 50%, and 100%. Define severity thresholds from the project data contract and assert unassigned workload appears as its own row.

- [ ] **Step 3: Run and observe missing helpers**

```powershell
Set-Location ai-collab-frontend
pnpm test -- src/modules/work/dependency-layout.test.ts src/modules/work/load-scale.test.ts
```

Expected: FAIL at import time.

- [ ] **Step 4: Implement the dependency graph**

Render a responsive SVG viewBox, arrow markers, status-colored nodes, highlighted blocked edges, a legend, zoom-to-fit, and a relayout button. Avoid repeated `find` calls in the template by using a precomputed node map and edge geometry.

- [ ] **Step 5: Implement comparable member-load bars**

Replace separate cards as the primary visualization with aligned horizontal bars. Show estimate hours and task count on the same row, use consistent maximum scale, color normal/high/overload, and include unassigned work.

- [ ] **Step 6: Run focused tests and build**

```powershell
pnpm test -- src/modules/work/dependency-layout.test.ts src/modules/work/load-scale.test.ts src/modules/work/MemberLoadView.test.ts
pnpm typecheck
pnpm build
```

Expected: PASS.

- [ ] **Step 7: Commit dependency and load visuals**

```powershell
git add ai-collab-frontend/src/modules/work/dependency-layout.ts ai-collab-frontend/src/modules/work/dependency-layout.test.ts ai-collab-frontend/src/modules/work/DependencyGraphView.vue ai-collab-frontend/src/modules/work/load-scale.ts ai-collab-frontend/src/modules/work/load-scale.test.ts ai-collab-frontend/src/modules/work/MemberLoadView.vue ai-collab-frontend/src/modules/work/MemberLoadView.test.ts
git commit -m "feat(frontend): clarify dependency and load views"
```

### Task 7: Add real browser layout acceptance and verify P2

**Files:**
- Modify: `ai-collab-frontend/package.json`
- Modify: `ai-collab-frontend/pnpm-lock.yaml`
- Create: `ai-collab-frontend/playwright.config.ts`
- Create: `ai-collab-frontend/e2e/layout-and-agent.spec.ts`
- Modify: `docs/feature-matrix.md`
- Create: `docs/acceptance/2026-07-29-agent-browser-acceptance.md`

**Interfaces:**
- Produces: `pnpm test:e2e`
- Tests local frontend at `http://localhost:5173` and backend at `http://localhost:8080`.

- [ ] **Step 1: Add Playwright and write the failing layout test**

The test logs in from environment variables, opens knowledge Q&A, and asserts:

```ts
expect(await page.evaluate(() =>
  document.documentElement.scrollHeight <= document.documentElement.clientHeight
)).toBe(true)
```

Assert three history items have one identical bounding-box height, system and project bars keep the same top position after message scrolling, and only the message region changes `scrollTop`.

- [ ] **Step 2: Run and observe failure**

```powershell
Set-Location ai-collab-frontend
pnpm test:e2e -- --grep "chat workspace"
```

Expected: FAIL until browser dependency and final layout are configured.

- [ ] **Step 3: Add browser checks for navigation and visualizations**

Assert six project navigation items, seven insights tabs, current-day badge, every seeded calendar task title, `<` and `>` controls, Gantt today line, dependency arrows, and aligned load bars.

- [ ] **Step 4: Add the fixed Agent acceptance test**

Submit the exact Chinese prompt, wait for a visible terminal state, assert a pending approval card contains title, high priority, and date, approve once, verify one matching task appears, then verify a second approval attempt does not create another task.

- [ ] **Step 5: Run the full verification suite**

```powershell
Set-Location ..\ai-collab-backend
.\mvnw.cmd test
Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build
pnpm test:e2e
Set-Location ..
.\scripts\validate-openapi.ps1
.\scripts\smoke-existing.ps1
git diff --check
git status --short
```

Expected: all commands exit 0; status still contains only intentional task changes plus pre-existing user changes.

- [ ] **Step 6: Record actual browser evidence**

Write exact commands, date, browser, viewport sizes, configured provider family, run ID, approval ID, created task ID, and pass/fail results. Do not include credentials, tokens, keys, prompts other than the fixed acceptance sentence, or model raw responses.

- [ ] **Step 7: Update factual status and commit**

Mark only verified capabilities complete.

```powershell
git add ai-collab-frontend/package.json ai-collab-frontend/pnpm-lock.yaml ai-collab-frontend/playwright.config.ts ai-collab-frontend/e2e docs/feature-matrix.md docs/acceptance/2026-07-29-agent-browser-acceptance.md
git commit -m "test(browser): verify agent and frontend usability"
```
