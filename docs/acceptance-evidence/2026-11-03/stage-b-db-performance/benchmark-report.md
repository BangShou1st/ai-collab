# PostgreSQL 性能基线与优化实测证据 (阶段 B)

测试环境：PostgreSQL 17.11 (Debian 17.11-1.pgdg12+2) 在 Docker 容器 `ai-collab-postgres` 运行。
测试方案：参数化合成数据集（1k, 10k, 100k 任务量），固定随机种子 (`setseed(0.42)`)，统一环境、先预热后多轮中位数采样。

## 1. 核心性能对比汇总表

| 查询编号 | 查询方法 | 数据规模 | 优化前耗时 (ms) | 优化后耗时 (ms) | 耗时提升比例 | 优化前 Buffers Hit | 优化后 Buffers Hit | Buffers 降低比例 |
|---|---|---|---|---|---|---|---|---|
| Q1_myTasks | `HomeMapper.myTasks()` | 1k | 6.030 | 0.178 | +97.0% | 4574 | 606 | +86.8% |
| Q1_myTasks | `HomeMapper.myTasks()` | 10k | 1.167 | 0.216 | +81.5% | 1068 | 620 | +41.9% |
| Q1_myTasks | `HomeMapper.myTasks()` | 100k | 9.804 | 0.252 | +97.4% | 6429 | 621 | +90.3% |
| Q2_listScoped | `TaskMapper.listScoped()` | 1k | 10.107 | 2.173 | +78.5% | 6207 | 1822 | +70.6% |
| Q2_listScoped | `TaskMapper.listScoped()` | 10k | 19.863 | 14.840 | +25.3% | 15974 | 10948 | +31.5% |
| Q2_listScoped | `TaskMapper.listScoped()` | 100k | 346.944 | 405.204 | -16.8% | 112534 | 112628 | -0.1% |
| Q3_findProjectInfo | `ProjectDashboardMapper.findProjectInfo()` | 1k | 0.122 | 0.117 | +4.1% | 175 | 175 | 0.0% |
| Q3_findProjectInfo | `ProjectDashboardMapper.findProjectInfo()` | 10k | 0.114 | 0.123 | -7.9% | 175 | 175 | 0.0% |
| Q3_findProjectInfo | `ProjectDashboardMapper.findProjectInfo()` | 100k | 0.117 | 0.110 | +6.0% | 175 | 175 | 0.0% |
| Q4_countTasksByStatus | `ProjectDashboardMapper.countTasksByStatus()` | 1k | 8.446 | 0.331 | +96.1% | 4718 | 301 | +93.6% |
| Q4_countTasksByStatus | `ProjectDashboardMapper.countTasksByStatus()` | 10k | 6.877 | 2.468 | +64.1% | 5096 | 745 | +85.4% |
| Q4_countTasksByStatus | `ProjectDashboardMapper.countTasksByStatus()` | 100k | 14.026 | 13.563 | +3.3% | 4894 | 4970 | -1.6% |
| Q5_listMilestoneProgress | `ProjectDashboardMapper.listMilestoneProgress()` | 1k | 9.090 | 0.377 | +95.9% | 4849 | 400 | +91.8% |
| Q5_listMilestoneProgress | `ProjectDashboardMapper.listMilestoneProgress()` | 10k | 2.412 | 1.267 | +47.5% | 1501 | 454 | +69.8% |
| Q5_listMilestoneProgress | `ProjectDashboardMapper.listMilestoneProgress()` | 100k | 18.851 | 10.606 | +43.7% | 4982 | 2263 | +54.6% |
| Q6_listRecentTasks | `ProjectDashboardMapper.listRecentTasks()` | 1k | 10.024 | 0.363 | +96.4% | 5082 | 643 | +87.3% |
| Q6_listRecentTasks | `ProjectDashboardMapper.listRecentTasks()` | 10k | 3.894 | 0.402 | +89.7% | 1028 | 642 | +37.5% |
| Q6_listRecentTasks | `ProjectDashboardMapper.listRecentTasks()` | 100k | 44.165 | 0.256 | +99.4% | 5217 | 652 | +87.5% |
| Q7_listRecentDocuments | `ProjectDashboardMapper.listRecentDocuments()` | 1k | 0.433 | 0.164 | +62.1% | 694 | 611 | +12.0% |
| Q7_listRecentDocuments | `ProjectDashboardMapper.listRecentDocuments()` | 10k | 0.368 | 0.181 | +50.8% | 686 | 603 | +12.1% |
| Q7_listRecentDocuments | `ProjectDashboardMapper.listRecentDocuments()` | 100k | 0.293 | 0.136 | +53.6% | 694 | 611 | +12.0% |

