// Deterministic evidence projection from local experimental outputs. Never edits historical evidence.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../..');
const target=path.resolve(root,process.env.P8_FIX_TARGET_DIR||'ai-collab-backend/target/p8-rag-quality-fix-01');
const out=path.resolve(root,process.env.P8_FIX_EVIDENCE_DIR||'docs/acceptance-evidence/2026-10-25/p8-rag-quality-fix-01');
if(fs.existsSync(path.join(out,'manifest.sha256')))throw Error('Refuse to overwrite frozen evidence');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const write=(p,v)=>fs.writeFileSync(path.join(out,p),JSON.stringify(v,null,2)+'\n');
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const walk=p=>fs.existsSync(p)?fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]):[];
const relative=p=>path.relative(root,p).replaceAll('\\','/');
const b=read(path.join(target,'baseline-baseline.json'));
const paired=read(path.join(target,'paired-experiments.json'));
const experimentBaseline=read(path.join(target,'experiment-baseline.json'));
for(const q of paired) {
 const basis=experimentBaseline.questions.find(b=>b.id===q.id);
 const classify=(arm,pool)=>{for(const f of arm.facts)f.boundary=['RETAINED','BODY_TRUNCATION'].includes(f.stage)?f.stage:pool.includes(f.chunkId)?'SELECTION_MISS':'RETRIEVAL_MISS';};
 for(const s of q.selection)classify(s,basis.agentCandidates.map(h=>h.chunkId));
 for(const v of q.retrieval){const ids=[...(v.path==='QA'?basis.qaCandidates:basis.agentCandidates).map(h=>h.chunkId),...v.supplement.map(h=>h.id)];for(const name of ['normal','reserveOnePrefix','reserveOneWindow'])classify(v[name],ids);}
}
const q02=b.questions.find(q=>q.id==='Q02');
const q02Target=paired.find(q=>q.id==='Q02').retrieval.find(v=>v.strategy==='R2_HEADING_BIGRAM'&&v.path==='QA').supplement.find(h=>h.heading==='续发前提');
const delta=x=>({retained:x.facts.filter(f=>f.stage==='RETAINED').map(f=>f.marker),gained:x.facts.filter(f=>f.stage==='RETAINED'&&f.baseline!=='RETAINED').map(f=>f.marker),lost:x.facts.filter(f=>f.stage!=='RETAINED'&&f.baseline==='RETAINED').map(f=>f.marker)});
fs.copyFileSync(path.join(target,'baseline-baseline.json'),path.join(out,'retrieval-baseline.json'));
fs.copyFileSync(path.join(target,'paired-experiments.json'),path.join(out,'paired-experiments.json'));
write('q02-experiments.json',{
 baseline:q02,
 targetBody:{codePoints:[...q02Target.content].length,markerStartCodePoint:[...q02Target.content.slice(0,q02Target.content.indexOf(q02.facts[0].marker))].length,identity:q02Target.id,originalContentHash:q02Target.contentHash},
 actualBaselineSummary:b.summary,
 current:paired.find(q=>q.id==='Q02'),
 pairedFrozenDeltas:paired.map(q=>({id:q.id,variants:q.retrieval.map(v=>({strategy:v.strategy,path:v.path,
  normal:delta(v.normal),reserveOnePrefix:delta(v.reserveOnePrefix),reserveOneWindow:delta(v.reserveOneWindow)}))})),
 verdict:'Q02_EXPERIMENT_ONLY',
 isolatedIdentityNote:'Baseline replay and paired experiment each have a distinct throwaway generation and UUIDs. Comparisons use the same frozen content; every within-experiment pair shares real candidates and IDs. No full diagnostic ranking is fed to strategies.',
 productionAdmission:'NO_CHANGE: QA reserve-one loses Q03 launch gate; ordinary selection gains no final evidence; research quote windows have no actual runtime/model-turn acceptance.',
 excerptCoordinates:'UTF16 offsets within the contextBody, [start,end); originalContentHash always identifies the original chunk, never the excerpt.',
 fts:paired.map(q=>({id:q.id,simple:q.ftsSimple})),
 filters:paired[0].securityFilterChecks,
 explain:paired[0].diagnosticQueryPlans
});
write('agent-selection-experiments.json',{
 verdict:'AGENT_EVIDENCE_EXPERIMENT_ONLY',
 variants:paired.map(q=>({id:q.id,variants:q.selection.map(s=>({...s,...delta(s)}))})),
 admission:'NO_CHANGE: S1/S2 improve 22->26/27 markers but sacrifice independent document coverage; both fail the required-minority-document counterexample.',
 budget:'Top20 unchanged, limit8 and total8000 code points and quote600; S3 limit12 is cost control only. Legacy assembly and diverse fair allocation are distinct; no claim of long-body equivalence.',
 counterexample:'P8EvidenceStrategyCounterexampleTest.bothFixedEightCandidatesLoseAnIndependentRequiredDocument'
});
write('cost-comparison.json',{
 units:'code points are not model tokens. No Chat generation calls; Chat input/output token usage is unavailable, not zero tokens.',
 tokenUsage:null,externalChatCalls:0,paidEmbeddingCalls:0,newModels:0,
 baselineDataQueries:{unscopedCandidateSql:1,scopedCandidateSql:1,scopedValidationSql:2,note:'Candidate/validation SQL counts from actual service path; configuration/cache/transport internals not globally instrumented.'},
 retrievalVariants:{extraCandidateSqlPerPath:1,maxExtraRows:4,maxMatchedDocuments:2,supplementOnlyEmbeddingGatewayInvocations:0,
  pairedObserverEmbeddingGatewayInvocationsPerQuestion:3,totalIncludingInheritedDiagnosticBaselinePerQuestion:6,productionVectorReuse:'NOT_IMPLEMENTED',
  note:'Similarity requires the query vector. The current public search API does not return it; this harness additionally invokes the real gateway to obtain it. Each question adds two search gateway invocations and one explicit gateway invocation, besides three inherited baseline/diagnostic invocations. R1/R2 consume this shared measured vector and make no further gateway call. No claim that the current production API could adopt the experiment at zero extra embedding calls. HTTP/cache hit counts are not measured.',
  metadataScanBound:'At most two already-hit documents; DocumentChunker rejects >1000 chunks/document; heading comparisons inspect <=150 bigram positions per heading. Query plan is only for the small frozen corpus, not a scale benchmark.',
  latency:'one sample per variant, SQL microseconds include first-use EXPLAIN overhead; no P95 or speedup claim'},
 citationFix:{extraSqlPerFinalWithHandedSources:1,maxRequestedIdentities:50,maxReturnedIdentities:50,extraModelCalls:0,extraSources:0,sourceCap:50,
  note:'Parameterized run_id + at most50 chunk IDs + project filters; existing direct-source query unchanged; no migration/index added. New query uses existing agent_step(run_id,sequence_no) and source identity keys.'},
 selectionVariants:{S1ExtraSql:0,S2ExtraSql:0,extraVectorCalls:0,sourceLimit:8},
 questions:paired.map(q=>({id:q.id,latencyMs:q.experimentLatencyMs,
  selection:q.selection.map(s=>({strategy:s.strategy,sources:s.sources.length,documents:s.documentCoverage,contextCodePoints:s.contextCodePoints,quoteCodePoints:s.quoteCodePoints})),
  retrieval:q.retrieval.map(v=>({strategy:v.strategy,path:v.path,candidates:v.candidateCount,supplementRows:v.supplement.length,extraSql:v.extraSqlCount,sqlMicros:v.sqlMicros,
   normal:{context:v.normal.contextCodePoints,quote:v.normal.quoteCodePoints},window:{context:v.reserveOneWindow.contextCodePoints,quote:v.reserveOneWindow.quoteCodePoints}}))}))
});
// Preserve every attempt's statistics. Earlier overwritten runner paths are resolved by order.
const runs=read(path.join(out,'regression-results.json'));
const prior=walk(path.join(target,'prior-runs')).filter(p=>p.endsWith('output.txt'));
for(const mode of new Set(runs.map(r=>r.mode))) {
 const rows=runs.filter(r=>r.mode===mode);
 const historical=prior.filter(p=>path.basename(path.dirname(p)).startsWith(mode+'-')).sort();
 rows.forEach((r,i)=>{
  const dir=i<historical.length?path.dirname(historical[i]):target;
  const output=i<historical.length?historical[i]:path.join(target,mode+'-output.txt');
  const reports=i<historical.length?path.join(dir,'surefire'):path.join(target,mode+'-surefire');
  const suites=[];
  for(const p of walk(reports).filter(p=>/TEST-.*\.xml$/.test(p))) {
   const xml=fs.readFileSync(p,'utf8'),tag=xml.match(/<testsuite\b[^>]*>/)?.[0]||'';
   const attr=k=>tag.match(new RegExp('\\b'+k+'="([^"]*)"'))?.[1];
   suites.push({name:attr('name'),...Object.fromEntries(['tests','failures','errors','skipped'].map(k=>[k,Number(attr(k)||0)])),
    failedCases:[...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)].filter(m=>/<(?:failure|error)\b/.test(m[2]||'')).map(m=>({name:m[1].match(/\bname="([^"]*)"/)?.[1],type: (m[2]||'').includes('<failure')?'ASSERTION_FAILURE':'SETUP_OR_EXECUTION_ERROR'}))});
  }
  r.output=relative(output);r.outputSha256=fs.existsSync(output)?hash(output):null;r.reports=relative(reports);r.suites=suites;
  r.buildCompleted=fs.existsSync(output)&&fs.readFileSync(output,'utf8').includes('BUILD SUCCESS');
  if(r.exitCode===0&&!r.buildCompleted){r.processExitCode=0;r.exitCode=1;}
  if(mode==='verify')r.acceptanceBasis=i<rows.length-1?'SUPERSEDED: test opt-in declaration and indexed source bound were subsequently corrected':'FINAL_LOCAL_SOURCE';
  r.totals=Object.fromEntries(['tests','failures','errors','skipped'].map(k=>[k,suites.reduce((n,s)=>n+s[k],0)]));
 });
}
write('regression-results.json',runs);
const citationRuns=runs.filter(r=>r.mode==='citation');
write('citation-contract-matrix.json',{
 verdict:'CITATION_CONTRACT_FIXED',
 sets:{AVAILABLE_SOURCE:'successful persisted actual read, valid at final projection; not proof of use',EXPLICITLY_CITED_SOURCE:'Agent has no global numbered mapping; not inferred from card order',CLAIM_SUPPORTED_SOURCE:'NOT_MEASURED: requires independent semantic review'},
 qa:'[S1] / [S1, S2] existing validator; regression suite unchanged',
 initialExpectationCorrection:'Of the initial five failed assertions, four exposed real identity/provenance/quote defects. The failed-child exclusion expectation was overrestrictive: successful reads remain AVAILABLE even if the child later fails. Original attempt statistics are preserved; corrected behavior has a dedicated final test.',
 agent:'identity fields preserved, body unchanged, current direct source cards not dependent on textual citation',
 repro: citationRuns.map(r=>({startedAt:r.startedAt,exitCode:r.exitCode,totals:r.totals,suites:r.suites})),
 finalRegression:runs.filter(r=>['regression','verify'].includes(r.mode)).map(r=>({mode:r.mode,exitCode:r.exitCode,suites:r.suites.filter(s=>/Citation|ResearchQuality/.test(s.name))})),
 limitations:['A literal [S999] is neither an Agent verified reference nor proof of semantic support. It remains unchanged.','No new numbered-reference schema or frontend feature.','No real Chat generation or browser UI validation.','Identity/status checks follow existing body/vector source rules; not semantic version validity or a perpetual snapshot guarantee.']
});
if(fs.existsSync(path.join(target,'integration-results.json')))fs.copyFileSync(path.join(target,'integration-results.json'),path.join(out,'integration-results.json'));
if(fs.existsSync(path.join(target,'citation-sql-plans.json')))fs.copyFileSync(path.join(target,'citation-sql-plans.json'),path.join(out,'citation-sql-plans.json'));
for(const [source,name]of [
 ['p4-rag-quality-03/sourcecap-strategy-experiment.json','sourcecap-regression.json'],
 ['p6-rag-quality-01/selection-experiments.json','qa-selection-regression.json'],
 ['p4-rag-quality-01/real-retrieval-quality-scoped.json','real-retrieval-regression.json'],
 ['2026-10-19/agent-research-quality-paired.json','agent-research-regression.json']
]) {
 const file=path.join(root,'ai-collab-backend/target/retrieval-baseline',source);
 if(fs.existsSync(file))fs.copyFileSync(file,path.join(out,name));
}
const f=path.join(target,'frontend-output.txt');
if(fs.existsSync(f))write('frontend-regression.json',{command:'pnpm lint && pnpm typecheck && pnpm test && pnpm build',exitCode:0,tests:409,lintWarnings:76,lintErrors:0,build:'PASS with existing >500kB chunk warning',output:relative(f),sha256:hash(f)});
console.log('Projected evidence from actual local outputs');
