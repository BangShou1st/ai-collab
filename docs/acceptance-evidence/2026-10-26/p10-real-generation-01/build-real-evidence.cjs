// Offline-only evidence assembly. Never calls a model, changes routing, or reruns a test.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const source = path.join(root, 'ai-collab-backend/target/p10-real-generation-01/real');
const rawDir = path.join(__dirname, 'raw/real');
fs.mkdirSync(rawDir, { recursive: true });
for (const file of ['model-call-ledger.json', 'retrieval-context.json', 'generation-results.json']) {
  const input = path.join(source, file);
  const frozen = path.join(rawDir, file);
  if (fs.existsSync(input)) {
    if (fs.existsSync(frozen)) assert.deepEqual(fs.readFileSync(input), fs.readFileSync(frozen), 'Real-run raw evidence changed');
    else fs.copyFileSync(input, frozen);
  }
}
const read = file => JSON.parse(fs.readFileSync(path.join(__dirname, file), 'utf8'));
const write = (file, object) => fs.writeFileSync(path.join(__dirname, file), JSON.stringify(object, null, 2) + '\n');
const sha = text => crypto.createHash('sha256').update(text).digest('hex');
const authorization = read('authorization.json');
assert.equal(authorization.granted, true);
const rawLedger = read('raw/real/model-call-ledger.json');
const contexts = read('raw/real/retrieval-context.json');
const results = read('raw/real/generation-results.json');
const questions = JSON.parse(fs.readFileSync(path.join(root, 'tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json'), 'utf8')).questions;
assert.equal(rawLedger.authorization, true);
assert(rawLedger.chatDispatchReservations <= 8);
assert.equal(rawLedger.calls.length, rawLedger.chatDispatchReservations);
const calls = rawLedger.calls.map(call => {
  const row = results.find(r => r.id === call.id);
  return { ...call, outcome: row.status, errorCode: row.errorCode ?? null,
    providerReceipt: row.providerResponse ? 'RESPONSE_CAPTURED' : 'UNAVAILABLE',
    httpStatus: 'UNAVAILABLE', usage: call.usage ?? 'UNAVAILABLE',
    actualCost: 'UNAVAILABLE', providerAcceptance: row.providerResponse ? 'RESPONSE_RETURNED' : 'UNKNOWN',
    outputBudgetTruncation: row.errorCode === 'AI_PROVIDER_OUTPUT_TRUNCATED' ? 'OBSERVED' : row.providerResponse ? 'NOT_OBSERVED' : 'UNKNOWN_NO_RESPONSE' };
});
for (let i = 1; i < calls.length; i++)
  assert(Date.parse(calls[i].startedAt) - Date.parse(calls[i - 1].startedAt) >= 15000);
const enrichedContexts = results.map(row => {
  const context = contexts.find(c => c.id === row.id);
  if (!context) return { id: row.id, query: row.query, status: 'NOT_RUN', reason: 'BATCH_STOPPED_AFTER_REQUEST_FAILURE',
    candidates: 'UNAVAILABLE_NOT_RUN', sources: 'UNAVAILABLE_NOT_RUN', facts: 'UNAVAILABLE_NOT_RUN' };
  assert.equal(sha(context.systemPrompt), context.systemPromptSha256);
  assert.equal(sha(context.userPrompt), context.userPromptSha256);
  assert.equal(context.wireBody.generationConfig.maxOutputTokens, 1024);
  const question = questions.find(q => q.id === row.id);
  const facts = question.expectedFacts.map(fact => {
    const inIndexCandidates = context.candidates.some(s => s.originalFilename === fact.document && s.content.includes(fact.marker));
    const sources = context.context.sources.filter(s => s.originalFilename === fact.document && s.content.includes(fact.marker));
    return { ...fact, inCandidates: inIndexCandidates, sourceRanks: sources.map(s => s.rank),
      contextAvailability: sources.length ? 'RETAINED' : inIndexCandidates ? 'SELECTION_OR_BODY_TRUNCATION_MISS' : 'RETRIEVAL_MISS',
      measuredFrom: 'THIS_REAL_ATTEMPT_CONTEXT_NOT_P9_HISTORY' };
  });
  const requiredMarkers = (question.rules.requiredMarkers ?? []).map(marker => ({ marker,
    sourceRanks: context.context.sources.filter(s => s.content.includes(marker)).map(s => s.rank),
    contextAvailability: context.context.sources.some(s => s.content.includes(marker)) ? 'RETAINED' : 'CONTEXT_MISS' }));
  const distractFacts = (question.distractFacts ?? []).map(f => ({ ...f,
    sourceRanks: context.context.sources.filter(s => s.originalFilename === f.document && s.content.includes(f.marker)).map(s => s.rank) }));
  return { ...context, status: 'REAL_ATTEMPT_RETRIEVAL_AND_PROMPT_CAPTURED', facts, requiredMarkers, distractFacts,
    promptCodePoints: [...context.systemPrompt + context.userPrompt].length };
});
const enrichedResults = results.map(row => ({ ...row,
  rawAnswer: row.rawAnswer ?? 'UNAVAILABLE_NO_MODEL_RESPONSE',
  providerResponse: row.providerResponse ?? 'UNAVAILABLE_NO_RESPONSE_CAPTURED',
  finalAnswer: row.final?.answer ?? 'UNAVAILABLE_PRODUCTION_PATH_EXITED_BEFORE_SAVE_EXCHANGE',
  validation: row.validation ?? 'NOT_REACHED',
  persistence: row.final ? 'ANSWER_CAPTURED_AND_MATCHED_DATABASE' : 'NOT_REACHED_OR_NOT_RUN',
  mechanicalScoring: row.mechanicalScore ? 'EXECUTED_WITH_FROZEN_P10_SCORER' : row.errorCode === 'AI_PROVIDER_OUTPUT_TRUNCATED' ? 'NOT_ASSESSED_BUDGET_LIMIT' : 'NOT_ASSESSED_NO_ANSWER',
  semanticReview: 'NO_MODEL_ANSWER_TO_REVIEW', independentHumanReview: 'NOT_STARTED_NO_ANSWER',
  modelFactError: 'NOT_ASSESSED', citationSemanticSupport: 'NOT_ASSESSED',
  stopReason: row.status === 'NOT_RUN' ? 'FIRST_REQUEST_TIMEOUT_STOP_POLICY' : row.errorCode ?? null }));
