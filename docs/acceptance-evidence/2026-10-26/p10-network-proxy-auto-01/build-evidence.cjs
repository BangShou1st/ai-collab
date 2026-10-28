/* Offline evidence assembly. Never invokes a model, network request or reads .env. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
const here = __dirname;
const baseline = 'fca8860a4f9f83e726f532985db83214bed43c16';
const old = 'docs/acceptance-evidence/2026-10-26/p10-real-generation-01/';
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const text = file => { const b = fs.readFileSync(file); return b.toString(b[0] === 255 && b[1] === 254 ? 'utf16le' : 'utf8').replace(/^\uFEFF/, ''); };
const write = (name, value) => fs.writeFileSync(path.join(here, name), value);
const json = (name, value) => write(name, JSON.stringify(value, null, 2) + '\n');
const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(item => item.isDirectory() ? walk(path.join(dir, item.name)) : [path.join(dir, item.name)]);
const manifest = () => write('manifest.sha256', walk(here).filter(file => file !== path.join(here, 'manifest.sha256')).sort().map(file => `${sha(fs.readFileSync(file))}  ${path.relative(here, file).replaceAll('\\', '/')}`).join('\n') + '\n');
if (process.argv.includes('--manifest-only')) { manifest(); process.exit(0); }
if (fs.existsSync(path.join(here, 'call-ledger.json'))) throw new Error('Existing task ledger is protected; use --manifest-only after a documentation-only update.');
fs.mkdirSync(path.join(here, 'checks'), { recursive: true });
const specs = [
 ['targeted-01', 'p10-proxy-targeted-01.log', 'test: initial network selection/security/protocol set', false],
 ['targeted-02', 'p10-proxy-targeted-02.log', 'test: corrected SSE fixture and TLS validation', true],
 ['native-diagnostic-first', 'p10-proxy-native-diagnostic.log', 'P10_PROXY_DIAGNOSTIC=true; test -Dtest=P10NetworkProxyDiagnosticTest', true],
 ['backend-full-verify', 'p10-proxy-backend-verify.log', 'verify (complete default suite, live opt-ins absent)', true],
 ['final-affected-verify', 'p10-proxy-final-verify.log', 'verify: network, protocol, legacy Chat timeout, Ollama, Kilo, gate; isolated jacoco-final.exec', true],
 ['maven-explicit-properties', 'p10-proxy-maven-explicit.log', '-o test -Dtest=ProxyForkStartupTest -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Dp10.proxy.expectedHost=127.0.0.1 -Dp10.proxy.expectedPort=3128 -Djava.net.useSystemProxies=false (selection only; no connection)', true],
 ['native-diagnostic-final', 'p10-proxy-native-diagnostic-final.log', 'P10_PROXY_DIAGNOSTIC=true; test -Dtest=P10NetworkProxyDiagnosticTest', true],
 ['kilo-application-wiring-final', 'p10-proxy-kilo-wiring-final.log', '-o test -Dtest=KiloGatewayStarterWiringTest (actual application configuration plus library auto-configuration)', true],
];
const receipts = specs.map(([name, log, command, success]) => {
 const file = path.join(root, 'ai-collab-backend/target', log);
 const raw = fs.readFileSync(file);
 const content = text(file);
 if (content.includes('BUILD SUCCESS') !== success) throw new Error('Unexpected build status: ' + name);
 const lines = content.split(/\r?\n/).filter(line => /^\[(INFO|WARNING|ERROR)\] (Tests run:|BUILD |Total time:|Finished at:|Running |Execution data for class |Classes in bundle |Failed to execute goal|.*does not match)/.test(line));
 write(`checks/${name}-receipt.txt`, `Command: ${command}\nExpected/observed success: ${success}\nOriginal private stdout SHA-256: ${sha(raw)}\nReceipt extracts only Maven status/test/coverage lines; failure original is separately preserved.\n\n${lines.join('\n')}\n`);
 return { name, command, status: success ? 'PASS' : 'FAIL', originalStdoutSha256: sha(raw), summary: lines.filter(line => /Tests run: \d+, Failures:/.test(line)).at(-1), artifact: `checks/${name}-receipt.txt` };
});
json('verification.json', { baseline, receipts, fullSuiteScope: '1830 tests before the final additive legacy timeout mapping/test and explicit fork property test; final affected verify compiles and packages final sources (113 tests). CI validates the committed final tree.', fullSuiteCoverageWarning: 'Historical target/jacoco.exec contained previous class versions; Maven reported mismatches while all tests passed. Final affected verify uses isolated coverage data and has no mismatch warning.', frontend: 'No frontend change; frontend verification remains in normal CI.' });
const sources = {
 'forked-jvm-diagnostic.json': 'ai-collab-backend/target/p10-network-proxy-auto-01/forked-jvm-diagnostic.json',
 'dev-launcher-diagnostic.json': 'ai-collab-backend/target/p10-proxy-dev-diagnostic.txt',
 'standalone-selection.json': 'ai-collab-backend/target/p10-proxy-standalone-selection.json',
 'native-selector-disabled.json': 'ai-collab-backend/target/p10-native-disabled.json',
 'native-selector-enabled.json': 'ai-collab-backend/target/p10-native-enabled.json',
};
for (const [name, file] of Object.entries(sources)) json(name, JSON.parse(text(path.join(root, file))));
for (const name of ['forked-jvm-diagnostic.json', 'dev-launcher-diagnostic.json']) {
 const value = JSON.parse(text(path.join(here, name)));
 if (value.source !== 'JDK_SYSTEM_SELECTOR' || value.selection !== 'PROXY' || !value.nativeSystemProxySelected || value.tls !== 'VERIFIED' || value.chatRequests !== 0) throw new Error('Actual native proxy prerequisite failed: ' + name);
}
const oldFiles = execFileSync('git', ['ls-tree', '-r', '--name-only', baseline, '--', old], { cwd: root, encoding: 'utf8' }).trim().split('\n');
for (const file of oldFiles) {
 if (sha(fs.readFileSync(path.join(root, file))) !== sha(execFileSync('git', ['show', `${baseline}:${file}`], { cwd: root, maxBuffer: 20 * 1024 * 1024 }))) throw new Error('Historical evidence changed: ' + file);
}
const oldManifest = text(path.join(root, old, 'manifest.sha256'));
const entries = oldManifest.trim().split(/\r?\n/);
for (const line of entries) {
 const [, expected, file] = line.match(/^([0-9a-f]{64})  (.+)$/);
 if (sha(fs.readFileSync(path.join(root, old, file))) !== expected) throw new Error('Historical manifest mismatch: ' + file);
}
json('old-evidence-integrity.json', { baseline, oldDirectory: old, trackedFilesByteEqualToBaseline: oldFiles.length, oldManifestEntriesValidated: entries.length, oldManifestSha256: sha(fs.readFileSync(path.join(root, old, 'manifest.sha256'))), changedFiles: [], previousOutcome: 'AQ01 timeout 20912ms; AQ02–AQ08 NOT_RUN; no answer, quality NOT_ASSESSABLE; first Maven database failure retained unchanged.' });
json('call-ledger.json', {
 task: 'P10-NETWORK-PROXY-AUTO-01', status: 'NETWORK_ONLY_NO_CHAT',
 authorization: '用户已确认使用免费 API；本专项不发送新的真实 Chat。未独立核实账号计费状态。',
 route: 'p3u04_admin → KNOWLEDGE_CHAT → GEMINI / gemini-3.5-flash-lite (not modified)',
 actualExternalRequests: { chat: 0, embedding: 0, agent: 0, modelJudge: 0, automaticChatRetry: 0 },
 externalTargetTransportProbes: { connectAttempts: 3, successfulConnect: 3, tlsHandshakeAttempts: 3, successfulVerifiedTls: 3, explanation: 'Initial opt-in Surefire diagnostic, final opt-in Surefire diagnostic, dev launcher diagnostic; each one CONNECT plus TLS. Raw JDK and standalone selection observations make no target connection.' },
 providerApplicationHttpRequests: 0, providerReceipt: 'NOT_TESTED_NO_HTTP_APPLICATION_REQUEST', modelUsage: 'NOT_APPLICABLE_NO_GENERATION_REQUEST',
 paidBillingEnabled: false, paidTierUpgrade: false, independentBillingVerification: false,
 priorBatch: 'p10-real-generation-01 remains ended and byte-preserved; no ledger reset/retry.',
 nextBatch: 'Separate new batch identifier, matching approval and budget/free-tier gates required; network success does not prove Provider/model/quota acceptance.'
});
const changed = new Set([
 ...execFileSync('git', ['diff', '--name-only', baseline], { cwd: root, encoding: 'utf8' }).trim().split('\n'),
 ...execFileSync('git', ['ls-files', '--others', '--exclude-standard'], { cwd: root, encoding: 'utf8' }).trim().split('\n'),
]);
const sourceFiles = [...changed].filter(file => /^(ai-collab-backend\/|dev\.ps1$|README\.md$|\.gitattributes$|docs\/development\/model-network-proxy\.md$|docs\/local-development\.md$|docs\/README\.md$)/.test(file)).sort();
json('source-snapshot.json', { baseline, hashBasis: 'Local working-tree bytes before Git normalization', files: sourceFiles.map(file => ({ path: file, sha256: sha(fs.readFileSync(path.join(root, file))) })) });
manifest();
console.log(JSON.stringify({ receipts: receipts.length, historicalFilesUnchanged: oldFiles.length, sourceFiles: sourceFiles.length, chatRequests: 0 }));
