// Local-only runner. No application configuration or credentials are read.
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process'), crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../..');
const target = path.resolve(root, process.env.P8_FIX_TARGET_DIR || 'ai-collab-backend/target/p8-rag-quality-fix-01');
const evidence = path.resolve(root, process.env.P8_FIX_EVIDENCE_DIR || 'docs/acceptance-evidence/2026-10-25/p8-rag-quality-fix-01');
if(!target.startsWith(path.join(root,'ai-collab-backend/target')+path.sep)||!evidence.startsWith(path.join(root,'docs/acceptance-evidence')+path.sep))throw Error('Controlled output directories required');
const walk = p => fs.existsSync(p) ? fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]) : [];
const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const rel = p => path.relative(root,p).replaceAll('\\','/');
const write = (p,v) => {fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v,null,2)+'\n');};
const git = (...args) => cp.execFileSync('git',args,{cwd:root,encoding:'utf8'}).trim();
fs.mkdirSync(target,{recursive:true});fs.mkdirSync(evidence,{recursive:true});
const mode=process.argv[2];
if(!['integrity','restore'].includes(mode)&&fs.existsSync(path.join(evidence,'manifest.sha256')))throw Error('Evidence is frozen: set fresh P8_FIX_EVIDENCE_DIR and P8_FIX_TARGET_DIR, then protect first');
if(mode==='protect') {
  if(fs.existsSync(path.join(evidence,'baseline.json')))throw Error('Already protected');
  const files=[...walk(path.join(root,'docs/acceptance-evidence')).filter(p=>!p.startsWith(evidence)),...walk(path.join(root,'tests/fixtures/documents'))];
  const wip=['ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P6RagQualityBaselinePostgresTest.java','ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P8AgentRagCrossLayerPostgresTest.java','docs/README.md'];
  for(const p of [...wip.map(p=>path.join(root,p)),...walk(path.join(root,'docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01'))]) {
    const dest=path.join(target,'protected',rel(p));fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(p,dest);
  }
  for(const p of walk(path.join(root,'ai-collab-backend/target/retrieval-baseline'))) {
    const dest=path.join(target,'prior-output',path.relative(path.join(root,'ai-collab-backend/target/retrieval-baseline'),p));fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(p,dest);
  }
  write(path.join(evidence,'source-manifest.json'),Object.fromEntries(files.map(p=>[rel(p),hash(p)])));
  write(path.join(evidence,'baseline.json'),{capturedAt:new Date().toISOString(),head:git('rev-parse','HEAD'),branch:git('branch','--show-current'),remote:git('ls-remote','origin','refs/heads/dev','refs/heads/main'),worktree:git('status','--short'),staged:git('diff','--cached','--stat'),ownership:'Existing P8 test/evidence/navigation WIP identified by content and P8 source hashes; preserved separately, not authored by this task.',wipHashes:Object.fromEntries(wip.map(p=>[p,hash(path.join(root,p))])),externalChatCalls:0});
  console.log('Protected original P8, fixtures, evidence and prior target outputs');process.exit(0);
}
if(mode==='integrity') {
  const files=JSON.parse(fs.readFileSync(path.join(evidence,'source-manifest.json')));
  const changed=Object.entries(files).filter(([p,h])=>!fs.existsSync(path.join(root,p))||hash(path.join(root,p))!==h).map(([p])=>p);
  if(!fs.existsSync(path.join(evidence,'manifest.sha256')))write(path.join(evidence,'historical-integrity.json'),{filesChecked:Object.keys(files).length,changed});
  if(changed.length)throw Error('Historical mutation');
  console.log('Historical hashes unchanged');process.exit(0);
}
if(mode==='restore') {
 const original=path.join(target,'prior-output');
 for(const p of walk(original)) {const dest=path.join(root,'ai-collab-backend/target/retrieval-baseline',path.relative(original,p));fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(p,dest);}
 if(fs.existsSync(path.join(target,'prior-p8')))fs.cpSync(path.join(target,'prior-p8'),path.join(root,'ai-collab-backend/target/p8-agent-rag-e2e-quality-01'),{recursive:true});
 console.log('Prior diagnostic outputs restored');process.exit(0);
}
if(mode==='integration' && !fs.existsSync(path.join(target,'prior-p8')))
 fs.cpSync(path.join(root,'ai-collab-backend/target/p8-agent-rag-e2e-quality-01'),path.join(target,'prior-p8'),{recursive:true});