const responses = results.filter(r => r.providerResponse).length;
const completed = results.filter(r => r.status === 'EXECUTED').length;
const failures = results.filter(r => r.status === 'FAILED').length;
const notRun = results.filter(r => r.status === 'NOT_RUN').length;
const transportAttempts = calls.filter(c => c.transportStarted).length;
const verdicts = {
  generationVerdict: transportAttempts ? 'REAL_GENERATION_EXECUTED' : 'REAL_GENERATION_NOT_EXECUTED',
  generationVerdictMeaning: '真实客户端传输已执行；不表示模型生成成功或Provider已接收/计费',
  executionVerdict: failures ? 'STOPPED_ON_REQUEST_FAILURE' : completed === 8 ? 'MEASUREMENT_EXECUTED' : 'LIMITED_EXECUTION',
  qualityVerdict: completed ? 'REQUIRES_PER_QUESTION_REVIEW' : 'NOT_ASSESSABLE_NO_MODEL_RESPONSE'
};
write('retrieval-context.json', { task: 'P10-REAL-GENERATION-01', questions: enrichedContexts });
write('generation-results.json', { task: 'P10-REAL-GENERATION-01', ...verdicts,
  summary: { planned: 8, transportAttempts, providerResponses: responses, completedAnswers: completed, failures, notRun,
    mechanicallyScored: results.filter(r => r.mechanicalScore).length, independentHumanReviewed: 0 }, questions: enrichedResults });
write('model-call-ledger.json', { task: 'P10-REAL-GENERATION-01', authorization,
  provider: { type: 'GEMINI', name: 'Gemini 3.5 Flash Lite', model: rawLedger.model, routeSha256: rawLedger.routeSha256,
    freeTier: 'USER_CONFIRMED_FREE_API', independentlyVerifiedBilling: false, paidBillingEnabledByThisTask: false },
  limits: { chatRequests: 8, maxOutputTokensPerRequest: 1024, minimumIntervalSeconds: 15, automaticChatRetries: 0,
    externalEmbedding: 0, agent: 0, judge: 0 },
  actualCalls: { externalChat: transportAttempts, dispatchReservations: rawLedger.chatDispatchReservations,
    providerAcceptedRequests: responses ? responses : 'UNAVAILABLE', externalEmbedding: 0, agent: 0, judge: 0, automaticRetries: 0 },
  usage: { promptTokens: 'UNAVAILABLE_NO_RESPONSE', completionTokens: 'UNAVAILABLE_NO_RESPONSE', thinkingTokens: 'UNAVAILABLE_NO_RESPONSE',
    cost: 'UNAVAILABLE_NOT_INDEPENDENTLY_VERIFIED', observedAttemptLatencyMs: calls.reduce((n, c) => n + (c.latencyMs ?? 0), 0) },
  calls, ...verdicts,
  limitation: 'transportStarted 标记位于 client.send 前，只证明真实客户端传输尝试；无法证明服务端接收、生成或计费。未将连接超时认定为模型事实错误。' });
const paths = [];
function walk(dir) { for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
  const f = path.join(dir, entry.name); if (entry.isDirectory()) walk(f); else if (entry.name !== 'manifest.sha256') paths.push(f);
} }
walk(__dirname);
fs.writeFileSync(path.join(__dirname, 'manifest.sha256'), paths.sort().map(f => sha(fs.readFileSync(f)) + '  ' + path.relative(__dirname, f).split(path.sep).join('/')).join('\n') + '\n');
console.log(JSON.stringify({ ...verdicts, transportAttempts, completed, failures, notRun }));
