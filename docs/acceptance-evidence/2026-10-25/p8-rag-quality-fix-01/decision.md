# 准入裁决

本轮分别裁决：

- `Q02_EXPERIMENT_ONLY`：实验中可把条件送入QA上下文与Agent局部quote，但QA同时丢Q03门禁；生产补读/摘录不准入。
- `AGENT_EVIDENCE_EXPERIMENT_ONLY`：S1/S2在固定8来源下救回4项marker，但牺牲独立文档覆盖并失败于必要少数文档反例；生产选择器不改。
- `CITATION_CONTRACT_FIXED`：只准入子来源收口时的当前身份、持久化读取依据和原摘录匹配；模型正文保持原样。

总体裁决：**`P8_RAG_QUALITY_FIX_01_ACCEPTED`，范围限定为受控实验的 NO_CHANGE 裁决与获准的引用身份修复。** 此结论不表示Q02或Agent默认8的事实缺口已经修复，不表示真实Chat生成质量通过。最终交付还须完成F16的同SHA Push/PR CI检查；提交本身不被当作CI通过，最终回执通过GitHub在线运行和交付消息独立核实。

| 编号 | 验收结果与证据 |
| --- | --- |
| F01 | PASS：真实复测Q02排名23、0.3643，Top8/20缺失 |
| F02 | MEASURED / NO_CHANGE：R1/R2候选获得、正常选择、保留名额分别测量；只有带回归的组合进入QA有效上下文 |
| F03 | PASS_BOUNDARY：808 code points，条件的零起始偏移为785；600前缀缺失；连续[208,808)实验quote保留。未接入实际Runtime的候选不判生产修复 |
| F04 | PASS：当前QA26/27；未准入的R2保留名额臂丢Q03，已明确否决 |
| F05 | PASS_BASELINE：当前Agent8为22/27；Q03两项、Q04一项、Q05旧版一项明确为选择缺失 |
| F06 | MEASURED / NO_CHANGE：同Top20、8名额、8000总预算；S1/S2到26/27但文档覆盖下降；S3仅12名额对照25/27 |
| F07 | PASS：Q09双合同身份独立，实际工具及持久化复测不合并 |
| F08 | PASS：既有SOURCE_CAP、E1–E11与QA S2回归继续通过；新必要少数文档反例拒绝候选策略 |
| F09 | PASS：现有真实guard及跨层非成员/限定范围检查；补读项目、成员、已命中文档过滤负例通过 |
| F10 | PASS：READY、provider/model/dimension/fingerprint/generation负例通过；生产检索过滤不变 |
| F11 | PASS_CURRENT / EXPERIMENT_LIMITED：当前链路quote/模型可见/持久化身份一致；研究窗口有原文位置，但没有候选Runtime验收，不能宣称修复 |
| F12 | PASS：QA单项/分组引用既有测试未变；最终完整回归核验 |
| F13 | PASS_MECHANICAL：AVAILABLE/EXPLICIT/SUPPORTED三集合分开；11项真实PostgreSQL引用反例，前端仅静态源码审计 |
| F14 | PASS_BOUNDED_WITH_LIMITS：新增生产引用SQL最多1条/50身份；实验补读1条/4块/2文档；code points与token分列，全链SQL及HTTP缓存次数未全局instrument，真实token不可得 |
| F15 | PASS：历史与冻结语料未覆盖；P8原manifest290项在工作区和Git暂存字节一致，见integrity与inherited-git-bytes |
| F16 | LOCAL_VERIFIED / FINAL_CI_AT_DELIVERY：本地完整verify及前端四项见regression-results；最终dev SHA的Push/PR CI由GitHub在线回执与最终交付消息收口，不以opt-in跳过冒充真实检索 |

## 变更与成本

生产检索代码：未修改。生产选择/摘录算法：未修改。新增子来源身份投影校验：有。新增外部模型依赖、索引重建、历史数据修改、迁移、正式部署：均无。

固定8的现有缺口仍为4项选择缺失，加Q02候选缺失共5项；QA现有缺口仍为Q02一项。获准的生产改动不改变这些基线，也未丢失既有重要事实。引用校验只在有移交引用的最终收口增加一次数据库查询，不增加来源名额、模型调用或工具quote预算。真实token为不可得；脚本usage不被记成真实用量。

后续必须处理Q02安全竞争、Agent覆盖与互补事实取舍、稳定文本引用协议与真实Chat语义验收。不能直接提高默认limit至12，不能把本轮局部摘录实验升级为完整生成质量通过。main保持原SHA，PR #18保持OPEN/Draft/未合并。
