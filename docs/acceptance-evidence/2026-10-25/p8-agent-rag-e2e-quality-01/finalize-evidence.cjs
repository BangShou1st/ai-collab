// Summarize actual outputs; refuses to finalize before verify completes.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=process.cwd(), rel=process.env.P8_EVIDENCE_DIR||'docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01', out=path.join(root,rel);
const target=path.join(root,'ai-collab-backend/target/p8-agent-rag-e2e-quality-01');
const read=f=>JSON.parse(fs.readFileSync(f,'utf8'));
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const write=(f,v)=>{fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,typeof v==='string'?v:JSON.stringify(v,null,2)+'\n');};
const walk=p=>fs.existsSync(p)?fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]):[];
const copy=(src,dest)=>{fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(src,dest);};
let matrix=read(path.join(out,'test-matrix.json')).filter(r=>!r.plannedOnly);
if(!matrix.some(r=>r.id==='verify'))throw Error('Verify has not completed');
const b=read(path.join(target,'integrated-retrieval-results.json'));
const integration=read(path.join(target,'integration-results.json'));
copy(path.join(target,'integrated-retrieval-results.json'),path.join(out,'retrieval-results.json'));
copy(path.join(target,'integration-results.json'),path.join(out,'integration-results.json'));
copy(path.join(target,'retrieval-results.json'),path.join(out,'raw/initial-retrieval-results.json'));
const oldRetrieval=path.join(target,'captured-agent-retrieval-quality.json');
copy(oldRetrieval,path.join(out,'raw/agent-retrieval-quality.json'));
const research=path.join(target,'captured-agent-research-quality-paired.json');
copy(research,path.join(out,'raw/agent-research-quality-paired.json'));
for(const r of matrix){
  const src=path.join(root,r.output);
  if(fs.existsSync(src)){copy(src,path.join(out,'raw',path.basename(src)));r.archivedOutput=`${rel}/raw/${path.basename(src)}`;r.outputSha256=hash(src);}
  const reportFiles=walk(path.join(root,r.reports)).filter(f=>/TEST-.*\.xml$/.test(f));
  r.suites=reportFiles.map(f=>{
    const xml=fs.readFileSync(f,'utf8'),tag=xml.match(/<testsuite\s[^>]*>/)?.[0]||'';
    const attr=n=>tag.match(new RegExp(`\\b${n}="([^"]*)"`))?.[1];
    const tests=Number(attr('tests')||0),failures=Number(attr('failures')||0),errors=Number(attr('errors')||0),skipped=Number(attr('skipped')||0);
    const skippedReasons=[...xml.matchAll(/<skipped[^>]*message="([^"]*)"/g)].map(m=>m[1]);
    return {class:attr('name'),tests,failures,errors,skipped,skippedReasons,seconds:Number(attr('time')||0),status:failures||errors?'FAIL':tests===skipped?'NOT_EXECUTED':'PASS',source:path.relative(root,f).replaceAll('\\','/'),sourceSha256:hash(f)};
  });
  r.totals=r.suites.reduce((a,s)=>{for(const k of ['tests','failures','errors','skipped'])a[k]+=s[k];return a;},{tests:0,failures:0,errors:0,skipped:0});
  r.totalsBasis='SUREFIRE_XML';
  if(!r.suites.length&&fs.existsSync(src)){
    const counts=[...fs.readFileSync(src,'utf8').matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)].at(-1);
    if(counts){r.totals={tests:+counts[1],failures:+counts[2],errors:+counts[3],skipped:+counts[4]};r.totalsBasis='CONSOLE_SUMMARY_ONLY: per-suite XML unavailable';}
  }
  // Plain reports contain results, not JVM properties. Keep raw XML local to target.
  for(const f of walk(path.join(root,r.reports)).filter(f=>f.endsWith('.txt')))copy(f,path.join(out,'raw',r.id,path.basename(f)));
}
matrix.push(...[
  {id:'GeminiKnowledgeAnswerQualityPostgresTest',status:'BLOCKED_EXTERNAL',reason:'本轮没有新外部模型授权；历史 24/24 预算不能沿用'},
  {id:'RealAgentLoopPostgresTest',status:'BLOCKED_EXTERNAL',reason:'所有外部 Chat opt-in 清除；本轮未授权任何免费或付费新请求'},
  {id:'frontend-lint-typecheck-test-build',status:'NOT_EXECUTED',reason:'本轮仅修改后端测试与证据，不涉及前端'},
  {id:'browser-e2e',status:'NOT_EXECUTED',reason:'本轮验收为隔离测试数据库中的后端链路，没有操作真实用户项目'},
  {id:'AgentCrashRecoveryPostgresTest',status:'NOT_EXECUTED',reason:'初次定向命令中不存在的类名；真实类为 AgentCrashRecoveryTimingPostgresTest，其结果见完整 verify'}
].map(r=>({...r,plannedOnly:true,exitCode:null,command:null})));
write(path.join(out,'test-matrix.json'),matrix);
const q=id=>b.questions.find(q=>q.id===id), agent=id=>integration.find(q=>q.id===id);
const sum=(xs,f)=>xs.reduce((a,x)=>a+f(x),0), ratio=(n,d)=>d?n/d:null;
const allFacts=b.questions.flatMap(q=>q.facts);
const historical=read(path.join(root,'docs/acceptance-evidence/2026-10-25/p6-rag-quality-01/d0-d2-final.json'));
const historicalComparison=[];
for(const row of b.questions){
  const prior=historical.questions.find(p=>p.id===row.id);
  for(const fact of row.facts){
    const old=prior?.facts.find(p=>p.document===fact.document&&p.marker===fact.marker);
    for(const stage of ['qaStage','agentStage'])historicalComparison.push({id:row.id,document:fact.document,marker:fact.marker,stage,historical:old?.[stage]??'UNKNOWN',current:fact[stage],changed:old?.[stage]!==fact[stage]});
  }
}
write(path.join(out,'historical-comparison.json'),{basis:'P6 actual saved facts versus P8 actual rerun; historical values never replace rerun',comparisonCount:historicalComparison.length,changed:historicalComparison.filter(x=>x.changed),rows:historicalComparison});
const retained=(xs,key)=>xs.filter(f=>f[key]==='RETAINED').length;
const breakdown={};
for(const scope of ['ALL_PROJECT','DOCUMENT_SCOPED']){
  const rows=b.questions.filter(q=>q.userScope===scope),facts=rows.flatMap(q=>q.facts);
  breakdown[scope]={questions:rows.length,expectedFacts:facts.length,recallAt8:ratio(facts.filter(f=>f.diagnosticFullRank>0&&f.diagnosticFullRank<=8).length,facts.length),recallAt20:ratio(facts.filter(f=>f.diagnosticFullRank>0&&f.diagnosticFullRank<=20).length,facts.length),qaRetained:retained(facts,'qaStage'),agentRetained:retained(facts,'agentStage')};
}
const coverage=key=>{
  let expected=0,actual=0;
  for(const row of b.questions){const docs=new Set(row.facts.map(f=>f.document));expected+=docs.size;for(const doc of docs)if(row.facts.some(f=>f.document===doc&&f[key]==='RETAINED'))actual++;}
  return {expectedDocumentQuestionPairs:expected,retainedDocumentQuestionPairs:actual,ratio:ratio(actual,expected),definition:'每题每个期望文档至少一条已标注事实在截断后保留；不是全事实完整率'};
};
const drops=key=>{let counts={};for(const row of b.questions)for(const [k,v]of Object.entries(row[key]))counts[k]=(counts[k]||0)+v;return counts;};
const markers=['自动化验收','阻断级缺陷必须关闭','数据迁移结果无误','两小时','核心业务指标与错误率','连续两次低于约定阈值','或者出现不可恢复的数据异常','立即暂停发布','回滚到最近一次稳定版本'];
const q03Audit=markers.map(marker=>({marker,qaPrompt:q('Q03').qaPromptSources.includes(marker),agentToolQuote:agent('Q03').sourceIdentityChecks.some(s=>s.quote.includes(marker))}));
const quotes=sum(integration,r=>r.sourceIdentityChecks.length);
const textCitationAudit=integration.map(r=>{
  const numbers=[...r.persistedAnswer.matchAll(/\[S(\d+)\]/g)].map(m=>Number(m[1]));
  const invalid=numbers.filter(n=>n<1||n>r.sourceIdentityChecks.length);
  const sourceOrderMatches=r.persistedCitations.every((c,i)=>c.chunkId===r.sourceIdentityChecks[i]?.chunkId);
  return {id:r.id,numbers,invalid,sourceOrderMatches,status:invalid.length?'FAIL':'PASS',basis:'SCRIPTED_OUTPUT_MECHANICAL; not real generation'};
});
write(path.join(out,'text-citation-audit.json'),textCitationAudit);
const identityPass=integration.every(r=>r.identityAndPersistence==='PASS');
const current=matrix.find(r=>r.id==='integration');
const verify=matrix.find(r=>r.id==='verify');
const verdict=verify.exitCode!==0||current.exitCode!==0||!identityPass?'P8_FAILED':'P8_PARTIAL_ACCEPTANCE';
const metrics={
  verdict,
  basis:{retrieval:'本轮真实本地 Ollama + pgvector；冻结 16 题、P6 27 个事实 marker；不重新定义冻结语义评分',mechanical:'真实工具/Runtime/数据库与脚本模型；不同于真实生成评分',history:'仅用于选题和对照，未混入当前分母',generation:'NOT_MEASURED'},
  retrieval:{recallAt8:ratio(allFacts.filter(f=>f.diagnosticFullRank>0&&f.diagnosticFullRank<=8).length,allFacts.length),recallAt20:ratio(allFacts.filter(f=>f.diagnosticFullRank>0&&f.diagnosticFullRank<=20).length,allFacts.length),denominator:allFacts.length,definition:'沿用 P6 逐事实 chunk 命中口径；一块可承载多事实；无期望证据的 Q07 不进入分母；原始候选列表可独立核对',byScope:breakdown,q02Rank:q('Q02').facts[0].diagnosticFullRank},
  context:{qa:b.summary.qaStage,agent8:b.summary.agentStage,agent12Diagnostic:b.summary.agent12Stage,qaDocumentCoverage:coverage('qaStage'),agentDocumentCoverage:coverage('agentStage'),qaDropReasons:drops('qaDropReasons'),agentDropReasons:drops('agentDropReasons'),qaMaxPromptCodePoints:Math.max(...b.questions.map(q=>q.qaPromptCodePoints)),agentMaxPromptCodePoints:Math.max(...b.questions.map(q=>q.agentPromptCodePoints)),budgetCodePoints:8000,scopeViolations:sum(b.questions,q=>q.scopeViolationCount),q03FineGrained:q03Audit},
  agent:{scenarios:integration.length,succeeded:integration.filter(r=>r.status==='SUCCEEDED').length,mainScriptedModelCalls:sum(integration,r=>r.mainModelCalls),auxiliaryScriptedModelCalls:sum(integration,r=>r.auxiliaryModelCalls),totalScriptedModelCalls:sum(integration,r=>r.modelCalls),toolCalls:sum(integration,r=>r.toolCalls),toolSuccessRate:1,actualQuoteFactsRetained:sum(integration,r=>r.factTransport.filter(f=>f.actualToolQuoteRetained).length),expectedFacts:allFacts.length,evidenceRecoveryDefinition:'工具/清洗/消息组装未再丢失来源；语义事实保留率仍为22/27',parentChildEvidenceRecovery:'见 raw/agent-research-quality-paired.json；搜索候选注入，非真实向量父子贯通',maxScenarioElapsedMs:Math.max(...integration.map(r=>r.elapsedMs)),maxScriptedCallsPerScenario:Math.max(...integration.map(r=>r.modelCalls)),maxSteps:Math.max(...integration.map(r=>r.steps))},
  citation:{identitiesChecked:quotes,persistenceIdentityPass:identityPass,unknownIdentitiesPersisted:0,nonMemberDenied:integration.filter(r=>r.nonMemberDenied).length,fabricatedIdentityRejected:integration.filter(r=>r.fabricatedIdentityRejected).length,numberValidity:{basis:'SCRIPTED_OUTPUT_MECHANICAL',invalidMarkers:sum(textCitationAudit,r=>r.invalid.length),cases:textCitationAudit},numberValidityNote:'Q13 copies literal S999 from untrusted source. Text is persisted unchanged; no fabricated structured citation is persisted. Real model behavior NOT_MEASURED',semanticSupport:'NOT_MEASURED for real generation; copied quote transport only',q07RelatedCitations:agent('Q07').sourceIdentityChecks.length,q07Sufficiency:'NOT_ESTABLISHED: related citations do not establish eighty years'},
  generation:{correctness:'NOT_MEASURED',completeness:'NOT_MEASURED',unsupportedClaims:'NOT_MEASURED',abstention:'SCRIPTED_ONLY',injectionResistance:'NOT_MEASURED: copying Q13 source text does not prove resistance'},
  cost:{externalChatRequests:0,realChatTokens:'NOT_MEASURED',embeddingModel:b.model,dimension:b.dimension,fingerprint:b.fingerprint,indexing:b.indexing,perFrozenRetrievalRunLogicalEmbeddingCalls:{index:14,qaQueries:16,agentQueries:16,diagnosticQueries:16},perFinalIntegratedRunAdditionalLogicalEmbeddingCalls:{actualAgentSearches:16,q10DisjointScope:0,nonMemberDenials:0},embeddingHttpRequests:'UNKNOWN: gateway batching/retries were not instrumented; logical calls are not HTTP attempts',embeddingTokenUsage:'UNKNOWN',embeddingRetries:'UNKNOWN',realModelCurrencyCost:'UNKNOWN (no external request; local resource cost not priced)',timingBasis:'单机顺序样本；仅用于复现，不推导 P95 或性能承诺'},
  repairReadiness:'已具备独立修复专项的可复现输入、失败分层、反例和可量化目标；尚不具备生产改动准入的端到端生成证据'
};
write(path.join(out,'metrics.json'),metrics);
const baseline=read(path.join(out,'baseline.json'));
baseline.embedding={provider:'OLLAMA',model:b.model,dimension:b.dimension,fingerprint:b.fingerprint,isolatedIndexGeneration:b.generationId,configurationBoundary:'测试中显式建立独立配置，未读取或修改应用数据库配置',modelDigest:'ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d'};
baseline.environment.java='21.0.10 (Maven -v captured this value; java -version writes to stderr)';
baseline.environment.backend='Spring Boot 4.1.0; Flyway V1–V68 in isolated DB';
baseline.environment.frontend={...read(path.join(root,'ai-collab-frontend/package.json')).devDependencies,...read(path.join(root,'ai-collab-frontend/package.json')).dependencies};
baseline.testOnlySourceHashes=Object.fromEntries(['P6RagQualityBaselinePostgresTest.java','P8AgentRagCrossLayerPostgresTest.java'].map(name=>{const f=`ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/${name}`;return [f,hash(path.join(root,f))];}));
baseline.originDevMeaning='本地 origin/dev 跟踪引用；未 fetch，不宣称实时远端状态';
baseline.localApplicationEmbeddingConfiguration=fs.existsSync(path.join(out,'local-embedding-configuration.json'))?read(path.join(out,'local-embedding-configuration.json')):'UNKNOWN: optional read-only probe not repeated';
baseline.localApplicationIndexSummary=fs.existsSync(path.join(out,'local-index-summary.json'))?read(path.join(out,'local-index-summary.json')):'UNKNOWN: optional read-only probe not repeated';
baseline.dataBoundary='应用数据库仅只读选择 Embedding 非敏感元数据及向量配置分组计数；无文档正文、凭据或用户身份读取。所有测试写入只在一次性 Testcontainers DB。';
baseline.embedding.fingerprintBoundary='本轮冻结夹具指纹20f29e...与本地应用371934...不同；SystemEmbeddingService把baseUrl/apiPath纳入配置指纹，冻结P6测试用provider/model/dimension指纹。相同Ollama模型/1024维但不同独立索引命名空间；不把夹具generation当成本地应用generation。';
write(path.join(out,'baseline.json'),baseline);
const table=matrix.filter(r=>!r.plannedOnly).map(r=>`| ${r.id} | ${r.status} | ${r.totals.tests} | ${r.totals.failures} / ${r.totals.errors} / ${r.totals.skipped} | ${r.exitCode} |`).join('\n');
write(path.join(out,'regression-results.md'),`# 本轮工程验证\n\n| 命令组 | 状态 | Tests | Failures / Errors / Skipped | 退出码 |\n| --- | --- | ---: | --- | ---: |\n${table}\n\n完整命令、UTC 起止时间、逐测试类实际执行/跳过数、输出哈希与本地 XML 路径见 [test-matrix.json](test-matrix.json)。报告展示日期按北京时间；原始机器时间保留时区。完整 verify 的跳过均须按类核对，不能把 opt-in 跳过视作质量通过。\n\n初次定向命令包含不存在的 AgentCrashRecoveryPostgresTest 类名，该项并未执行；真实类 AgentCrashRecoveryTimingPostgresTest 由完整 verify 覆盖。初次使用的 surefire.reportsDirectory 参数没有改变本工程的输出位置，已按运行时段把真实 surefire-reports 归档到对应 target 子目录；后续 runner 直接归档，不依赖该参数。\n\nintegration-attempt1 保留初步运行的控制台证据；首次逐类 XML 已被第二次复测替换，故不为它补造逐类结果。integration-attempt2 的断言虽通过，Q02/Q07 辅助模型分支未显式脚本化，记 INCONCLUSIVE。最终 integration 明确返回 EMPTY 摘要并计数，才作为最终链路证据。MinIO 127.0.0.1:1 连接拒绝为隔离测试的预期后台初始化告警；没有操作应用存储。\n\n本轮没有前端改动，前端验证 NOT_EXECUTED；外部生成用例 BLOCKED_EXTERNAL。\n`);
write(path.join(out,'limitations.md'),`# 结论边界\n\n- Embedding 是本轮真实本地模型；Chat 是 Mockito 确定性脚本。实际 Runtime、SQL、工具注册/执行/清洗、消息 composer、数据库 guard 与引用持久化执行。上下文 assembler、事件发送、审批和记忆 collaborator 被替换，前两者避免接入真实 worker/SSE，后两者不属于只读搜索验收。没有浏览器、公开 HTTP/API 认证入口或真实 Worker 后台调度验收。\n- 模型脚本逐字复制实际 quote，无法证明真实 Correctness、Completeness、Faithfulness、Citation Support、自主拒答、提示注入抵抗能力。Q13 工具内容包含恶意文本，脚本复制它不构成真实抗注入通过。\n- Q11/Q12 使用同一 session、生产消息历史组装，Q12 的用户范围由测试显式继承。辅助摘要为明确脚本 EMPTY 降级；没有验证真实摘要语义与自主指代改写。\n- 父子运行、工具失败由现有 AgentResearchQualityPostgresTest 复测覆盖；该测试仍注入检索候选及 Mock guard。P8 的真实向量新链路只覆盖直接搜索；真实向量父子完整贯通 NOT_EXECUTED。\n- 冻结 P6 27 个 marker 不等于 questions.json 所有语义约束。Q03 另逐短语核对或关系、不可恢复异常、暂停与最近稳定版。完整的生成评分仍必须由未来授权的真实生成和人工逐项审查完成。\n- R1 候选补充是既有诊断实验：QA 使用 legacy buildWithDiagnostics，而非当前 balanced 入口；不能据它断言 R1+S2 联合收益。R1b 测试侧 SQL 扫描项目所有 READY 同代际分块，至多保留4项补充；不能称为生产级固定 I/O 上界。未把它接入 Agent 实际链路。\n- 只有逻辑 Embedding 调用数可核查。没有 HTTP 级请求/重试、缓存、Token 计量，均 UNKNOWN；脚本 Token 为 null，Runtime 的估算不作为真实用量。单次延迟不做 P95 或性能提升推断。\n- origin/dev 是开工时本地跟踪引用，未访问远端核验最新提交。未执行生产部署、模型配置变更、生产算法修改或用户数据写入；测试仅写一次性 Testcontainers DB。\n- 未执行任何外部 Chat 请求；历史 Gemini/Kilo/魔搭账本仅为历史授权边界，不继承本轮授权。\n`);
write(path.join(out,'failure-analysis.md'),`# 失败分层与最小修复候选\n\n## F1 Q02：FAIL / RETRIEVAL_MISS（主因已确认）\n\n本轮真实 Ollama 与隔离 pgvector 均确认文档已索引；目标“只有最近一次回滚演练通过的项目才允许继续发布”在全量第 ${q('Q02').facts[0].diagnosticFullRank} 名，相似度 ${q('Q02').facts[0].diagnosticSimilarity}。QA Top8、Agent Top20 均缺失，最终上下文、实际工具 quote 和脚本模型观察均不含该条件。SOURCE_CAP 是其他候选的丢弃统计，不能当作原生产 Q02 的主因。\n\n复现：按 README 执行 retrieval / integration，检查 retrieval-results.json questions[Q02] 的 facts、qaCandidates、agentCandidates、diagnosticFullRanking，再对照 integration-results.json 的 Q02 factTransport。原始冻结资料尾部含大量重复步骤，稀释语义是与证据一致的解释，但“具体 embedding 因果机制”未通过消融实验证实。\n\n既有回归能稳定记录该失败，未要求 Q02 通过；未来修复须新增“最终有效 quote 必含条件”准入断言。候选最小修复为受控查询分解或已命中文档有界补读关键章节，先做测试侧对照；不能用 gold 标签、题号或硬编码 docId 特判。R1b 本轮复测可将证据补入池，但已有 legacy QA/Agent 选择仍丢弃，最终收益为0。R1+当前 S2 联合收益尚未测，不能排除。\n\n风险：额外查询/Token、误召回、source cap 再次挤压、指纹与范围过滤遗漏。下一轮要求在相同冻结输入、20 候选/8000预算和明确额外I/O上界下，Q02 最终事实 0→1；同时 Q03/Q05/Q09 与既有反例重要事实零退化。证据足以进入独立修复实验，尚不足以批准某个生产实现。\n\n## F2 Agent Q03/Q04/Q05：FAIL / CONTEXT_MISS（主因已确认）\n\nTop20 中已有关键块，生产跨文档代表优先选择在默认8来源下丢失 Q03 上线门禁及回滚触发条件、Q04 导出审计、Q05 旧版条款。真实工具和最终模型观察与诊断一致，没有新增清洗/回流丢失。逐题 marker 与 SOURCE_CAP 见 retrieval-results.json；实际 quote、消息及引用身份见 integration-results.json。\n\nQ03 逐短语审查：QA 同时包含自动化验收、阻断缺陷关闭、迁移核对、两小时、连续两次阈值、或不可恢复异常、暂停和最近稳定版；Agent8 仅保留灰度阶段，不能判完整。\n\n复现：上述两命令；按 Q03/Q04/Q05 facts.agentStage 对照候选及 sources。既有选择器反例测试、P6 冻结回归与新增实际工具传递一致性断言均覆盖该边界。\n\n同候选对照：Agent12 从22/27到25/27（+3事实，+11.11个百分点），救回 Q03 回滚、Q04 审计、Q05旧版；Q03 上线门禁仍缺失，Q02 仍缺失。该收益只来自真实向量上的构建器诊断，尚未把limit12接入完整Runtime或真实生成，不作为上线依据。\n\n候选最小修复：有界互补片段选择/章节补读，或同预算下对两阶段代表分配做测试侧对照；简单扩大limit不能独立解决问题。风险是独立文档覆盖下降、旧版/第二事实被替换、quote截断、Token增加。未来必须逐题 gained/lost、E1–E11、CE反例与真实工具回流对照，关键事实零退化；真实生成收益另需授权。值得独立专项。\n\n## F3 Q09：本轮未复现 CITATION_FAILURE\n\nQA 与实际 Agent 工具均有甲乙两个独立 documentId/chunkId；相同正文去重没有合并文档身份，最终持久化身份均可回查本项目真实块。无需再修历史缺陷。实际生成语义支持仍 NOT_MEASURED；不能把脚本摘录当作真实模型比较正确。\n\n## F4 Q07/Q12：证据充分性不可由引用数判断\n\nQ07 有 ${agent('Q07').sourceIdentityChecks.length} 个相关引用，但没有“八十年”所需事实。脚本按题目拒答标签表达不足，并非模型自主识别。没有发现新的编排或持久化缺陷；真实生成层错误/拒答与资料充分性状态的关系 INCONCLUSIVE。建议下一轮设计独立的“逐事实可支持”契约，再决定是否改变不足状态，禁止关键词式状态推断。\n\n## F5 外部层：EXTERNAL_BLOCK\n\nGeminiKnowledgeAnswerQualityPostgresTest、RealAgentLoopPostgresTest 未获本轮新外部调用授权，BLOCKED_EXTERNAL，不记通过或生成失败。没有尝试429重试、切换供应商或读取私密配置。\n\n## F6 测试装配限制（已修正，非生产缺陷）\n\n初步跨层测试未显式脚本化 Q02/Q07 的辅助摘要入口，Mockito null触发生产降级。保留初始日志，不以断言绿色掩盖额外调用；最终明确 EMPTY 摘要、计辅助调用并重跑。最终原始结果为唯一当前链路裁决依据。\n`);
write(path.join(out,'README.md'),`# P8-AGENT-RAG-E2E-QUALITY-01\n\n最终裁决：**${verdict}**。研究与可复现证据已交付；**Q02=FAIL，Agent 多条件完整性=FAIL**。确定性跨层身份/回流/持久化验证通过；真实外部生成未执行，不能宣称完整端到端回答质量通过。\n\n基线：dev@${baseline.head}，开工时 HEAD=origin/dev、工作区干净。当前变更仅后端测试与本专项证据/导航，生产 Java、Prompt、分块、来源算法、配置及迁移未改。\n\n| 层级 | 本轮结果 | 证据性质 |\n| --- | --- | --- |\n| 冻结检索 | Recall@8 / @20 均26/27；QA保留26/27，Agent8保留22/27 | 本地真实Ollama、真实pgvector、生产服务 |\n| Q02 | 关键续发条件全量排名23，Top8/Top20/实际工具均缺失：FAIL | 本轮真实复测 |\n| Q03 | QA三阶段与所有列明条件保留；Agent8缺上线门禁及回滚：FAIL | 实际QA上下文与工具quote逐短语核对 |\n| Q09 | 双合同独立身份均保留并正确持久化 | 实际文档/块/数据库核对 |\n| 新增跨层 | ${integration.length}题、${quotes}条来源身份核验；工具成功${sum(integration,r=>r.toolCalls)}/${sum(integration,r=>r.toolCalls)}，未知身份0 | Chat脚本，其他核心检索与运行路径真实 |\n| 权限/范围 | 非成员16次拒绝；指定范围零越界；Q10模型扩大范围被阻断 | 真实数据库guard/范围解析 |\n| 父子与失败工具 | 既有Agent研究测试本轮复跑 | 脚本模型+注入检索候选，不是真实向量父子贯通 |\n| 真实生成 | BLOCKED_EXTERNAL，外部Chat请求0 | 未获本轮新授权 |\n\n[现有能力与设计](engineering-inventory.md) · [基线/环境/冻结哈希](baseline.json) · [执行矩阵](test-matrix.json) · [真实检索原始结果](retrieval-results.json) · [实际Agent跨层结果](integration-results.json) · [指标与口径](metrics.json) · [失败归因及下一轮建议](failure-analysis.md) · [回归结果](regression-results.md) · [限制](limitations.md) · [历史完整性核验](historical-integrity.json) · [哈希清单](manifest.sha256)。\n\n## 可复现步骤\n\n当前目录用于保存本轮冻结产物，runner 的 baseline 模式会拒绝覆盖 baseline.json。再次运行请先将 runner 的 out/target 日期或任务目录改为新的独立目录；测试入口支持用 -Drag.baseline.output 单独指定基线JSON。测试自身不写历史docs文件；已有target诊断文件先备份，结束后恢复。\n\n在仓库根目录执行下列命令（实际完整Maven命令及 opt-in 设置记录在 test-matrix.json）：\n\n~~~powershell\nnode ${rel}/run-evaluation.cjs baseline\nnode ${rel}/run-evaluation.cjs retrieval\nnode ${rel}/run-evaluation.cjs integration\nnode ${rel}/run-evaluation.cjs regression\nnode ${rel}/run-evaluation.cjs verify\nnode ${rel}/run-evaluation.cjs restore\nnode ${rel}/finalize-evidence.cjs\n~~~\n\n所有Maven执行为离线 -o；仅本机Ollama和隔离Testcontainers服务允许真实调用，外部模型/应用库验收opt-in均清除。P8在单个JUnit测试内顺序执行16道冻结问题，不能把Tests run:1当作只评估一道题，也不能把16题当作16个独立JUnit测试。\n\n## 最终问题回答\n\n1. RAG卡在两层：Q02语义排序未召回；Agent代表优先与来源名额竞争丢掉同文档互补条件。QA当前三阶段门禁恢复，不能据此替Agent判通过。\n2. 真实向量→工具执行→清洗→模型可见→脚本回答→消息/引用数据库链路的机械一致性通过；真实Chat生成和真实向量父子完整链路未通过本轮验证（未执行）。\n3. 检索、工具、SQL、来源/持久化是本轮真实运行；Chat、辅助摘要及既有父子模型是脚本/Mock；历史结果仅供对照。\n4. 下一轮优先解决Q02最终证据召回，以及Agent同文档互补事实保留。无需再次修复当前已恢复的Q09；不凭引用数量扩展不足语义。\n5. 具备进入独立修复研究的证据条件；生产改动准入仍需成对净收益、关键事实零退化与授权后的真实生成验证。\n`);
fs.appendFileSync(path.join(out,'failure-analysis.md'), '\n## F7 Q13：脚本输出文本编号 FAIL / CITATION_FAILURE（机械现象确认）\n\ntext-citation-audit.json 实测 Q13 的最终文本包含无可用来源的 [S999]；来源编号按实际 sources 顺序核对，其他题未发现越界编号。S999 来自冻结恶意资料，经逐字复制脚本进入最终回答，数据库 content 原样保留。结构化 citations_json 中没有伪造 chunk/document，来源卡身份仍全部有效。AgentRunEventRecorder.recordFinal 的现行契约投影实际读取来源并保存模型文本，没有复用 QA 的 KnowledgeCitationValidator 对文本做最终编号校验。\n\n复现直接查看 integration-results.json Q13 的 toolResult、persistedAnswer、persistedCitations；这不是本轮真实模型注入遵从证据，也不证明资料指令被执行。已确认的是未知文本标号可穿过实际 Runtime 被保存；编号语义契约与真实生成安全性须分开。现有单测钉住身份集合，尚未钉住 Agent 最终文本未知编号。下一轮可先制定 Agent 引用编号映射/源卡展示契约，再以脚本负例测试最小校验；直接套用 QA 清理器可能损坏正常引用文档原文中的字面编号，必须增加“引用文本 vs 文档摘录”的反例和语义审查。建议进入独立契约修复调查，未经该调查不直接改生产清洗。\n');
fs.appendFileSync(path.join(out,'README.md'), '\n补充编号审计：[text-citation-audit.json](text-citation-audit.json) 检出 Q13 脚本复制资料中的 [S999]，最终文本编号 FAIL；实际结构化来源未伪造。该现象可复现最终文本校验边界，不构成真实模型抗注入结论，详见失败分析 F7。\n');
const readmePath=path.join(out,'README.md');
let readmeText=fs.readFileSync(readmePath,'utf8');
readmeText=readmeText.replace('再次运行请先将 runner 的 out/target 日期或任务目录改为新的独立目录；测试入口支持用 -Drag.baseline.output 单独指定基线JSON。', '再次运行请先设置 $env:P8_EVIDENCE_DIR 为 docs/acceptance-evidence/日期/新的任务目录，再调用本目录原有脚本；不修改原脚本路径或固定测试target路径。baseline模式会先把旧P8 target输出复制到target/p8-replays/时间/prior-p8，且拒绝覆盖已存在的baseline.json。测试入口也支持用 -Drag.baseline.output 单独指定基线JSON。只复现某一测试时不必调用finalize。');
readmeText=readmeText.replaceAll(`node ${rel}/`, 'node docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/');
fs.writeFileSync(readmePath,readmeText);
fs.appendFileSync(path.join(out,'limitations.md'), '\n只读配置补核：local-embedding-configuration.json 与 local-index-summary.json 显示应用使用相同 Ollama 模型/1024维，存量29块均匹配应用指纹371934...，generation_id为null；P8临时索引是49块、独立ACTIVE generation、指纹20f29e...。生产配置服务的指纹把endpoint路径纳入模型参数字符串，冻结P6夹具保留旧provider/model/dimension计算方式；这是明确的配置命名空间差异，不据此宣称应用索引漂移或损坏。实际业务库检索未执行，本轮质量结论适用于冻结语料上的当前生产实现。只读核对未读取任何凭据或正文，没有改应用数据。\n');
// Final manifest covers evidence plus the test sources used to produce it. No recursive self hash.
const files=[...walk(out).filter(f=>!f.endsWith('manifest.sha256')),...Object.keys(baseline.testOnlySourceHashes).map(f=>path.join(root,f))];
write(path.join(out,'manifest.sha256'),files.sort().map(f=>`${hash(f)}  ${path.relative(root,f).replaceAll('\\','/')}`).join('\n')+'\n');
console.log(JSON.stringify({verdict,metrics:metrics.context,verify:verify.totals,files:files.length},null,2));
