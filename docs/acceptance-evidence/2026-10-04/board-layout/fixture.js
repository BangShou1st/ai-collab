import { createApp, h } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import './src/styles.css';
import TaskBoardCard from './src/modules/work/TaskBoardCard.vue';
const titles=['实现会议纪要记录能力','实现待办生成能力','实现人工审阅能力','端到端串联验收与规划约束核对'];
const tasks=titles.map((title,i)=>({id:`fixture-${i}`,title,status:'TODO',priority:i===2?'MEDIUM':'HIGH',assigneeDisplayName:i===0?'Local Owner':null,dueDate:['2026-10-12','2026-10-16','2026-10-20','2026-10-25'][i],dependencyIds:i?[`fixture-${i-1}`]:[],unfinishedDependencyCount:i?1:0}));
createApp({render:()=>h('div',{class:'app-shell'},[h('aside',{class:'app-sidebar'},'隔离布局复验（生产卡片与CSS，静态资料，不连接业务API）'),h('div',{class:'app-content'},h('main',{class:'workspace-page board-page'},[h('h1','任务看板'),h('section',{class:'filter-bar'},'复用已验收规划的四个标题、日期与依赖摘要'),h('section',{class:'board-grid'},['待处理','进行中','已阻塞','已完成','已取消'].map((status,i)=>h('div',{class:'board-column'},[h('h2',`${status} ${i?0:4}`),h('div',{class:'board-tasks'},i?h('div',{class:'board-empty'},'暂无任务'):tasks.map(task=>h(TaskBoardCard,{task,canChangeStatus:true,opening:false,updating:false,operationLocked:false})))])))]))])}).use(ElementPlus).mount('#app');