## 2. 详细执行节点与扫描方式对比

### Q1_myTasks: HomeMapper.myTasks()

> **用途**：个人首页未完成任务查询 (按截止日期升序+更新时间倒序)

#### 数据规模：1k 任务

- **优化前**：耗时 `6.03 ms`，Buffers Hit `4574`
  - 扫描节点：Seq Scan on project → Seq Scan on project_member → Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_assignee)
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.178 ms`，Buffers Hit `606`
  - 扫描节点：Index Scan (idx_task_assignee_active_due) → Seq Scan on project_member → Seq Scan on project
  - 排序节点：无

#### 数据规模：10k 任务

- **优化前**：耗时 `1.167 ms`，Buffers Hit `1068`
  - 扫描节点：Seq Scan on project → Seq Scan on project_member → Index Scan (idx_task_project_assignee)
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.216 ms`，Buffers Hit `620`
  - 扫描节点：Index Scan (idx_task_assignee_active_due) → Index Only Scan (idx_project_member_user) → Index Scan (project_pkey)
  - 排序节点：无

#### 数据规模：100k 任务

- **优化前**：耗时 `9.804 ms`，Buffers Hit `6429`
  - 扫描节点：Seq Scan on project → Seq Scan on project_member → Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_assignee)
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.252 ms`，Buffers Hit `621`
  - 扫描节点：Index Scan (idx_task_assignee_active_due) → Index Only Scan (idx_project_member_user) → Index Scan (project_pkey)
  - 排序节点：无

### Q2_listScoped: TaskMapper.listScoped()

> **用途**：项目任务列表查询 (按创建时间升序+关联子查询统计未完成依赖)

#### 数据规模：1k 任务

- **优化前**：耗时 `10.107 ms`，Buffers Hit `6207`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_due) → Seq Scan on app_user → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [quicksort]
- **优化后**：耗时 `2.173 ms`，Buffers Hit `1822`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_created) → Index Scan (app_user_pkey) → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [quicksort]

#### 数据规模：10k 任务

- **优化前**：耗时 `19.863 ms`，Buffers Hit `15974`
  - 扫描节点：Seq Scan on project_task → Seq Scan on app_user → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [quicksort]
- **优化后**：耗时 `14.84 ms`，Buffers Hit `10948`
  - 扫描节点：Seq Scan on project_task → Seq Scan on app_user → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [quicksort]

#### 数据规模：100k 任务

- **优化前**：耗时 `346.944 ms`，Buffers Hit `112534`
  - 扫描节点：Seq Scan on project_task → Seq Scan on app_user → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [quicksort]
- **优化后**：耗时 `405.204 ms`，Buffers Hit `112628`
  - 扫描节点：Seq Scan on project_task → Seq Scan on app_user → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [quicksort]

### Q3_findProjectInfo: ProjectDashboardMapper.findProjectInfo()

> **用途**：项目看板基本信息与成员总数查询

#### 数据规模：1k 任务

- **优化前**：耗时 `0.122 ms`，Buffers Hit `175`
  - 扫描节点：Seq Scan on project_member → Seq Scan on project → Seq Scan on project_member
  - 排序节点：无
- **优化后**：耗时 `0.117 ms`，Buffers Hit `175`
  - 扫描节点：Seq Scan on project_member → Seq Scan on project → Seq Scan on project_member
  - 排序节点：无

#### 数据规模：10k 任务

- **优化前**：耗时 `0.114 ms`，Buffers Hit `175`
  - 扫描节点：Seq Scan on project_member → Seq Scan on project → Seq Scan on project_member
  - 排序节点：无
- **优化后**：耗时 `0.123 ms`，Buffers Hit `175`
  - 扫描节点：Seq Scan on project_member → Seq Scan on project → Seq Scan on project_member
  - 排序节点：无

#### 数据规模：100k 任务

- **优化前**：耗时 `0.117 ms`，Buffers Hit `175`
  - 扫描节点：Seq Scan on project_member → Seq Scan on project → Seq Scan on project_member
  - 排序节点：无
- **优化后**：耗时 `0.11 ms`，Buffers Hit `175`
  - 扫描节点：Seq Scan on project_member → Seq Scan on project → Seq Scan on project_member
  - 排序节点：无

### Q4_countTasksByStatus: ProjectDashboardMapper.countTasksByStatus()

> **用途**：项目看板状态统计与完成率/逾期计算 (FILTER 单扫描多聚合)

#### 数据规模：1k 任务

- **优化前**：耗时 `8.446 ms`，Buffers Hit `4718`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_due)
  - 排序节点：无
- **优化后**：耗时 `0.331 ms`，Buffers Hit `301`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_created)
  - 排序节点：无

#### 数据规模：10k 任务

- **优化前**：耗时 `6.877 ms`，Buffers Hit `5096`
  - 扫描节点：Seq Scan on project_task
  - 排序节点：无
- **优化后**：耗时 `2.468 ms`，Buffers Hit `745`
  - 扫描节点：Seq Scan on project_task
  - 排序节点：无

#### 数据规模：100k 任务

- **优化前**：耗时 `14.026 ms`，Buffers Hit `4894`
  - 扫描节点：Seq Scan on project_task
  - 排序节点：无
- **优化后**：耗时 `13.563 ms`，Buffers Hit `4970`
  - 扫描节点：Seq Scan on project_task
  - 排序节点：无

### Q5_listMilestoneProgress: ProjectDashboardMapper.listMilestoneProgress()

> **用途**：项目看板里程碑进度统计 (分组聚合 project_task 并左连接 milestone)

#### 数据规模：1k 任务

- **优化前**：耗时 `9.09 ms`，Buffers Hit `4849`
  - 扫描节点：Index Scan (idx_milestone_project) → Subquery Scan → Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_due)
  - 排序节点：Incremental Sort → Sort [quicksort]
- **优化后**：耗时 `0.377 ms`，Buffers Hit `400`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_milestone) → Seq Scan on milestone
  - 排序节点：Sort [quicksort]

#### 数据规模：10k 任务

- **优化前**：耗时 `2.412 ms`，Buffers Hit `1501`
  - 扫描节点：Index Scan (idx_milestone_project) → Subquery Scan → Index Scan (idx_task_milestone)
  - 排序节点：Incremental Sort
- **优化后**：耗时 `1.267 ms`，Buffers Hit `454`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_milestone) → Seq Scan on milestone
  - 排序节点：Sort [quicksort]

#### 数据规模：100k 任务

- **优化前**：耗时 `18.851 ms`，Buffers Hit `4982`
  - 扫描节点：Index Scan (idx_milestone_project) → Subquery Scan → Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_due)
  - 排序节点：Incremental Sort
- **优化后**：耗时 `10.606 ms`，Buffers Hit `2263`
  - 扫描节点：Index Scan (idx_milestone_project) → Subquery Scan → Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_milestone)
  - 排序节点：Incremental Sort

### Q6_listRecentTasks: ProjectDashboardMapper.listRecentTasks()

> **用途**：项目看板最近更新任务 (更新时间倒序+主键倒序+LIMIT 8)

#### 数据规模：1k 任务

- **优化前**：耗时 `10.024 ms`，Buffers Hit `5082`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_due) → Index Scan (app_user_pkey) → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.363 ms`，Buffers Hit `643`
  - 扫描节点：Index Scan (idx_task_project_updated) → Index Scan (app_user_pkey) → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：无

