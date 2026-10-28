// Run from the repository root. Local/offline only; never read .env or provider credentials.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const root = process.cwd();
const out = path.join(root, process.env.P8_EVIDENCE_DIR || 'docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01');
const target = path.join(root, 'ai-collab-backend/target/p8-agent-rag-e2e-quality-01');
const mode = process.argv[2] || 'baseline';
const sha = f => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const walk = p => fs.existsSync(p) ? fs.readdirSync(p, {withFileTypes:true}).flatMap(e => e.isDirectory() ? walk(path.join(p,e.name)) : [path.join(p,e.name)]) : [];
const write = (f,v) => {fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,JSON.stringify(v,null,2)+'\n');};
const git = (...args) => cp.execFileSync('git',args,{cwd:root,encoding:'utf8'}).trim();
fs.mkdirSync(target,{recursive:true});
if (mode === 'baseline') {
  if (git('branch','--show-current') !== 'dev') throw Error('dev required');
  if (fs.existsSync(path.join(out,'baseline.json'))) throw Error('Refuse to overwrite baseline');
  fs.mkdirSync(out,{recursive:true});
  // Replays keep their own evidence directory and preserve all earlier P8 target outputs.
  const priorP8Backup=path.join(root,'ai-collab-backend/target/p8-replays',new Date().toISOString().replaceAll(':','-'),'prior-p8');
  if(walk(target).length) fs.cpSync(target,priorP8Backup,{recursive:true});
  const frozen = [...walk(path.join(root,'docs/acceptance-evidence')).filter(f=>!f.startsWith(out)),...walk(path.join(root,'tests/fixtures/documents'))];
  const hashes = Object.fromEntries(frozen.map(f=>[path.relative(root,f).replaceAll('\\','/'),sha(f)]));
  write(path.join(out,'historical-hashes.json'),hashes);
  // Preserve existing diagnostic outputs before ANY tests. No files are moved or deleted.
  const previous = walk(path.join(root,'ai-collab-backend/target/retrieval-baseline'));
  for(const f of previous){const dest=path.join(target,'prior-output',path.relative(path.join(root,'ai-collab-backend/target/retrieval-baseline'),f));fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(f,dest);}
  write(path.join(out,'protected-output-hashes.json'),Object.fromEntries(previous.map(f=>[path.relative(root,f).replaceAll('\\','/'),sha(f)])));
  const probe = (cmd,args) => {try{return cp.execFileSync(cmd,args,{encoding:'utf8',timeout:10000,stdio:['ignore','pipe','pipe']}).trim();}catch(e){return {status:'UNAVAILABLE',exitCode:e.status};}};
  write(path.join(out,'baseline.json'),{capturedAt:new Date().toISOString(),branch:git('branch','--show-current'),head:git('rev-parse','HEAD'),originDev:git('rev-parse','origin/dev'),initialWorktree:'clean (observed before test-only edits)',worktreeAtCapture:git('status','--short'),environment:{java:probe('java',['-version']),maven:probe('cmd.exe',['/d','/c','ai-collab-backend\\mvnw.cmd -v']),docker:probe('docker',['version','--format','{{.Client.Version}} {{.Server.Version}}']),postgres:probe('docker',['exec','ai-collab-postgres','postgres','--version']),node:probe('node',['--version']),ollama:probe('curl.exe',['--silent','--max-time','5','http://127.0.0.1:11434/api/version']),backendHealth:probe('curl.exe',['--silent','--max-time','5','http://localhost:8080/actuator/health']),frontendStatus:probe('curl.exe',['--silent','--output','NUL','--write-out','%{http_code}','--max-time','5','http://localhost:5173'])},frozenFiles:Object.fromEntries(Object.entries(hashes).filter(([f])=>/questions.json|rubric.md|corpus-manifest.json/.test(f))),dataBoundary:'Only throwaway Testcontainers DBs; no application DB queries or writes',externalChatAuthorization:false});
  console.log('baseline captured'); process.exit(0);
}
if(mode==='restore') {
  for(const [source,name] of [
    ['ai-collab-backend/target/retrieval-baseline/p4-rag-quality-01/real-retrieval-quality-scoped.json','captured-agent-retrieval-quality.json'],
    ['ai-collab-backend/target/retrieval-baseline/2026-10-19/agent-research-quality-paired.json','captured-agent-research-quality-paired.json']
  ]) fs.copyFileSync(path.join(root,source),path.join(target,name));
  const prior=JSON.parse(fs.readFileSync(path.join(out,'protected-output-hashes.json')));
  for(const [rel,hash] of Object.entries(prior)){const dest=path.join(root,rel);const src=path.join(target,'prior-output',path.relative(path.join(root,'ai-collab-backend/target/retrieval-baseline'),dest));fs.copyFileSync(src,dest);if(sha(dest)!==hash)throw Error('Restore mismatch '+rel);}
  const hashes=JSON.parse(fs.readFileSync(path.join(out,'historical-hashes.json')));
  const changed=Object.entries(hashes).filter(([f,h])=>!fs.existsSync(path.join(root,f))||sha(path.join(root,f))!==h).map(([f])=>f);
  write(path.join(out,'historical-integrity.json'),{checkedAt:new Date().toISOString(),filesChecked:Object.keys(hashes).length,changed,priorTargetFilesRestored:Object.keys(prior).length});
  if(changed.length)throw Error('Historical evidence changed');
  console.log('Historical evidence intact; previous target results restored');process.exit(0);
}
const env={...process.env};
// Clear all opt-ins discovered in test annotations and all live-provider activation flags.
for(const f of walk(path.join(root,'ai-collab-backend/src/test/java')).filter(f=>f.endsWith('.java'))){const s=fs.readFileSync(f,'utf8');for(const m of s.matchAll(/EnabledIfEnvironmentVariable\s*\(\s*named\s*=\s*"([^"]+)"/g))delete env[m[1]];}
for(const k of Object.keys(env))if(/^(AI_|GEMINI_|OPENAI_|ANTHROPIC_|ZEN_|KILO_)/.test(k))delete env[k];
const suites={
  retrieval:['-Dtest=P6RagQualityBaselinePostgresTest,AgentRetrievalQualityPostgresTest', '-Drag.baseline.output=target/p8-agent-rag-e2e-quality-01/retrieval-results.json'],
  integration:['-Dtest=P8AgentRagCrossLayerPostgresTest','-Drag.baseline.output=target/p8-agent-rag-e2e-quality-01/integrated-retrieval-results.json'],
  regression:['-Dtest=KnowledgeContextBuilderTest,CrossDocumentSourceSelectorTest,RetrievalCoverageTest,RetrievalQualityHardGatePostgresTest,AgentResearchQualityPostgresTest,AgentResearchCitationMetricsTest,KnowledgeCitationValidatorTest,FrozenKnowledgeQualityRegressionTest,P6QaSourceSelectionExperimentTest,CrossDocumentSourceSelectorStrategyExperimentTest,AgentPauseResumePostgresTest,AgentCrashRecoveryTimingPostgresTest,AgentLoopGuardTest,ToolArgumentValidatorTest'],
  verify:[]
};
if(!suites[mode])throw Error('Unknown mode');
if(['retrieval','integration'].includes(mode))env.AI_REAL_OLLAMA_RETRIEVAL='true';
const reports=`target/p8-agent-rag-e2e-quality-01/${mode}-surefire`;
const args=['-o','-B','-ntp',...suites[mode],`-Drag.baseline.sha=${git('rev-parse','HEAD')}`,mode==='verify'?'verify':'test'];
const startedAt=new Date().toISOString();
const output=fs.openSync(path.join(target,`${mode}-output.txt`),'w');
console.log(`Starting ${mode} ${startedAt}`);
const child=cp.spawn('cmd.exe',['/d','/c',`mvnw.cmd ${args.join(' ')}`],{cwd:path.join(root,'ai-collab-backend'),env,stdio:['ignore',output,output],windowsHide:true});
child.on('exit',exitCode=>{
  fs.closeSync(output);
  const reportDir=path.join(root,'ai-collab-backend',reports);
  fs.mkdirSync(reportDir,{recursive:true});
  for(const f of walk(path.join(root,'ai-collab-backend/target/surefire-reports'))){
    if(fs.statSync(f).mtimeMs >= Date.parse(startedAt)-1000) fs.copyFileSync(f,path.join(reportDir,path.basename(f)));
  }
  const file=path.join(out,'test-matrix.json');const rows=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):[];
  rows.push({id:mode,command:`mvnw.cmd ${args.join(' ')}`,environmentOverrides:['retrieval','integration'].includes(mode)?{AI_REAL_OLLAMA_RETRIEVAL:'true'}:{allOptIns:'unset'},startedAt,endedAt:new Date().toISOString(),exitCode,status:exitCode===0?'PASS':'FAIL',output:`ai-collab-backend/target/p8-agent-rag-e2e-quality-01/${mode}-output.txt`,reports:`ai-collab-backend/${reports}`});write(file,rows);console.log(`${mode} exit=${exitCode}`);process.exitCode=exitCode;
});
