#!/usr/bin/env node
/**
 * P9-RAG-GENERALIZATION-01 清单生成与完整性校验。
 * - 扫描 tests/fixtures/documents/p9-rag-generalization-01/ 的 23 篇语料，计算 SHA-256 与 code point 数；
 * - 校验 question-manifest*.json 中每个 fact/distractFact 的 marker 是对应文档的逐字子串；
 * - 生成 corpus-manifest.json（语料冻结）与 holdout-integrity.json（Holdout 冻结）。
 * 只读语料与问题文件，仅写清单文件；重复运行幂等。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '../../../..');
const DIR = path.join(ROOT, 'tests/fixtures/documents/p9-rag-generalization-01');
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const codePoints = (s) => [...s].length;

const FAMILY_SET = { A: 'dev', B: 'dev', C: 'dev', D: 'holdout', E: 'holdout', F: 'holdout' };
const files = fs.readdirSync(DIR).filter(f => f.endsWith('.md')).sort();
if (files.length !== 23) throw new Error(`期望 23 篇语料，实际 ${files.length}`);

const docs = [];
const contentByFile = {};
for (const f of files) {
  const raw = fs.readFileSync(path.join(DIR, f), 'utf8');
  if (raw.charCodeAt(0) === 0xFEFF) throw new Error(`${f} 含 BOM`);
  contentByFile[f] = raw;
  const family = f.charAt(0);
  docs.push({ filename: f, family, set: FAMILY_SET[family], codePoints: codePoints(raw), sha256: sha256(raw) });
}

const verifyQuestions = (file) => {
  const q = JSON.parse(fs.readFileSync(path.join(DIR, file), 'utf8'));
  let markers = 0;
  for (const item of q.questions) {
    for (const key of ['facts', 'distractFacts']) {
      for (const fact of item[key] || []) {
        const body = contentByFile[fact.document];
        if (body === undefined) throw new Error(`${item.id}: 文档不存在 ${fact.document}`);
        if (!body.includes(fact.marker)) {
          throw new Error(`${item.id}: marker 不在 ${fact.document} 中逐字存在: ${fact.marker}`);
        }
        markers++;
      }
    }
    for (const doc of item.distractorDocs || []) {
      if (!contentByFile[doc]) throw new Error(`${item.id}: distractorDoc 不存在 ${doc}`);
    }
    const sets = new Set((item.facts || []).map(f => FAMILY_SET[f.document.charAt(0)]));
    if (item.set === 'dev' && sets.has('holdout')) throw new Error(`${item.id}: dev 题引用 holdout 语料事实`);
  }
  console.log(`${file}: ${q.questions.length} 题, ${markers} 个 marker 全部逐字校验通过`);
  return q;
};

const dev = verifyQuestions('question-manifest.json');
const holdout = verifyQuestions('question-manifest-holdout.json');
const all = [...dev.questions, ...holdout.questions];
const abstain = all.filter(q => q.abstain).length;
const inject = all.filter(q => q.inject).length;
const factCount = all.reduce((s, q) => s + (q.facts || []).length, 0);
console.log(`问题总数 ${all.length}（dev ${dev.questions.length} / holdout ${holdout.questions.length}），必答事实 ${factCount}，拒答 ${abstain}，注入 ${inject}`);

fs.writeFileSync(path.join(DIR, 'corpus-manifest.json'), JSON.stringify({
  benchmark: 'p9-rag-generalization-01',
  createdAt: '2026-10-26',
  chunker: 'production DocumentChunker 1200/150',
  encoding: 'UTF-8 无 BOM',
  documents: docs,
}, null, 2));

const holdoutDocs = docs.filter(d => d.set === 'holdout');
fs.writeFileSync(path.join(DIR, 'holdout-integrity.json'), JSON.stringify({
  benchmark: 'p9-rag-generalization-01',
  frozenAt: '2026-10-26',
  methodNote: 'Holdout 为方法学保留集（非密码学盲测）：冻结后不得依据其测量结果调整生产检索规则、评测参数或标注。若任何候选方案消费过 Holdout 结果，必须标记该保留集已消费并另建新集。',
  holdoutDocuments: holdoutDocs.map(d => ({ filename: d.filename, sha256: d.sha256, codePoints: d.codePoints })),
  holdoutQuestionFile: { filename: 'question-manifest-holdout.json', sha256: sha256(fs.readFileSync(path.join(DIR, 'question-manifest-holdout.json'))) },
  devQuestionFile: { filename: 'question-manifest.json', sha256: sha256(fs.readFileSync(path.join(DIR, 'question-manifest.json'))) },
  holdoutQuestionCount: holdout.questions.length,
  devQuestionCount: dev.questions.length,
}, null, 2));

console.log('corpus-manifest.json 与 holdout-integrity.json 已生成');