const suites={baseline:'P6RagQualityBaselinePostgresTest',experiment:'P8RagQualityFixExperimentPostgresTest',integration:'P8AgentRagCrossLayerPostgresTest',regression:'KnowledgeContextBuilderTest,CrossDocumentSourceSelectorTest,RetrievalCoverageTest,RetrievalQualityHardGatePostgresTest,AgentResearchQualityPostgresTest,AgentResearchCitationMetricsTest,KnowledgeCitationValidatorTest,FrozenKnowledgeQualityRegressionTest,P6QaSourceSelectionExperimentTest,CrossDocumentSourceSelectorStrategyExperimentTest,AgentRetrievalQualityPostgresTest',verify:null};
suites.citation='AgentFinalCitationContractPostgresTest';
suites.regression+=',AgentFinalCitationContractPostgresTest,P8EvidenceStrategyCounterexampleTest';
if(!(mode in suites))throw Error('Unknown mode');
const env={...process.env};
for(const p of walk(path.join(root,'ai-collab-backend/src/test/java')).filter(p=>p.endsWith('.java')))for(const m of fs.readFileSync(p,'utf8').matchAll(/EnabledIfEnvironmentVariable\s*\(\s*named\s*=\s*"([^"]+)"/g))delete env[m[1]];
for(const k of Object.keys(env))if(/^(AI_|GEMINI_|OPENAI_|ANTHROPIC_|ZEN_|KILO_)/.test(k))delete env[k];
if(['baseline','experiment','integration','regression'].includes(mode))env.AI_REAL_OLLAMA_RETRIEVAL='true';
const backendTarget=path.relative(path.join(root,'ai-collab-backend'),target).replaceAll('\\','/');
if(!/^[A-Za-z0-9_./-]+$/.test(backendTarget))throw Error('Use a controlled target path without spaces or shell metacharacters');
const args=['-o','-B','-ntp',...(suites[mode]?[`-Dtest=${suites[mode]}`]:[]),`-Dp8.fix.outputDir=${backendTarget}`,`-Drag.baseline.output=${backendTarget}/${mode}-baseline.json`,`-Drag.baseline.sha=${git('rev-parse','HEAD')}`,mode==='verify'?'verify':'test'];
const startedAt=new Date().toISOString();
if(fs.existsSync(path.join(target,`${mode}-output.txt`))) {
 const backup=path.join(target,'prior-runs',`${mode}-${Date.now()}`);fs.mkdirSync(backup,{recursive:true});
 fs.copyFileSync(path.join(target,`${mode}-output.txt`),path.join(backup,'output.txt'));
 if(fs.existsSync(path.join(target,`${mode}-surefire`)))fs.cpSync(path.join(target,`${mode}-surefire`),path.join(backup,'surefire'),{recursive:true});
 console.log('Previous attempt preserved: '+rel(backup));
}
const output=fs.openSync(path.join(target,`${mode}-output.txt`),'w');
const child=cp.spawn('cmd.exe',['/d','/c',`mvnw.cmd ${args.join(' ')}`],{cwd:path.join(root,'ai-collab-backend'),env,stdio:['ignore',output,output],windowsHide:true});
console.log(`Started ${mode}`);
child.on('exit',exitCode=>{
 fs.closeSync(output);
 const processExitCode=exitCode;
 const buildCompleted=fs.readFileSync(path.join(target,`${mode}-output.txt`),'utf8').includes('BUILD SUCCESS');
 if(exitCode===0&&!buildCompleted)exitCode=1;
 if(mode==='integration' && exitCode===0)fs.copyFileSync(path.join(root,'ai-collab-backend/target/p8-agent-rag-e2e-quality-01/integration-results.json'),path.join(target,'integration-results.json'));
 const dir=path.join(target,`${mode}-surefire`);fs.mkdirSync(dir,{recursive:true});
 for(const p of walk(path.join(root,'ai-collab-backend/target/surefire-reports')))if(fs.statSync(p).mtimeMs>=Date.parse(startedAt)-1000)fs.copyFileSync(p,path.join(dir,path.basename(p)));
 const file=path.join(evidence,'regression-results.json');const rows=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):[];
 rows.push({mode,command:`mvnw.cmd ${args.join(' ')}`,startedAt,endedAt:new Date().toISOString(),processExitCode,buildCompleted,exitCode,output:rel(path.join(target,`${mode}-output.txt`)),reports:rel(dir)});write(file,rows);console.log(`${mode}: ${exitCode}`);process.exitCode=exitCode;
});