#### 数据规模：10k 任务

- **优化前**：耗时 `3.894 ms`，Buffers Hit `1028`
  - 扫描节点：Seq Scan on project_task → Seq Scan on app_user → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.402 ms`，Buffers Hit `642`
  - 扫描节点：Index Scan (idx_task_project_updated) → Index Scan (app_user_pkey) → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：无

#### 数据规模：100k 任务

- **优化前**：耗时 `44.165 ms`，Buffers Hit `5217`
  - 扫描节点：Bitmap Heap Scan on project_task → Bitmap Index Scan (idx_task_project_due) → Seq Scan on app_user → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.256 ms`，Buffers Hit `652`
  - 扫描节点：Index Scan (idx_task_project_updated) → Index Scan (app_user_pkey) → Seq Scan on milestone → Index Only Scan (task_dependency_pkey) → Index Scan (project_task_pkey)
  - 排序节点：无

### Q7_listRecentDocuments: ProjectDashboardMapper.listRecentDocuments()

> **用途**：项目看板最近文档 (创建时间倒序+主键倒序+可见性视图+LIMIT 5)

#### 数据规模：1k 任务

- **优化前**：耗时 `0.433 ms`，Buffers Hit `694`
  - 扫描节点：Seq Scan on project_document → Index Scan (project_document_pkey) → Index Scan (uk_connector_source_project_folder) → Index Scan (idx_external_document_source) → Index Scan (uk_connector_oauth_project_user) → Seq Scan on app_user
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.164 ms`，Buffers Hit `611`
  - 扫描节点：Index Scan (idx_document_project_recent) → Index Scan (project_document_pkey) → Index Scan (uk_connector_source_project_folder) → Index Scan (idx_external_document_source) → Index Scan (uk_connector_oauth_project_user) → Seq Scan on app_user
  - 排序节点：无

#### 数据规模：10k 任务

- **优化前**：耗时 `0.368 ms`，Buffers Hit `686`
  - 扫描节点：Seq Scan on project_document → Index Scan (project_document_pkey) → Index Scan (uk_connector_source_project_folder) → Index Scan (idx_external_document_source) → Index Scan (uk_connector_oauth_project_user) → Seq Scan on app_user
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.181 ms`，Buffers Hit `603`
  - 扫描节点：Index Scan (idx_document_project_recent) → Index Scan (project_document_pkey) → Index Scan (uk_connector_source_project_folder) → Index Scan (idx_external_document_source) → Index Scan (uk_connector_oauth_project_user) → Seq Scan on app_user
  - 排序节点：无

#### 数据规模：100k 任务

- **优化前**：耗时 `0.293 ms`，Buffers Hit `694`
  - 扫描节点：Seq Scan on project_document → Index Scan (project_document_pkey) → Index Scan (uk_connector_source_project_folder) → Index Scan (idx_external_document_source) → Index Scan (uk_connector_oauth_project_user) → Seq Scan on app_user
  - 排序节点：Sort [top-N heapsort]
- **优化后**：耗时 `0.136 ms`，Buffers Hit `611`
  - 扫描节点：Index Scan (idx_document_project_recent) → Index Scan (project_document_pkey) → Index Scan (uk_connector_source_project_folder) → Index Scan (idx_external_document_source) → Index Scan (uk_connector_oauth_project_user) → Seq Scan on app_user
  - 排序节点：无

