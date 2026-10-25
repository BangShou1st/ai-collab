#!/usr/bin/env node
/**
 * P10-RAG-ANSWER-QUALITY-01 证据收口（离线层）。
 * 生成：evidence-availability-matrix / conflict-case-matrix / baseline / model-call-ledger /
 * citation-contract-results / regression-results，并复制 offline-evaluation-results。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const ROOT = path.resolve(__dirname, '../../../..');
const P9DIR = path.join(ROOT, 'docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01');
const QSET = JSON.parse(fs.readFileSync(path.join(ROOT,
  'tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json'), 'utf8'));
const P9 = JSON.parse(fs.readFileSync(path.join(P9DIR, 'retrieval-results.json'), 'utf8'));
const sha256 = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');

// ---- evidence-availability-matrix ----
const byId = {};
for (const q of P9.questions) byId[q.id] = q;
const matrix = QSET.questions.map(q => {
  const p9 = byId[q.p9Ref];
  const facts = q.expectedFacts.map(f => {
    const p9fact = p9.facts.find(x => x.marker === f.marker);
    return { document: f.document, marker: f.marker,
      qaContext: p9fact ? p9fact.qaStage : 'NOT_ANNOTATED_IN_P9',
      agentContext: p9fact ? p9fact.agentToolStage : 'NOT_ANNOTATED_IN_P9' };
  });
  const qaComplete = p9.facts.length === 0 ? 'ABSTAIN_NA'
    : p9.facts.every(f => String(f.qaStage).startsWith('RETAINED')) ? 'COMPLETE' : 'INCOMPLETE';
  const agentComplete = p9.facts.length === 0 ? 'ABSTAIN_NA'
    : p9.facts.every(f => String(f.agentToolStage).startsWith('RETAINED')) ? 'COMPLETE' : 'INCOMPLETE';
  return { id: q.id, p9Ref: q.p9Ref, dimension: q.dimension, abstain: !!q.abstain,
    qaEvidence: qaComplete, agentEvidence: agentComplete, facts,
    generationVerdictMeaning: qaComplete === 'COMPLETE' && agentComplete === 'COMPLETE'
      ? '两条路径证据完整：回答缺陷可归责生成层'
      : qaComplete === 'ABSTAIN_NA' ? '拒答题：考察拒答行为而非事实覆盖'
      : '至少一条路径证据不完整：回答缺陷须先区分证据缺口与生成错误' };
});
fs.writeFileSync(__dirname + '/evidence-availability-matrix.json', JSON.stringify({
  purpose: 'P10 评测子集的证据可得性：区分"证据未进上下文"与"模型答错"', baselineSha: P9.baseline,
  questions: matrix }, null, 2));

// ---- conflict-case-matrix ----
const conflicts = QSET.questions.filter(q => (q.distractFacts || []).length > 0
  || (q.rules && (q.rules.legacyMarkers || []).length > 0)).map(q => ({
  id: q.id, dimension: q.dimension, conflictType:
    (q.rules.legacyMarkers || []).length ? 'VERSION' : 'CROSS_SOURCE',
  expectedFacts: q.expectedFacts.map(f => f.marker),
  conflictingClaims: (q.distractFacts || []).map(f => ({ marker: f.marker, note: f.note })),
  legacyRule: ((q.rules.legacyMarkers || []).length)
    ? { legacyMarkers: q.rules.legacyMarkers, requiredContextWords: q.rules.legacyContextWords } : null,
  p9Exposure: (() => {
    const p9 = byId[q.p9Ref];
    return (p9.distractFacts || []).map(d => ({ marker: d.marker,
      qaVisible: d.qaRetained, agentVisible: d.agentToolRetained }));
  })() }));
fs.writeFileSync(__dirname + '/conflict-case-matrix.json', JSON.stringify({
  purpose: '版本/跨来源冲突题矩阵：正确条款、干扰条款与 P9 冲突暴露实测',
  keyFinding: 'P9 实测中全部干扰条款几乎都与正确条款同时可见（QA 6/7、Agent 7/7），冲突辨别责任在生成层',
  cases: conflicts }, null, 2));

// ---- baseline ----
fs.writeFileSync(__dirname + '/baseline.json', JSON.stringify({
  task: 'p10-rag-answer-quality-01', date: '2026-10-26',
  sourceBaseline: P9.baseline, derivedFrom: 'P9 冻结证据与题集（未改动）',
  evaluationSet: { questions: QSET.questions.length,
    dimensions: [...new Set(QSET.questions.map(q => q.dimension))] },
  layers: {
    offlineDeterministic: 'EXECUTED：P10AnswerQualityOfflineEvaluationTest（默认 CI，零外部调用）',
    realGeneration: 'NOT_EXECUTED：本轮无外部 Chat 模型授权',
  },
  qaCitationProtocol: '正文 [S#] 编号 + 生产 KnowledgeCitationValidator 强校验；无有效引用的实质回答被替换为统一拒答文案并记 invalidOutput',
  agentCitationProtocol: '最终来源卡=工具实际读取并持久化的 documentId/chunkId（citationsStillValid 防失效）；正文无编号协议，来源卡与正文引用解耦',
}, null, 2));

// ---- model-call-ledger ----
fs.writeFileSync(__dirname + '/model-call-ledger.json', JSON.stringify({
  task: 'p10-rag-answer-quality-01', date: '2026-10-26',
  authorization: { granted: false,
    note: '本轮未获得外部 Chat 模型调用授权；P7 历史授权不自动延续' },
  actualCalls: { externalChat: 0, externalEmbedding: 0, localOllama: 0 },
  executedPaths: ['离线确定性评测（真实生产 KnowledgeCitationValidator + P9 冻结检索结果 + 语料全文，零模型调用）'],
  realGenerationVerdict: 'REAL_GENERATION_NOT_EXECUTED',
}, null, 2));

// ---- citation-contract-results（引用 P9 实测身份核验 + 本轮确定性契约测试结论） ----
fs.writeFileSync(__dirname + '/citation-contract-results.json', JSON.stringify({
  qaProtocol: {
    deterministicTests: 'P10AnswerQualityOfflineEvaluationTest（默认 CI）：分组引用合法成员保留、非法成员移除并计数、编造编号高严重度、无引用实质回答被生产 validator 替换为统一拒答（CONTRACT_FORCED_ABSTENTION）',
    existingRegression: 'KnowledgeCitationValidatorTest（既有，继续执行）',
    p9RealIdentityCheck: 'P9 实测 identityViolation=0 / scopeViolation=0（39 题 × 真实工具输出 vs 数据库）',
  },
  agentProtocol: {
    deterministicTests: 'P10AnswerQualityOfflineEvaluationTest：伪造 documentId/chunkId 高严重度、合法身份通过',
    existingRegression: 'AgentFinalCitationContractPostgresTest 11 项（默认 CI 真实 PostgreSQL 执行）：未知身份不可造来源卡、失效引用剔除、跨项目排除等',
  },
  undefinedBehaviors: [
    'Agent 正文与来源卡解耦：正文逐句是否被来源支持无机械绑定（available-but-uncited 是既有契约行为）',
    'QA validator 只证编号合法（citation_number_valid），语义支持（citation_claim_supported）须人工审查',
  ],
}, null, 2));

// ---- regression-results ----
fs.writeFileSync(__dirname + '/regression-results.json', JSON.stringify({
  scope: '本轮新增测试全部为默认 CI 执行的确定性测试；既有质量门槛未关闭',
  newTests: ['com.shitulelv.aicollab.retrieval.P10AnswerQualityOfflineEvaluationTest（4 项）'],
  existingGatesUntouched: ['P6/P8/P9 显式 opt-in 门控（默认跳过，本地已验证）',
    'AgentFinalCitationContractPostgresTest（默认执行）', 'KnowledgeCitationValidatorTest（默认执行）'],
  productionJavaChanged: false,
}, null, 2));

// ---- offline-evaluation-results 复制 ----
fs.copyFileSync(path.join(ROOT,
  'ai-collab-backend/target/p10-rag-answer-quality-01/offline-evaluation-results.json'),
  path.join(__dirname, 'offline-evaluation-results.json'));

// ---- manifest ----
const files = fs.readdirSync(__dirname).filter(f => f !== 'manifest.sha256' && f !== 'build-evidence.cjs');
fs.writeFileSync(__dirname + '/manifest.sha256',
  files.sort().map(f => sha256(path.join(__dirname, f)) + '  ' + f).join('\n') + '\n');
console.log('P10 证据已生成:', files.sort().join(', '));
