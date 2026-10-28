// P10-REAL-GENERATION-02 独立证据组装与完整性校验（离线，不调用模型、不改路由、不重跑测试）。
//
// 与旧批次 docs/acceptance-evidence/2026-10-26/p10-real-generation-01/build-real-evidence.cjs 完全独立：
//   - 只读本批次的 ai-collab-backend/target/p10-real-generation-02/real/
//   - 只写本批次目录 docs/acceptance-evidence/2026-10-26/p10-real-generation-02/
//   - 不读取、不复制、不校验、不覆盖旧批次 01 的任何文件
//
// 拒绝条件：授权未授予、账本缺失/超预算、调用数与预留数不符、请求间隔 < 15s、
// 出站 body 的 maxOutputTokens 不是 1024、证据内出现凭据字段、冻结语料哈希漂移。

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

const TASK = 'P10-REAL-GENERATION-02';
const BATCH = 'p10-real-generation-02';
const here = __dirname;
const root = path.resolve(here, '../../../..');
const source = path.join(root, 'ai-collab-backend/target', BATCH, 'real');
const rawDir = path.join(here, 'raw/real');

const read = file => JSON.parse(fs.readFileSync(path.join(here, file), 'utf8'));
const sha = text => crypto.createHash('sha256').update(text).digest('hex');
const write = (file, object) => fs.writeFileSync(path.join(here, file), JSON.stringify(object, null, 2) + '\n');

// ---- 0. 授权必须先存在且已授予 ----
const authorizationFile = path.join(here, 'authorization.json');
if (!fs.existsSync(authorizationFile)) throw new Error('authorization.json missing: real batch evidence requires explicit approval');
const authorization = read('authorization.json');
assert.equal(authorization.granted, true, 'Real batch evidence requires granted authorization');
assert.equal(authorization.task, TASK);
assert.equal(authorization.maxChatRequests, 8);
assert.equal(authorization.maxOutputTokensPerRequest, 1024);
assert.equal(authorization.minimumIntervalSeconds, 15);
assert.equal(authorization.externalEmbedding, 0);
assert.equal(authorization.agent, 0);
assert.equal(authorization.judge, 0);
assert.equal(authorization.automaticChatRetries, 0);
assert.equal(authorization.paidBillingChangesAllowed, false);

// ---- 1. 原始运行输出必须存在 ----
for (const file of ['model-call-ledger.json', 'retrieval-context.json', 'generation-results.json']) {
  if (!fs.existsSync(path.join(source, file))) throw new Error('Real-run raw output missing: ' + file);
}

// ---- 2. 冻结原始文件（已存在则逐字节校验，绝不覆盖）----
fs.mkdirSync(rawDir, { recursive: true });
for (const file of ['model-call-ledger.json', 'retrieval-context.json', 'generation-results.json']) {
  const input = path.join(source, file);
  const frozen = path.join(rawDir, file);
  if (fs.existsSync(frozen)) {
    assert.deepEqual(fs.readFileSync(input), fs.readFileSync(frozen), 'Real-run raw evidence changed: ' + file);
  } else {
    fs.copyFileSync(input, frozen);
  }
}
const rawLedger = read('raw/real/model-call-ledger.json');
const contexts = read('raw/real/retrieval-context.json');
const results = read('raw/real/generation-results.json');

// ---- 3. 凭据零容忍扫描 ----
const forbiddenKeys = /^(encrypted_api_key|encryptedApiKey|apiKey|api_key|headers|masterKey|secretKey|secret_key)$/i;
function scan(value, trail) {
  if (!value || typeof value !== 'object') return;
  for (const [key, item] of Object.entries(value)) {
    if (forbiddenKeys.test(key)) throw new Error('Credential field in evidence: ' + trail + '.' + key);
    if (/^authorization$/i.test(key) && typeof item === 'string') throw new Error('Authorization header in evidence: ' + trail + '.' + key);
    scan(item, trail + '.' + key);
  }
}
scan(rawLedger, 'ledger');
scan(contexts, 'contexts');
scan(results, 'results');

// ---- 4. 冻结语料完整性 ----
const fixtures = path.join(root, 'tests/fixtures/documents/p9-rag-generalization-01');
for (const entry of JSON.parse(fs.readFileSync(path.join(fixtures, 'corpus-manifest.json'), 'utf8')).documents) {
  const bytes = fs.readFileSync(path.join(fixtures, entry.filename));
  if (sha(bytes) !== entry.sha256) throw new Error('Frozen corpus changed: ' + entry.filename);
}

// ---- 5. 账本门禁 ----
assert.equal(rawLedger.task, TASK);
assert.equal(rawLedger.mode, 'REAL');
assert.equal(rawLedger.authorization, true);
assert(rawLedger.chatDispatchReservations <= 8, 'Chat dispatch budget exceeded');
assert.equal(rawLedger.calls.length, rawLedger.chatDispatchReservations, 'Call ledger and reservations disagree');
assert.equal(rawLedger.externalEmbedding, 0);
assert.equal(rawLedger.agent, 0);
assert.equal(rawLedger.judge, 0);
assert.equal(rawLedger.chatRetries, 0);
assert.equal(rawLedger.routeSha256, authorization.provider.routeSha256, 'Route snapshot changed after approval');

