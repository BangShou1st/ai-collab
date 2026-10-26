// Rebuild authorization-stage evidence only. Never executes models or changes frozen inputs.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '../../../..');
const authorizationFile = path.join(__dirname, 'authorization.json');
if (fs.existsSync(authorizationFile) && JSON.parse(fs.readFileSync(authorizationFile, 'utf8')).granted)
  throw new Error('Authorized phase exists: preflight builder must not overwrite real-run evidence.');
const target = path.join(root, 'ai-collab-backend/target');
const source = path.join(target, 'p10-real-generation-01/preflight');
const read = name => JSON.parse(fs.readFileSync(path.join(source, name), 'utf8'));
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const write = (name, value) => fs.writeFileSync(path.join(__dirname, name), JSON.stringify(value, null, 2) + '\n');
const ledger = read('model-call-ledger.json');
if (ledger.authorization || ledger.chatDispatchReservations !== 0 || ledger.syntheticTransportResponses !== 8)
  throw new Error('Not a completed zero-Chat preflight');
const retrieval = read('retrieval-context.json');
const generated = read('generation-results.json');
if (retrieval.length !== 8 || generated.some(row => row.status !== 'PREFLIGHT_SYNTHETIC_TRANSPORT'))
  throw new Error('Preflight incomplete');
const forbiddenKeys = /^(encrypted_api_key|encryptedApiKey|apiKey|api_key|headers|masterKey)$/i;
function scan(value) {
  if (!value || typeof value !== 'object') return;
  for (const [key, item] of Object.entries(value)) {
    if (forbiddenKeys.test(key)) throw new Error('Credential field in evidence');
    if (/^authorization$/i.test(key) && typeof item === 'string') throw new Error('Authorization header in evidence');
    scan(item);
  }
}
for (const value of [ledger, retrieval, generated]) scan(value);
const fixtures = path.join(root, 'tests/fixtures/documents/p9-rag-generalization-01');
for (const entry of JSON.parse(fs.readFileSync(path.join(fixtures, 'corpus-manifest.json'), 'utf8')).documents)
  if (hash(fs.readFileSync(path.join(fixtures, entry.filename))) !== entry.sha256)
    throw new Error('Frozen corpus changed');
fs.mkdirSync(path.join(__dirname, 'preflight'), { recursive: true });
for (const name of ['model-call-ledger.json', 'retrieval-context.json', 'generation-results.json'])
  fs.copyFileSync(path.join(source, name), path.join(__dirname, 'preflight', name));
const pending = retrieval.map(row => ({ id: row.id, query: row.query,
  status: 'NOT_RUN', reason: 'PENDING_EXPLICIT_APPROVAL', independentHumanReview: 'NOT_STARTED' }));
write('model-call-ledger.json', {
  task: 'P10-REAL-GENERATION-01', authorization: { granted: false,
    approvedPreflightUser: 'p3u04_admin', note: 'User selection is not approval of model requests' },
  provider: { type: 'GEMINI', name: 'Gemini 3.5 Flash Lite', model: 'gemini-3.5-flash-lite',
    enabled: true, credentialDecrypt: 'PREFLIGHT_SUCCEEDED', networkAvailability: 'NOT_PROBED',
    routeSha256: ledger.routeSha256, accountFreeTier: 'UNCONFIRMED', remainingQuota: 'UNAVAILABLE',
    rateLimits: 'USER_CONFIRMATION_REQUIRED' },
  limits: { chatRequests: 8, outputTokensPerRequest: 1024, externalEmbedding: 0,
    agentCalls: 0, judgeCalls: 0, chatRetries: 0 },
  actualCalls: { externalChat: 0, externalEmbedding: 0, agent: 0, judge: 0 },
  actualUsage: { inputTokens: 'UNAVAILABLE', outputTokens: 'UNAVAILABLE', latencyMs: 'UNAVAILABLE', cost: 'UNAVAILABLE' },
  estimate: { promptCharactersInLocalPreflight: retrieval.reduce((n, r) => n + [...r.systemPrompt + r.userPrompt].length, 0),
    inputTokens: '5000-20000_ROUGH_ESTIMATE_NOT_MEASURED', outputTokensMaximum: 8192,
    conditionalPaidCostUsd: '0.022-0.027_ESTIMATE_NOT_AUTHORIZATION_OR_CAP',
    sources: ['https://ai.google.dev/gemini-api/docs/pricing', 'https://ai.google.dev/gemini-api/docs/rate-limits'] },
  localPreflight: { syntheticResponses: 8, externalChat: 0, localEmbeddingRequests: 'UNAVAILABLE',
    localEmbeddingRetryPolicy: 'PRODUCTION_MAX_3_ATTEMPTS_NOT_CHAT_RETRIES' },
  questions: pending, generationVerdict: 'REAL_GENERATION_NOT_EXECUTED',
  executionVerdict: 'NOT_EXECUTED_PENDING_APPROVAL', qualityVerdict: 'NOT_ASSESSED'
});
write('retrieval-context.json', { status: 'NO_REAL_GENERATION_RUN',
  note: 'Local preflight is separately archived. Never reuse its source identities as real-run evidence.', questions: pending });
