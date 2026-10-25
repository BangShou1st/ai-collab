const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=process.cwd(),dir=path.join(root,process.env.P8_EVIDENCE_DIR||'docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01');
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
const errors=[];
for(const line of fs.readFileSync(path.join(dir,'manifest.sha256'),'utf8').trim().split(/\r?\n/)){
  const [,expected,name]=line.match(/^([a-f0-9]{64})  (.*)$/)||[];
  if(!name||!fs.existsSync(path.join(root,name))||hash(path.join(root,name))!==expected)errors.push('Hash mismatch '+name);
}
for(const f of walk(dir)){
  if(f.endsWith('.json'))try{JSON.parse(fs.readFileSync(f,'utf8'));}catch{errors.push('Invalid JSON '+f);}
  if(f.endsWith('.md'))for(const m of fs.readFileSync(f,'utf8').matchAll(/\]\(([^)]+)\)/g)){
    const ref=m[1].split('#')[0];
    if(!ref||/^https?:|^mailto:/.test(ref))continue;
    if(!fs.existsSync(path.resolve(path.dirname(f),ref)))errors.push('Broken local link '+f+' '+ref);
  }
}
const historical=JSON.parse(fs.readFileSync(path.join(dir,'historical-hashes.json')));
for(const [f,h]of Object.entries(historical))if(!fs.existsSync(path.join(root,f))||hash(path.join(root,f))!==h)errors.push('Historical mutation '+f);
const metrics=JSON.parse(fs.readFileSync(path.join(dir,'metrics.json')));
const matrix=JSON.parse(fs.readFileSync(path.join(dir,'test-matrix.json')));
for(const r of matrix.filter(r=>!r.plannedOnly)){
  if(!fs.existsSync(path.join(root,r.output)))errors.push('Missing raw output '+r.id);
  if(r.id!=='integration-attempt1'&&!r.suites.length)errors.push('Missing actual test suites '+r.id);
}
if(metrics.agent.scenarios!==16)errors.push('Expected all frozen scenarios');
if(metrics.cost.externalChatRequests!==0)errors.push('Unexpected external model request');
const production=cp.execFileSync('git',['diff','--name-only','--','ai-collab-backend/src/main','ai-collab-frontend','ai-collab-deploy'],{encoding:'utf8'}).trim();
if(production)errors.push('Unexpected production changes '+production);
console.log(JSON.stringify({auditStatus:errors.length?'FAIL':'PASS',errors,files:walk(dir).length,historicalFiles:Object.keys(historical).length,verdict:metrics.verdict,verify:matrix.find(r=>r.id==='verify')?.totals},null,2));
process.exitCode=errors.length?1:0;