// ---- 6. 逐题调用账本（传输尝试 ≠ Provider 接收 ≠ 成功生成）----
const calls = rawLedger.calls.map(call => {
  const row = results.find(r => r.id === call.id);
  const responded = Boolean(row && row.providerResponse);
  return {
    ...call,
    outcome: row ? row.status : 'NOT_RUN',
    errorCode: row && row.errorCode ? row.errorCode : null,
    providerReceipt: responded ? 'RESPONSE_CAPTURED' : 'UNAVAILABLE',
    httpStatus: responded ? '200_OR_2XX_RESPONSE_RETURNED' : 'UNAVAILABLE',
    usage: call.usage ?? 'UNAVAILABLE',
    actualCost: 'UNAVAILABLE',
    providerAcceptance: responded ? 'RESPONSE_RETURNED' : 'UNKNOWN',
    outputBudgetTruncation: row && row.errorCode === 'AI_PROVIDER_OUTPUT_TRUNCATED'
      ? 'OBSERVED'
      : responded ? 'NOT_OBSERVED' : 'UNKNOWN_NO_RESPONSE',
  };
});
for (let i = 1; i < calls.length; i++) {
  const gap = Date.parse(calls[i].startedAt) - Date.parse(calls[i - 1].startedAt);
  assert(gap >= 15000, 'Chat interval below 15s between ' + calls[i - 1].id + ' and ' + calls[i].id);
}

// ---- 7. 逐题上下文证据可得性（按本次真实上下文重算，不用 P9 历史）----
const questions = JSON.parse(fs.readFileSync(path.join(fixtures, 'p10-evaluation-question-set.json'), 'utf8')).questions;
const enrichedContexts = results.map(row => {
  const context = contexts.find(c => c.id === row.id);
  if (!context) {
    return {
      id: row.id, query: row.query, status: 'NOT_RUN',
      reason: 'BATCH_STOPPED_AFTER_REQUEST_FAILURE',
      candidates: 'UNAVAILABLE_NOT_RUN', sources: 'UNAVAILABLE_NOT_RUN', facts: 'UNAVAILABLE_NOT_RUN',
    };
  }
  assert.equal(sha(context.systemPrompt), context.systemPromptSha256, 'System prompt hash mismatch: ' + row.id);
  assert.equal(sha(context.userPrompt), context.userPromptSha256, 'User prompt hash mismatch: ' + row.id);
  assert.equal(context.wireBody.generationConfig.maxOutputTokens, 1024, 'Unexpected output cap: ' + row.id);
  const question = questions.find(q => q.id === row.id);
  const facts = question.expectedFacts.map(fact => {
    const inIndexCandidates = context.candidates.some(s => s.originalFilename === fact.document && s.content.includes(fact.marker));
    const sources = context.context.sources.filter(s => s.originalFilename === fact.document && s.content.includes(fact.marker));
    return {
      ...fact,
      inCandidates: inIndexCandidates,
      sourceRanks: sources.map(s => s.rank),
      contextAvailability: sources.length ? 'RETAINED' : inIndexCandidates ? 'SELECTION_OR_BODY_TRUNCATION_MISS' : 'RETRIEVAL_MISS',
      measuredFrom: 'THIS_REAL_ATTEMPT_CONTEXT_NOT_P9_HISTORY',
    };
  });
  const requiredMarkers = (question.rules.requiredMarkers ?? []).map(marker => ({
    marker,
    sourceRanks: context.context.sources.filter(s => s.content.includes(marker)).map(s => s.rank),
    contextAvailability: context.context.sources.some(s => s.content.includes(marker)) ? 'RETAINED' : 'CONTEXT_MISS',
  }));
  const distractFacts = (question.distractFacts ?? []).map(f => ({
    ...f,
    sourceRanks: context.context.sources.filter(s => s.originalFilename === f.document && s.content.includes(f.marker)).map(s => s.rank),
  }));
  return {
    ...context, status: 'REAL_ATTEMPT_RETRIEVAL_AND_PROMPT_CAPTURED',
    facts, requiredMarkers, distractFacts,
    promptCodePoints: [...context.systemPrompt + context.userPrompt].length,
  };
});

