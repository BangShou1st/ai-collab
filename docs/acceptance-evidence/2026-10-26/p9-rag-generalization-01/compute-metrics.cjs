#!/usr/bin/env node
/**
 * P9-RAG-GENERALIZATION-01 指标后处理。
 * 输入：ai-collab-backend/target/p9-rag-generalization-01/retrieval-results.json（真实测量输出）
 * 输出：metrics.json 与 cost-and-latency.json（写入验收证据目录）。
 * 只读测量输出并聚合，不产生任何新的检索调用。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '../../../..');
const INPUT = path.join(ROOT, 'ai-collab-backend/target/p9-rag-generalization-01/retrieval-results.json');
const OUT = __dirname;
const r = JSON.parse(fs.readFileSync(INPUT, 'utf8'));
const pct = (a, b) => (b === 0 ? null : Math.round((a / b) * 10000) / 10000);

// ---- 指标计算 ----
const facts = r.questions.flatMap(q => q.facts.map(f => ({ ...f, qid: q.id, set: q.set, dim: q.dimension })));
const indexed = facts.filter(f => f.indexed);
const recall = (field) => ({
  indexedFacts: indexed.length,
  hits: indexed.filter(f => f[field] > 0).length,
  ratio: pct(indexed.filter(f => f[field] > 0).length, indexed.length),
});
const stageStats = (field, rows) => {
  const all = rows.flatMap(q => q.facts);
  return {
    retained: all.filter(f => String(f[field]).startsWith('RETAINED')).length,
    truncation: all.filter(f => String(f[field]).startsWith('BODY_TRUNCATION')).length,
    retrievalMiss: all.filter(f => String(f[field]) === 'RETRIEVAL_MISS').length,
    selectionMiss: all.filter(f => String(f[field]).startsWith('SELECTION_MISS')).length,
    indexMiss: all.filter(f => String(f[field]) === 'INDEX_MISS').length,
    total: all.length,
    ratio: pct(all.filter(f => String(f[field]).startsWith('RETAINED')).length, all.length),
  };
};
const completeCoverage = (field, rows) => {
  const answerable = rows.filter(q => q.facts.length > 0);
  const complete = answerable.filter(q => q.facts.every(f => String(f[field]).startsWith('RETAINED')));
  return { questions: complete.length, answerable: answerable.length, ratio: pct(complete.length, answerable.length) };
};
const groupBy = (rows, field) => {
  const map = {};
  for (const row of rows) (map[row[field]] ??= []).push(row);
  return map;
};

const metrics = {
  benchmark: 'p9-rag-generalization-01',
  generatedAt: new Date().toISOString().slice(0, 10),
  baselineSha: r.baseline,
  embeddingModel: r.model,
  corpus: { documents: r.indexing.documentCount, chunks: r.indexing.chunkCount,
    embeddingInputCodePoints: r.indexing.embeddingInputCodePoints },
  productionGeneration: 'REAL_GENERATION_NOT_EXECUTED',
  recall: { at8: recall('top8Rank'), at20: recall('top20Rank') },
  overall: {
    qa: stageStats('qaStage', r.questions),
    qaLegacy: stageStats('qaLegacyStage', r.questions),
    agent: stageStats('agentToolStage', r.questions),
    agentLimit12: stageStats('agent12Stage', r.questions),
    completeCoverageQa: completeCoverage('qaStage', r.questions),
    completeCoverageAgent: completeCoverage('agentToolStage', r.questions),
  },
  bySet: Object.fromEntries(Object.entries(r.setSummary).map(([k, v]) => [k, {
    qa: v.qaStage, agent: v.agentToolStage,
    completeCoverageQa: v['completeCoverage:qaStage'], completeCoverageAgent: v['completeCoverage:agentToolStage'],
  }])),
  byDimension: Object.fromEntries(Object.entries(r.dimensionSummary).map(([k, v]) => [k, {
    qa: v.qaStage, agent: v.agentToolStage,
    completeCoverageAgent: v['completeCoverage:agentToolStage'],
  }])),
  byDocLength: Object.fromEntries(Object.entries(r.lengthSummary).map(([k, v]) => [k, {
    qa: v.qaStage, agent: v.agentToolStage }])),
  byCrossDocCount: Object.fromEntries(Object.entries(r.docCountSummary).map(([k, v]) => [k, {
    qa: v.qaStage, agent: v.agentToolStage }])),
  byFactCount: Object.fromEntries(Object.entries(r.factCountSummary).map(([k, v]) => [k, {
    qa: v.qaStage, agent: v.agentToolStage }])),
  conflictExposure: {
    definition: '已废止/他方干扰条款同时保留为最终可见证据的比例（数值高=识别冲突的负担全部落在生成端）',
    distractFacts: r.questions.flatMap(q => (q.distractFacts || []).map(d => ({
      question: q.id, marker: d.marker, note: d.note, qaRetained: d.qaRetained, agentToolRetained: d.agentToolRetained }))),
  },
  falseEvidenceProxy: {
    definition: '拒答题/干扰题中干扰文档进入最终上下文或工具摘录（覆盖语义为 RELEVANT_EXCERPTS_ONLY，诚实拒答责任在生成端）',
    questions: r.questions.filter(q => (q.falseEvidence || []).length > 0)
      .map(q => ({ question: q.id, abstain: q.abstain, evidence: q.falseEvidence })),
  },
  integrity: {
    identityViolations: r.questions.reduce((s, q) => s + q.identityViolationCount, 0),
    scopeViolations: r.questions.reduce((s, q) => s + q.scopeViolationCount, 0),
  },
  failures: r.questions.flatMap(q => q.facts
    .filter(f => ['qaStage', 'agentToolStage'].some(s => !String(f[s]).startsWith('RETAINED')))
    .map(f => ({
      question: q.id, dimension: q.dimension, document: f.document, marker: f.marker,
      diagnosticFullRank: f.diagnosticFullRank, top8Rank: f.top8Rank, top20Rank: f.top20Rank,
      primaryCause: String(f.qaStage).startsWith('RETAINED')
        ? String(f.agentToolStage).replace(':NOT_IN_TOOL_RESULT', ':SOURCE_CAP(agent名额)')
        : String(f.qaStage),
      agentToolStage: f.agentToolStage, agent12Stage: f.agent12Stage,
    }))),
};

const n = r.questions.length;
const avg = (field) => Math.round(r.questions.reduce((s, q) => s + q[field], 0) / n);
const cost = {
  benchmark: 'p9-rag-generalization-01',
  indexing: r.indexing,
  queryLatency: {
    qaAvgMs: avg('qaLatencyMs'), agentCandidateAvgMs: avg('agentLatencyMs'),
    agentToolAvgMs: avg('agentToolLatencyMs'), diagnosticFullRankingAvgMs: avg('diagnosticFullRankingMs'),
    note: '诊断全量排序不计入生产成本',
  },
  contextCost: {
    qaPromptCodePointsAvg: avg('qaPromptCodePoints'),
    agentPromptCodePointsAvg: avg('agentPromptCodePoints'),
    agentToolQuoteCodePointsAvg: avg('agentToolQuoteTotalCodePoints'),
    qaSourceCountAvg: Math.round(r.questions.reduce((s, q) => s + q.qaSourceCount, 0) / n * 100) / 100,
    agentSourceCountAvg: Math.round(r.questions.reduce((s, q) => s + q.agentToolSourceCount, 0) / n * 100) / 100,
  },
  embeddingCalls: { productionPerQuestion: 2, diagnosticOnlyPerQuestion: 1,
    note: 'QA 与 Agent 各一次查询嵌入；诊断排序一次，不计入生产成本' },
  units: 'code points，非 token；quote 为 600 code point 前缀截断',
};

const perQuestion = r.questions.map(q => ({
  id: q.id, set: q.set, dimension: q.dimension,
  qaLatencyMs: q.qaLatencyMs, agentToolLatencyMs: q.agentToolLatencyMs,
  qaSourceCount: q.qaSourceCount, agentToolSourceCount: q.agentToolSourceCount,
  qaPromptCodePoints: q.qaPromptCodePoints, agentToolQuoteTotalCodePoints: q.agentToolQuoteTotalCodePoints,
}));

fs.writeFileSync(path.join(OUT, 'metrics.json'), JSON.stringify(metrics, null, 2));
fs.writeFileSync(path.join(OUT, 'cost-and-latency.json'), JSON.stringify({ ...cost, perQuestion }, null, 2));

// ---- source-manifest：版本绑定 ----
const hash = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const rel = (p) => path.relative(ROOT, p);
const sources = [
  'ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P9RagGeneralizationPostgresTest.java',
  'tests/fixtures/documents/p9-rag-generalization-01/corpus-manifest.json',
  'tests/fixtures/documents/p9-rag-generalization-01/question-manifest.json',
  'tests/fixtures/documents/p9-rag-generalization-01/question-manifest-holdout.json',
  'tests/fixtures/documents/p9-rag-generalization-01/holdout-integrity.json',
  'docs/design/rag-generalization-benchmark.md',
  'docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/generate-manifests.cjs',
  'docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/compute-metrics.cjs',
].map(p => ({ file: rel(path.join(ROOT, p)), sha256: hash(path.join(ROOT, p)) }));
fs.writeFileSync(path.join(OUT, 'source-manifest.json'), JSON.stringify({
  purpose: '版本绑定证据：本轮评测实现、冻结清单与文档的 SHA-256',
  baselineSha: r.baseline,
  files: sources,
}, null, 2));

console.log('metrics.json / cost-and-latency.json / source-manifest.json 已生成');
console.log('QA', metrics.overall.qa.retained + '/' + metrics.overall.qa.total,
  'Agent', metrics.overall.agent.retained + '/' + metrics.overall.agent.total,
  'failures', metrics.failures.length);
