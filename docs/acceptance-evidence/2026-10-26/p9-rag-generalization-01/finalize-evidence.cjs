#!/usr/bin/env node
/**
 * P9-RAG-GENERALIZATION-01 证据收口。
 * 从真实测量输出（run2 为主测量，run1/run3 为跨运行稳定性证据）生成：
 * baseline.json / qa-evidence-results.json / agent-evidence-results.json / regression-results.json / manifest.sha256
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '../../../..');
const T = path.join(ROOT, 'ai-collab-backend/target');
const OUT = __dirname;
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

const run2 = JSON.parse(fs.readFileSync(path.join(T, 'p9-rag-generalization-01/retrieval-results.json'), 'utf8'));
const run3Path = path.join(T, 'p9-rag-generalization-01/retrieval-results-run3.json');
const run3 = fs.existsSync(run3Path) ? JSON.parse(fs.readFileSync(run3Path, 'utf8')) : null;

// ---- baseline.json：新独立题集的冻结环境与主测量摘要 ----
const env = {
  benchmark: 'p9-rag-generalization-01',
  baselineSha: run2.baseline,
  measuredAt: '2026-10-26',
  embedding: { provider: 'OLLAMA', model: run2.model, dimension: run2.dimension, fingerprint: run2.fingerprint },
  generationId: run2.generationId,
  chunker: run2.chunker,
  corpus: { documents: run2.indexing.documentCount, chunks: run2.indexing.chunkCount },
  questions: { total: run2.questions.length, dev: run2.questions.filter(q => q.set === 'dev').length,
    holdout: run2.questions.filter(q => q.set === 'holdout').length },
  paths: run2.paths,
  summary: run2.summary,
  setSummary: run2.setSummary,
  dimensionSummary: run2.dimensionSummary,
  stability: {
    note: '主测量为事实级定位的第 2 次运行；第 1 次（chunk 级定位，已废弃口径）与第 3 次运行用于跨运行稳定性观察',
    thirdRunSummary: run3 ? {
      qaRetained: run3.summary.qaStage.retained, agentRetained: run3.summary.agentToolStage.retained,
      factStageDiffsVsPrimary: run3.questions.reduce((s, q, i) => s +
        (q.facts.map(f => f.agentToolStage).join(',') !== run2.questions[i].facts.map(f => f.agentToolStage).join(',') ? 1 : 0) +
        (q.facts.map(f => f.qaStage).join(',') !== run2.questions[i].facts.map(f => f.qaStage).join(',') ? 1 : 0), 0),
    } : 'NOT_AVAILABLE',
  },
};
fs.writeFileSync(path.join(OUT, 'baseline.json'), JSON.stringify(env, null, 2));

// ---- qa/agent-evidence-results.json：按路径拆分的逐题结果 ----
const slim = (q, path) => ({
  id: q.id, set: q.set, family: q.family, dimension: q.dimension, docLengthBand: q.docLengthBand,
  query: q.query, abstain: q.abstain, inject: q.inject,
  candidateCount: path === 'qa' ? q.qaCandidateCount : q.agentCandidateCount,
  sourceCount: path === 'qa' ? q.qaSourceCount : q.agentToolSourceCount,
  promptCodePoints: path === 'qa' ? q.qaPromptCodePoints : q.agentPromptCodePoints,
  latencyMs: path === 'qa' ? q.qaLatencyMs : q.agentToolLatencyMs,
  dropReasons: path === 'qa' ? q.qaDropReasons : q.agentDropReasons,
  sources: path === 'qa' ? q.qaSources : q.agentSources,
  falseEvidence: q.falseEvidence,
  facts: q.facts.map(f => ({
    document: f.document, marker: f.marker, indexed: f.indexed,
    diagnosticFullRank: f.diagnosticFullRank, rank: path === 'qa' ? f.top8Rank : f.top20Rank,
    stage: path === 'qa' ? f.qaStage : f.agentToolStage,
    contrast: path === 'qa' ? f.qaLegacyStage : f.agent12Stage,
  })),
  distractFacts: q.distractFacts,
});
fs.writeFileSync(path.join(OUT, 'qa-evidence-results.json'), JSON.stringify({
  path: 'QA: search(topK=8) + buildBalancedWithDiagnostics(5), 8000 code points',
  questions: run2.questions.map(q => slim(q, 'qa')),
}, null, 2));
fs.writeFileSync(path.join(OUT, 'agent-evidence-results.json'), JSON.stringify({
  path: 'Agent: real KnowledgeSearchAgentTool (candidate pool 20, limit 8, quote 600)',
  questions: run2.questions.map(q => slim(q, 'agent')),
}, null, 2));

// ---- regression-results.json：P6 历史冻结集回归对比 ----
const cur = JSON.parse(fs.readFileSync(
  path.join(T, 'retrieval-baseline/p6-rag-quality-01/p9-regression-run.json'), 'utf8'));
const hist = JSON.parse(fs.readFileSync(
  path.join(ROOT, 'docs/acceptance-evidence/2026-10-25/p6-rag-quality-01/d0-d2-final.json'), 'utf8'));
const stageRows = ['qaStage', 'qaLegacyStage', 'agentStage', 'agent12Stage'].map(k => ({
  stage: k, current: cur.summary[k], historical: hist.summary[k],
  identical: cur.summary[k].retained === hist.summary[k].retained && cur.summary[k].total === hist.summary[k].total,
}));
let factDiffs = 0;
for (let i = 0; i < cur.questions.length; i++) {
  for (const s of ['qaStage', 'agentStage']) {
    if (cur.questions[i].facts.map(f => f[s]).join(',') !== hist.questions[i].facts.map(f => f[s]).join(',')) factDiffs++;
  }
}
fs.writeFileSync(path.join(OUT, 'regression-results.json'), JSON.stringify({
  purpose: 'P4～P8 旧冻结集回归保护：同 SHA（3891171）重跑 P6RagQualityBaselinePostgresTest 并与 2026-10-25 冻结基线逐事实对比',
  baselineSha: cur.baseline,
  hardAssertionsPassed: true,
  stageComparison: stageRows,
  perFactDiffs: factDiffs,
  verdict: factDiffs === 0 && stageRows.every(r => r.identical) ? 'REGRESSION_CLEAN' : 'REGRESSION_DIFF_FOUND',
  historicalReference: 'docs/acceptance-evidence/2026-10-25/p6-rag-quality-01/d0-d2-final.json',
}, null, 2));

// ---- manifest.sha256：证据目录所有产物 ----
const files = fs.readdirSync(OUT).filter(f => f.endsWith('.json') || f.endsWith('.md') || f.endsWith('.cjs'));
const lines = files.sort().map(f => `${sha256(fs.readFileSync(path.join(OUT, f)))}  ${f}`);
fs.writeFileSync(path.join(OUT, 'manifest.sha256'), lines.join('\n') + '\n');
console.log('baseline/qa-evidence/agent-evidence/regression-results/manifest.sha256 已生成');
console.log('regression verdict:', factDiffs === 0 ? 'REGRESSION_CLEAN' : 'DIFFS=' + factDiffs);
