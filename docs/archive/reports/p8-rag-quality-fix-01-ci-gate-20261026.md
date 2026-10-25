# P8-RAG-QUALITY-FIX-01 CI门控补充交付

入口：[补充验收](../../acceptance-evidence/2026-10-26/p8-rag-quality-fix-01-ci-gate/README.md)。本报告补充25日研究交付，保留其原始数据与有条件CI裁决。

首个正式SHA d380b21的Push/PR后端失败，故当时不能判最终验收通过。原P8测试声称继承本地Embedding门控，但该JUnit条件未继承，CI意外请求不存在的本地模型；这不是生产引用修复缺陷，也不能靠忽略失败验收。

最小修复为P8跨层测试增加显式opt-in，CI再断言P6基线、原P8跨层、新P8实验三个类在默认环境中全部跳过。引用身份的11项真实PostgreSQL测试继续纳入必须执行名单。显式启用时仍运行原16题真实Ollama跨层链路，证明门控没有删除研究能力；默认完整verify另测禁用路径。

最终本地默认verify为1808项、0失败、0错误、37跳过；三个本地模型类均按预期跳过，新增引用PostgreSQL测试11项实际执行。显式启用的跨层测试1个JUnit入口完整执行16题，无跳过。与先前36项跳过相比，多出的是正确门控的P8真实本地模型用例，不是放宽质量断言。

生产Java自d380b21后没有任何变化；Q02与Agent固定8的算法仍不准入，引用身份修复与成本上界不变。外部Chat/付费Embedding/生产部署均未执行，main不变，PR #18保持Draft未合并。源码哈希是版本绑定证据；原P8源码可在3a32937恢复，引用修复源码在d380b21，门控演进另有新提交。旧证据和语料字节不改写，最终源码哈希及回归详见source-versions/gate-results。

最终结果仍按原专项三个分项裁决：Q02_EXPERIMENT_ONLY、AGENT_EVIDENCE_EXPERIMENT_ONLY、CITATION_CONTRACT_FIXED。总体P8_RAG_QUALITY_FIX_01_ACCEPTED须在本补充默认/启用双路径与最终同SHA Push/PR CI均通过后收口；不是将真实检索或脚本测试提升为真实Chat生成质量通过。