write('generation-results.json', { generationVerdict: 'REAL_GENERATION_NOT_EXECUTED',
  executionVerdict: 'NOT_EXECUTED_PENDING_APPROVAL', qualityVerdict: 'NOT_ASSESSED', questions: pending });
function receiptFile(name) {
  const local = path.join(target, name);
  return fs.existsSync(local) ? local : path.join(__dirname, 'checks', name.replace(/\.log$/, '.txt'));
}
const verifyFile = receiptFile('p10-backend-verify.log');
const verify = fs.existsSync(verifyFile) ? fs.readFileSync(verifyFile, 'utf8') : '';
const testTotals = [...verify.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)].at(-1);
const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
function checkResult(name) {
  const file = receiptFile(name);
  const log = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
  const totals = [...log.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)].at(-1);
  return { status: log.includes('BUILD SUCCESS') ? 'PASSED' : log.includes('BUILD FAILURE') ? 'FAILED' : 'UNAVAILABLE',
    summary: totals ? { tests: +totals[1], failures: +totals[2], errors: +totals[3], skipped: +totals[4] } : 'UNAVAILABLE' };
}
write('preflight-summary.json', {
  task: 'P10-REAL-GENERATION-01', baselineSha: git('rev-parse', 'HEAD'),
  sourceFiles: ['P10RealGenerationGate.java', 'P10RealGenerationGateTest.java', 'P10RealGenerationPostgresTest.java']
    .map(name => ({ name, sha256: hash(fs.readFileSync(path.join(root, 'ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval', name))) })),
  frozenCorpus: 'ALL_MANIFEST_HASHES_VERIFIED', productionJavaChanged: false,
  localPreflight: { status: 'PASSED', syntheticResponses: 8, externalChat: 0, externalEmbedding: 0,
    freshFacts: retrieval.map(row => ({ id: row.id, candidates: row.candidates.length, sources: row.context.sources.length,
      facts: row.facts, outputCap: row.wireBody.generationConfig.maxOutputTokens, sourceIdentity: row.sourceIdentity })) },
  defaultMode: { integrationClass: 'SKIPPED_WITHOUT_OPT_IN', externalRequestsFromNewTests: 0 },
  backendVerify: { status: verify.includes('BUILD SUCCESS') ? 'PASSED' : verify.includes('BUILD FAILURE') ? 'FAILED' : 'RUNNING_OR_UNAVAILABLE',
    summary: testTotals ? { tests: +testTotals[1], failures: +testTotals[2], errors: +testTotals[3], skipped: +testTotals[4] } : 'UNAVAILABLE',
    initialError: 'AgentRunContextCommitPostgresTest: PostgreSQL connection failed; nested BindException Address already in use',
    originalErrorEvidence: 'checks/backend-verify-initial-error.txt' },
  finalTargetedAndFailedClassRerun: checkResult('p10-final-targeted.log'),
  finalDefaultOffVerification: checkResult('p10-default-off.log'),
  frontend: 'NOT_RUN_NO_FRONTEND_CHANGES_PER_TASK_SCOPE',
  git: { head: git('rev-parse', 'HEAD'), branch: git('branch', '--show-current'),
    changes: 'UNCOMMITTED_AUTHORIZATION_STAGE_PREPARATION', newCI: 'NOT_TRIGGERED',
    pr18: 'OPEN_DRAFT_NOT_MERGED_AT_PREFLIGHT' }
});
const files = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (entry.name !== 'manifest.sha256') files.push(file);
  }
}
walk(__dirname);
fs.writeFileSync(path.join(__dirname, 'manifest.sha256'), files.sort().map(file =>
  hash(fs.readFileSync(file)) + '  ' + path.relative(__dirname, file).split(path.sep).join('/')).join('\n') + '\n');
console.log('P10 authorization-stage evidence rebuilt; real Chat calls=0.');
