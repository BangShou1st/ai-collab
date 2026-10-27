// P10-02（8 题）与 P10-03（同题复测）对比 + 重复测量稳定性对比。
import { readFileSync, writeFileSync } from "node:fs";

const REPO = "E:/project/ai-collab";
const p02Raw = JSON.parse(readFileSync(`${REPO}/docs/acceptance-evidence/2026-10-26/p10-real-generation-02/generation-results.json`, "utf8"));
const p02 = Array.isArray(p02Raw) ? p02Raw : p02Raw.questions;
const p03 = JSON.parse(readFileSync(`${REPO}/ai-collab-backend/target/p10-real-generation-03/real/generation-results.json`, "utf8"));
const ledger03 = JSON.parse(readFileSync(`${REPO}/ai-collab-backend/target/p10-real-generation-03/real/model-call-ledger.json`, "utf8"));

const norm = s => String(s || "").replace(/\s+/g, "").replace(/[*_`~#]/g, "");
const map02 = new Map(p02.map(r => [r.id, r]));

const comparison = { batchPair: "p10-real-generation-02 vs p10-real-generation-03", questions: [], repeats: [] };
for (const row of p03.filter(r => r.runId.startsWith("Q") && map02.has(r.id))) {
  const prev = map02.get(row.id);
  const answerEqual = norm(prev.rawAnswer) === norm(row.rawAnswer);
  const prevHigh = (prev.mechanicalScore?.highSeveritySignals || []).length;
  const nowHigh = (row.mechanicalScore?.highSeveritySignals || []).length;
  comparison.questions.push({
    id: row.id,
    p02: { answerChars: (prev.rawAnswer || "").length, citations: prev.final?.citations?.length ?? null, highSeverity: prevHigh },
    p03: { answerChars: (row.rawAnswer || "").length, citations: row.final?.citations?.length ?? null, highSeverity: nowHigh },
    answerNormalizedEqual: answerEqual,
  });
}
// 重复测量稳定性：Q02 vs R1、Q04 vs R2、Q12 vs R3
for (const [q, r] of [["Q02", "R1"], ["Q04", "R2"], ["Q12", "R3"]]) {
  const a = p03.find(x => x.runId === q), b = p03.find(x => x.runId === r);
  comparison.repeats.push({
    pair: `${q}/${r}`, questionId: a.id,
    answerNormalizedEqual: norm(a.rawAnswer) === norm(b.rawAnswer),
    chars: [(a.rawAnswer || "").length, (b.rawAnswer || "").length],
    citations: [a.final?.citations?.length ?? 0, b.final?.citations?.length ?? 0],
  });
}
comparison.totals = {
  p03Requests: ledger03.calls.length,
  p03HttpReturned: ledger03.calls.filter(c => c.status === "HTTP_RETURNED").length,
  p03PromptTokens: ledger03.calls.reduce((a, c) => a + (c.usage?.promptTokenCount || 0), 0),
  p03CompletionTokens: ledger03.calls.reduce((a, c) => a + (c.usage?.candidatesTokenCount || 0), 0),
};
writeFileSync(`${REPO}/target/p10-real-generation-03/batch-comparison.json`, JSON.stringify(comparison, null, 2));
console.log(JSON.stringify(comparison, null, 1).slice(0, 3500));
