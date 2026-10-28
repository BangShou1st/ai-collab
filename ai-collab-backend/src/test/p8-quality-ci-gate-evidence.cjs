// CI follow-up: preserve the frozen 25 October evidence; append a new version-bound record.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../..');
const out=path.join(root,'docs/acceptance-evidence/2026-10-26/p8-rag-quality-fix-01-ci-gate');
const target=path.join(root,'ai-collab-backend/target/p8-rag-quality-fix-01-ci-gate');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const write=(name,v)=>fs.writeFileSync(path.join(out,name),JSON.stringify(v,null,2)+'\n');
const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const relative=p=>path.relative(root,p).replaceAll('\\','/');
if(fs.existsSync(path.join(out,'manifest.sha256')))throw Error('Follow-up already frozen');
const runs=read(path.join(out,'regression-results.json'));
for(const r of runs){
 r.suites=[];
 for(const p of walk(path.join(root,r.reports)).filter(p=>/TEST-.*\.xml$/.test(p))){
  const xml=fs.readFileSync(p,'utf8'),tag=xml.match(/<testsuite\b[^>]*>/)?.[0]||'';
  const attr=k=>tag.match(new RegExp('\\b'+k+'="([^"]*)"'))?.[1];
  r.suites.push({name:attr('name'),...Object.fromEntries(['tests','failures','errors','skipped'].map(k=>[k,Number(attr(k)||0)]))});
 }
 r.totals=Object.fromEntries(['tests','failures','errors','skipped'].map(k=>[k,r.suites.reduce((n,s)=>n+s[k],0)]));
 r.outputSha256=hash(fs.readFileSync(path.join(root,r.output)));
}
const verification=runs.filter(r=>r.mode==='verify').at(-1);
const integration=runs.filter(r=>r.mode==='integration').at(-1);
if(!verification||verification.exitCode!==0||!verification.buildCompleted||verification.totals.failures||verification.totals.errors)throw Error('Final verify incomplete');
if(!integration||integration.exitCode!==0||integration.totals.skipped)throw Error('Opt-in integration not executed');
const gates=['P6RagQualityBaselinePostgresTest','P8AgentRagCrossLayerPostgresTest','P8RagQualityFixExperimentPostgresTest'].map(name=>{
 const r=verification.suites.find(s=>s.name.endsWith('.'+name));
 if(!r||!r.tests||r.tests!==r.skipped)throw Error('Expected explicit default opt-in skip: '+name);
 return r;
});
const citation=verification.suites.find(s=>s.name.endsWith('.AgentFinalCitationContractPostgresTest'));
if(!citation||citation.tests!==11||citation.skipped)throw Error('Citation PG tests must actually run');
const rows=read(path.join(target,'integration-results.json'));
if(rows.length!==16)throw Error('Not all16 integrated questions');
const sources=walk(path.join(root,'ai-collab-backend/src')).filter(p=>p.endsWith('.java'));
if(sources.some(p=>fs.statSync(p).mtimeMs>Date.parse(verification.startedAt)))throw Error('Java changed after final verify start');
const normalized=Object.fromEntries(sources.map(p=>[relative(p),hash(fs.readFileSync(p,'utf8').replaceAll('\r\n','\n'))]));
const previous=read(path.join(root,'docs/acceptance-evidence/2026-10-25/p8-rag-quality-fix-01/tested-source-hashes.json')).lfNormalizedHashes;
const changes=[...new Set([...Object.keys(previous),...Object.keys(normalized)])].filter(p=>previous[p]!==normalized[p]);
const expected='ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P8AgentRagCrossLayerPostgresTest.java';
if(changes.length!==1||changes[0]!==expected)throw Error('Unexpected Java delta: '+changes.join(','));
const oldRef='d380b21e24aeffbfe85bb61bd9cd7c1f931c9e9f';
const originalP8Ref=read(path.join(root,'docs/acceptance-evidence/2026-10-25/p8-rag-quality-fix-01/git-delivery.json')).inheritedP8Commit;
const originalManifest=fs.readFileSync(path.join(root,'docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/manifest.sha256'),'utf8').trim().split(/\r?\n/);
for(const line of originalManifest){
 const [,digest,p]=line.match(/^([a-f0-9]{64})  (.*)$/);
 const bytes=p.endsWith('.java')?cp.execFileSync('git',['show',originalP8Ref+':'+p],{cwd:root,maxBuffer:10000000}):fs.readFileSync(path.join(root,p));
 if(hash(bytes)!==digest)throw Error('Original evidence/source revision mismatch '+p);
}
const protectedFiles=read(path.join(out,'source-manifest.json'));
const mutations=Object.entries(protectedFiles).filter(([p,h])=>hash(fs.readFileSync(path.join(root,p)))!==h).map(([p])=>p);
if(mutations.length)throw Error('Frozen evidence modified '+mutations.join(','));
write('regression-results.json',runs);
write('source-versions.json',{originalP8SourceRef:originalP8Ref,originalFixSourceRef:oldRef,originalManifestFiles:originalManifest.length,
 originalDataUnchanged:true,protectedFiles:Object.keys(protectedFiles).length,
 javaDeltaFromOriginalFix:changes,productionJavaDelta:[],
 explanation:'Historical source hashes bind the immutable original source revision; test gating evolves in a new commit. Original corpus and all frozen evidence bytes are unchanged.',
 finalVerifiedLfHashes:normalized});
write('gate-results.json',{status:'PASS',defaultVerify:verification.totals,defaultLocalModelGates:gates,
 mandatoryCitationTests:citation,optInIntegration:integration.totals,optInQuestions:rows.length,
 realChatCalls:0,productionJavaChanged:false,thresholdsRelaxed:false,
 finalCi:'Must query final Push/PR head SHA after push; receipt is supplied in final delivery and ignored target/final-ci.json.'});
fs.copyFileSync(path.join(target,'integration-results.json'),path.join(out,'integration-results.json'));
const files=walk(out).sort();
fs.writeFileSync(path.join(out,'manifest.sha256'),files.map(p=>hash(fs.readFileSync(p))+'  '+relative(p)).join('\n')+'\n');
console.log(JSON.stringify({status:'PASS',verify:verification.totals,gates:gates.map(g=>g.name),questions:rows.length,changedJava:changes}));
