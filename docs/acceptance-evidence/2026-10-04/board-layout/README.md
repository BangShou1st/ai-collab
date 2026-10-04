布局复验（2026-10-04）

fixture.js 复用生产 TaskBoardCard、Element Plus 和全局 CSS，静态数据沿用已验收规划的四个标题/日期/依赖结构；没有业务 API 或数据库连接。重跑：将 fixture.js 复制到 ai-collab-frontend/board-layout-acceptance.js，用包含 #app 和该 module 脚本的临时 HTML 启动现有 Vite，浏览器访问该页面；结束后删除这两个临时入口。不要替换生产入口。

before/after JSON 为 bsk 对 DOM 的只读测量（CSS 像素），截图保留对应尺寸。before-desktop.png 是最初实际窗口，截图物理像素2244×1253，页面 CSS 视口1496×836，不混用这两种坐标。1440×900 修复后全视口截图两次 tool RPC timed out after 30s；没有保存该失败截图，随后 ref 截图虽返回成功，但目视内容是侧栏而非目标卡片，保留为 1440-ref-screenshot-mislocated.png，不能算卡片截图通过。1265×713 修复前后全视口截图均成功。原始浏览器 debugging 导出保留在忽略的 target，不提交整份源文件/网络内容。

验证：四张卡片的正文 clientHeight=scrollHeight；列表 scrollHeight>clientHeight。142 个既有前端测试通过，构建含 vue-tsc -b 并通过；无后端代码改动，不重复后端全量或真实模型。此为布局层复验，不替代先前真实业务主链路证据。

