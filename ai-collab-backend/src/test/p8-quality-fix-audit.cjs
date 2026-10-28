const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=path.resolve(__dirname,'../../..');
const dir=path.resolve(root,process.env.P8_FIX_EVIDENCE_DIR||'docs/acceptance-evidence/2026-10-25/p8-rag-quality-fix-01');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
const errors=[];
const sourceRef=process.env.P8_FIX_SOURCE_REF;
if(sourceRef&&!/^[a-f0-9]{40}$/.test(sourceRef))throw Error('Source reference must be an exact immutable commit SHA');
let sourceCache;
const sourceBytes=p=>{
 if(!sourceRef)return fs.readFileSync(p);
 if(!sourceCache){
  const files=cp.execFileSync('git',['ls-tree','-r','--name-only',sourceRef,'ai-collab-backend/src'],{cwd:root,encoding:'utf8'}).trim().split('\n').filter(p=>p.endsWith('.java'));
  const bytes=cp.execFileSync('git',['cat-file','--batch'],{cwd:root,input:files.map(p=>sourceRef+':'+p+'\n').join(''),maxBuffer:100000000});
  sourceCache=new Map();let offset=0;
  for(const name of files){const end=bytes.indexOf(10,offset),size=Number(bytes.subarray(offset,end).toString().split(' ')[2]);if(!Number.isFinite(size))throw Error('Missing source '+name);sourceCache.set(name,bytes.subarray(end+1,end+1+size));offset=end+size+2;}
 }
 const bytes=sourceCache.get(path.relative(root,p).replaceAll('\\','/'));if(!bytes)throw Error('Source not in revision '+p);return bytes;
};
const manifestFile=path.join(dir,'manifest.sha256');
const sealed=fs.existsSync(manifestFile);
if(sealed)for(const line of fs.readFileSync(manifestFile,'utf8').trim().split(/\r?\n/)) {
 const m=line.match(/^([a-f0-9]{64})  (.*)$/);
 if(!m||!fs.existsSync(path.join(root,m[2]))||hash(path.join(root,m[2]))!==m[1])errors.push('Frozen manifest mismatch '+line);
}
const historical=read(path.join(dir,'source-manifest.json'));
for(const [p,h]of Object.entries(historical))if(!fs.existsSync(path.join(root,p))||hash(path.join(root,p))!==h)errors.push('Historical mutation '+p);
const runs=read(path.join(dir,'regression-results.json'));
for(const mode of ['baseline','experiment','regression','integration','verify']){
 const r=runs.filter(r=>r.mode===mode).at(-1);
 if(!r||r.exitCode!==0||!r.totals||r.totals.failures||r.totals.errors)errors.push('Missing successful final run '+mode);
 else if(mode!=='verify'&&r.totals.skipped)errors.push('Unexpected skip '+mode);
}
const verify=runs.filter(r=>r.mode==='verify').at(-1);
if(verify){
 const citation=verify.suites.find(s=>s.name.endsWith('.AgentFinalCitationContractPostgresTest'));
 if(!citation||citation.tests!==11||citation.skipped)errors.push('Citation container did not run all11');
 const sources=sourceRef?cp.execFileSync('git',['ls-tree','-r','--name-only',sourceRef,'ai-collab-backend/src'],{cwd:root,encoding:'utf8'}).trim().split('\n').filter(p=>p.endsWith('.java')).map(p=>path.join(root,p)):walk(path.join(root,'ai-collab-backend/src')).filter(p=>p.endsWith('.java'));
 if(sealed) {
  const expected=read(path.join(dir,'tested-source-hashes.json')).lfNormalizedHashes;
  const actual=Object.fromEntries(sources.map(p=>[path.relative(root,p).replaceAll('\\','/'),crypto.createHash('sha256').update(sourceBytes(p).toString('utf8').replaceAll('\r\n','\n')).digest('hex')]));
  for(const p of new Set([...Object.keys(expected),...Object.keys(actual)]))if(expected[p]!==actual[p])errors.push('Tested Java source changed '+p);
 } else {
  const changedAfterStart=sources.filter(p=>fs.statSync(p).mtimeMs>Date.parse(verify.startedAt));
  if(changedAfterStart.length)errors.push('Java source changed after verify start: '+changedAfterStart.join(','));
 }
 if(!sealed)fs.writeFileSync(path.join(dir,'tested-source-hashes.json'),JSON.stringify({verifyStartedAt:verify.startedAt,
  localByteHashes:Object.fromEntries(sources.map(p=>[path.relative(root,p).replaceAll('\\','/'),hash(p)])),
  lfNormalizedHashes:Object.fromEntries(sources.map(p=>[path.relative(root,p).replaceAll('\\','/'),crypto.createHash('sha256').update(fs.readFileSync(p,'utf8').replaceAll('\r\n','\n')).digest('hex')]))},null,2)+'\n');
}
const documents=[...walk(dir),...['docs/README.md','docs/design/agent-source-citation-contract.md','docs/archive/research/p8-rag-quality-fix-01-research-20261025.md','docs/archive/reports/p8-rag-quality-fix-01-delivery-20261025.md'].map(p=>path.join(root,p))];
for(const p of documents){
 if(p.endsWith('.json'))try{read(p);}catch{errors.push('Invalid JSON '+p);}
 if(p.endsWith('.md'))for(const m of fs.readFileSync(p,'utf8').matchAll(/\]\(([^)]+)\)/g)){
  const ref=m[1].split('#')[0];if(!ref||/^(?:https?:|mailto:)/.test(ref))continue;
  const resolved=path.resolve(path.dirname(p),ref);
  if(!sealed&&resolved===path.join(dir,'audit-results.json'))continue;
  if(!fs.existsSync(resolved))errors.push('Broken local link '+p+' '+ref);
 }
}
const inherited=fs.readFileSync(path.join(root,'docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/manifest.sha256'),'utf8').trim().split(/\r?\n/);
for(const line of inherited){const m=line.match(/^([a-f0-9]{64})  (.*)$/);const actual=m&&m[2].endsWith('.java')?crypto.createHash('sha256').update(sourceBytes(path.join(root,m[2]))).digest('hex'):m?hash(path.join(root,m[2])):null;if(!m||actual!==m[1])errors.push('Inherited manifest mismatch '+line);}
const q02=read(path.join(dir,'q02-experiments.json'));
if(q02.targetBody.codePoints!==808||q02.targetBody.markerStartCodePoint!==785)errors.push('Q02 position drift');
const scope=read(path.join(dir,'paired-experiments.json'));
if(scope.length!==16)errors.push('Not all16 frozen questions');
const result={checkedAt:new Date().toISOString(),sourceRef:sourceRef||'WORKTREE',status:errors.length?'FAIL':'PASS',errors,historicalFiles:Object.keys(historical).length,inheritedManifestFiles:inherited.length,verifyTotals:verify?.totals,
 limitations:['CI receipt is independently queried after the final commit; ordinary CI skips local Ollama by design.','No real Chat generation or semantic-support acceptance.']};
if(!sealed)fs.writeFileSync(path.join(dir,'audit-results.json'),JSON.stringify(result,null,2)+'\n');
if(process.argv[2]==='seal') {
 if(sealed)throw Error('Already sealed');
 if(errors.length)throw Error('Cannot seal a failing audit');
 fs.writeFileSync(manifestFile,walk(dir).sort().map(p=>hash(p)+'  '+path.relative(root,p).replaceAll('\\','/')).join('\n')+'\n');
}
console.log(JSON.stringify(result));if(errors.length)process.exit(1);