// ---- 8. 逐题结果（原始回答 / 最终回答 / 校验差异分开）----
const enrichedResults = results.map(row => {
  const hasAnswer = typeof row.rawAnswer === 'string' && row.rawAnswer.length > 0;
  const finalAnswer = row.final ? row.final.answer : null;
  return {
    ...row,
    rawAnswer: hasAnswer ? row.rawAnswer : 'UNAVAILABLE_NO_MODEL_RESPONSE',
    providerResponse: row.providerResponse ?? 'UNAVAILABLE_NO_RESPONSE_CAPTURED',
    finalAnswer: finalAnswer ?? 'UNAVAILABLE_PRODUCTION_PATH_EXITED_BEFORE_SAVE_EXCHANGE',
    rawEqualsFinal: hasAnswer && finalAnswer != null ? row.rawAnswer === finalAnswer : 'UNAVAILABLE',
    validation: row.validation ?? 'NOT_REACHED',
    persistence: row.final ? 'ANSWER_CAPTURED_AND_MATCHED_DATABASE' : 'NOT_REACHED_OR_NOT_RUN',
    mechanicalScoring: row.mechanicalScore
      ? 'EXECUTED_WITH_FROZEN_P10_SCORER'
      : row.errorCode === 'AI_PROVIDER_OUTPUT_TRUNCATED' ? 'NOT_ASSESSED_BUDGET_LIMIT' : 'NOT_ASSESSED_NO_ANSWER',
    semanticReview: hasAnswer ? 'PENDING_INDEPENDENT_HUMAN_REVIEW' : 'NO_MODEL_ANSWER_TO_REVIEW',
    independentHumanReview: 'NOT_STARTED',
    modelFactError: hasAnswer ? 'PENDING_HUMAN_REVIEW' : 'NOT_ASSESSED',
    citationSemanticSupport: hasAnswer ? 'PENDING_HUMAN_REVIEW' : 'NOT_ASSESSED',
    stopReason: row.status === 'NOT_RUN' ? 'STOP_POLICY_AFTER_REQUEST_FAILURE' : (row.errorCode ?? null),
  };
});

// ---- 9. 裁决（执行裁决与质量裁决分开）----
const transportAttempts = calls.filter(c => c.transportStarted).length;
const responses = results.filter(r => r.providerResponse).length;
const completed = results.filter(r => r.status === 'EXECUTED').length;
const failures = results.filter(r => r.status === 'FAILED').length;
const notRun = results.filter(r => r.status === 'NOT_RUN').length;
const verdicts = {
  generationVerdict: transportAttempts ? 'REAL_GENERATION_EXECUTED' : 'REAL_GENERATION_NOT_EXECUTED',
  generationVerdictMeaning: '真实客户端传输已执行；不表示模型生成成功或Provider已接收/计费',
  executionVerdict: failures ? 'STOPPED_ON_REQUEST_FAILURE' : completed === 8 ? 'MEASUREMENT_EXECUTED' : 'LIMITED_EXECUTION',
  qualityVerdict: completed ? 'REQUIRES_PER_QUESTION_REVIEW' : 'NOT_ASSESSABLE_NO_MODEL_RESPONSE',
};

write('retrieval-context.json', { task: TASK, questions: enrichedContexts });
write('generation-results.json', {
  task: TASK, ...verdicts,
  summary: {
    planned: 8, transportAttempts, providerResponses: responses, completedAnswers: completed,
    failures, notRun, mechanicallyScored: results.filter(r => r.mechanicalScore).length,
    independentHumanReviewed: 0,
  },
  questions: enrichedResults,
});
write('model-call-ledger.json', {
  task: TASK, authorization,
  provider: {
    type: rawLedger.provider, name: authorization.provider.name, model: rawLedger.model,
    routeSha256: rawLedger.routeSha256,
    freeTier: authorization.provider.freeTier,
    independentlyVerifiedBilling: authorization.provider.independentlyVerifiedBilling,
    paidBillingEnabledByThisTask: false,
  },
  limits: {
    chatRequests: 8, maxOutputTokensPerRequest: 1024, minimumIntervalSeconds: 15,
    automaticChatRetries: 0, externalEmbedding: 0, agent: 0, judge: 0,
  },
  actualCalls: {
    externalChat: transportAttempts,
    dispatchReservations: rawLedger.chatDispatchReservations,
    providerAcceptedRequests: responses ? responses : 'UNAVAILABLE',
    externalEmbedding: 0, agent: 0, judge: 0, automaticRetries: 0,
  },
  usage: {
    promptTokens: responses ? 'SEE_CALL_USAGE' : 'UNAVAILABLE_NO_RESPONSE',
    completionTokens: responses ? 'SEE_CALL_USAGE' : 'UNAVAILABLE_NO_RESPONSE',
    thinkingTokens: 'UNAVAILABLE_NOT_REPORTED_BY_PROVIDER',
    cost: 'UNAVAILABLE_NOT_INDEPENDENTLY_VERIFIED',
    observedAttemptLatencyMs: calls.reduce((n, c) => n + (c.latencyMs ?? 0), 0),
  },
  calls, ...verdicts,
  limitation: 'transportStarted 标记位于 client.send 前，只证明真实客户端传输尝试；无法证明服务端接收、生成或计费。',
});

// ---- 10. 冻结本批次证据清单（不含 manifest 自身）----
const paths = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const f = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(f);
    else if (entry.name !== 'manifest.sha256') paths.push(f);
  }
})(here);
fs.writeFileSync(path.join(here, 'manifest.sha256'),
  paths.sort().map(f => sha(fs.readFileSync(f)) + '  ' + path.relative(here, f).split(path.sep).join('/')).join('\n') + '\n');

console.log(JSON.stringify({ ...verdicts, transportAttempts, completed, failures, notRun }));
