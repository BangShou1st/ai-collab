#!/usr/bin/env node
/**
 * Summarizes JMeter CSV JTL files and evaluates the frozen perf gates.
 * Only aggregate CSVs are produced; raw JTLs stay in the caller's results directory.
 */
import fs from 'node:fs';
import path from 'node:path';

const resultsDir = path.resolve(process.argv[2] ?? '.');
if (!fs.existsSync(resultsDir)) {
  console.error(`results directory not found: ${resultsDir}`);
  process.exit(2);
}

const REQUIRED = [
  'smoke',
  'read-baseline-b1',
  'read-baseline-b2',
  'mixed-baseline',
  'spike',
  'soak',
  'ai-stub',
  'sse-smoke',
];

const parseJtl = (file) => {
  const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
  const lines = text.split(/\r?\n/).filter((line) => line.length > 0);
  if (lines.length < 2) return [];
  const clean = (value) => String(value).replace(/^"|"$/g, '');
  const header = lines.shift().split(',').map(clean);
  return lines.map((line) => {
    const values = line.split(',');
    const row = {};
    header.forEach((key, index) => {
      row[key] = clean(values[index] ?? '');
    });
    return row;
  });
};

const numeric = (value) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
};

const pct = (values, percentile) => {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil((percentile / 100) * sorted.length) - 1);
  return sorted[index];
};

const round = (value, digits = 2) => value === null ? '' : Number(value.toFixed(digits));

const summarize = (id, rows) => {
  const samples = rows.length;
  const errors = rows.filter((row) => row.success !== 'true').length;
  const http5xx = rows.filter((row) => {
    const code = numeric(row.responseCode);
    return code !== null && code >= 500 && code < 600;
  }).length;
  const elapsed = rows.map((row) => numeric(row.elapsed)).filter((value) => value !== null);
  const overhead = rows
    .map((row) => numeric(row.overheadMs))
    .filter((value) => value !== null && value >= 0);
  const provider = rows
    .map((row) => numeric(row.providerLatencyMs))
    .filter((value) => value !== null && value >= 0);
  const errorRate = samples === 0 ? null : errors / samples;
  const reasons = [];
  if (samples === 0) reasons.push('no samples');
  if (errorRate !== null && errorRate > 0.01) reasons.push(`errorRate ${round(errorRate * 100)}% > 1%`);
  if (http5xx > 0) reasons.push(`${http5xx} HTTP 5xx`);
  if (id === 'ai-stub') {
    const overheadP95 = pct(overhead, 95);
    if (overheadP95 === null) reasons.push('AI overhead missing');
    else if (overheadP95 > 800) reasons.push(`AI overhead P95 ${round(overheadP95)}ms > 800ms`);
  }
  return {
    scenario: id,
    samples,
    errors,
    errorRatePct: errorRate === null ? '' : round(errorRate * 100, 3),
    http5xx,
    p50Ms: round(pct(elapsed, 50)),
    p95Ms: round(pct(elapsed, 95)),
    p99Ms: round(pct(elapsed, 99)),
    maxMs: elapsed.length === 0 ? '' : round(elapsed.reduce((max, value) => Math.max(max, value), 0)),
    overheadP95Ms: id === 'ai-stub' ? round(pct(overhead, 95)) : '',
    providerP95Ms: id === 'ai-stub' ? round(pct(provider, 95)) : '',
    gate: samples === 0 ? 'NOT_RUN' : reasons.length === 0 ? 'PASS' : 'FAIL',
    notes: samples === 0 ? 'JTL missing or empty' : reasons.join('; '),
  };
};

const labelStats = (rows) => {
  const groups = new Map();
  for (const row of rows) {
    const key = row.label;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(row);
  }
  return [...groups.entries()].map(([label, group]) => {
    const elapsed = group.map((row) => numeric(row.elapsed)).filter((value) => value !== null);
    return {
      label,
      samples: group.length,
      errors: group.filter((row) => row.success !== 'true').length,
      p50Ms: round(pct(elapsed, 50)),
      p95Ms: round(pct(elapsed, 95)),
      p99Ms: round(pct(elapsed, 99)),
    };
  }).sort((a, b) => a.label.localeCompare(b.label));
};

const files = fs.readdirSync(resultsDir).filter((file) => file.endsWith('.jtl'));
const scenarioRows = new Map();
const labelRows = [];
for (const file of files) {
  const id = path.basename(file, '.jtl');
  const rows = parseJtl(path.join(resultsDir, file));
  scenarioRows.set(id, rows);
  for (const row of labelStats(rows)) {
    labelRows.push({ scenario: id, ...row });
  }
}

const summary = REQUIRED.map((id) => summarize(id, scenarioRows.get(id) ?? []));

const writeCsv = (file, rows, columns) => {
  const lines = [columns.join(',')];
  for (const row of rows) {
    lines.push(columns.map((column) => {
      const value = row[column] ?? '';
      return String(value).replaceAll('"', '""');
    }).map((value) => `"${value}"`).join(','));
  }
  fs.writeFileSync(path.join(resultsDir, file), `${lines.join('\n')}\n`, 'utf8');
};

writeCsv('summary.csv', summary, [
  'scenario', 'samples', 'errors', 'errorRatePct', 'http5xx',
  'p50Ms', 'p95Ms', 'p99Ms', 'maxMs', 'overheadP95Ms', 'providerP95Ms',
  'gate', 'notes',
]);

writeCsv('labels.csv', labelRows, [
  'scenario', 'label', 'samples', 'errors', 'p50Ms', 'p95Ms', 'p99Ms',
]);

const compareScenario = (leftId, rightId, outputFile) => {
  const left = scenarioRows.get(leftId) ?? [];
  const right = scenarioRows.get(rightId) ?? [];
  const leftByLabel = new Map(labelStats(left).map((row) => [row.label, row]));
  const rightByLabel = new Map(labelStats(right).map((row) => [row.label, row]));
  const rows = [];
  let maxDegradation = -Infinity;
  let worstLabel = '';
  for (const [label, leftRow] of leftByLabel) {
    const rightRow = rightByLabel.get(label);
    if (!rightRow) continue;
    const leftP95 = numeric(leftRow.p95Ms);
    const rightP95 = numeric(rightRow.p95Ms);
    if (leftP95 === null || rightP95 === null || leftP95 <= 0) continue;
    const degradation = ((rightP95 - leftP95) / leftP95) * 100;
    if (degradation > maxDegradation) {
      maxDegradation = degradation;
      worstLabel = label;
    }
    rows.push({
      label,
      leftP95Ms: round(leftP95),
      rightP95Ms: round(rightP95),
      degradationPct: round(degradation),
      verdict: degradation > 20 ? 'REGRESSION' : 'OK',
    });
  }
  writeCsv(outputFile, rows, ['label', 'leftP95Ms', 'rightP95Ms', 'degradationPct', 'verdict']);
  return { maxDegradation: Number.isFinite(maxDegradation) ? maxDegradation : null, worstLabel, count: rows.length };
};

const baseline = compareScenario('read-baseline-b1', 'read-baseline-b2', 'comparison-baseline.csv');
const mixed = compareScenario('read-baseline-b2', 'mixed-baseline', 'comparison-mixed-vs-read.csv');

const gateRows = [
  {
    gate: 'error_rate',
    scope: 'all scenarios',
    requirement: '<= 1%',
    measured: summary.map((row) => `${row.scenario}:${row.errorRatePct}%`).join(' '),
    verdict: summary.every((row) => row.samples > 0 && Number(row.errorRatePct) <= 1) ? 'PASS' : 'FAIL',
  },
  {
    gate: 'http_5xx',
    scope: 'all scenarios',
    requirement: '0 unexpected 5xx',
    measured: summary.reduce((sum, row) => sum + Number(row.http5xx || 0), 0),
    verdict: summary.every((row) => Number(row.http5xx || 0) === 0) ? 'PASS' : 'FAIL',
  },
  {
    gate: 'ai_stub_overhead_p95',
    scope: 'ai-stub',
    requirement: '<= 800 ms',
    measured: summary.find((row) => row.scenario === 'ai-stub')?.overheadP95Ms ?? 'NOT_RUN',
    verdict: (() => {
      const row = summary.find((item) => item.scenario === 'ai-stub');
      return row && row.gate === 'PASS' && Number(row.overheadP95Ms) <= 800 ? 'PASS' : 'FAIL';
    })(),
  },
  {
    gate: 'baseline_p95_degradation',
    scope: 'read-baseline-b2 vs read-baseline-b1',
    requirement: '<= 20%',
    measured: baseline.maxDegradation === null ? 'NOT_RUN' : `${round(baseline.maxDegradation)}% (${baseline.worstLabel})`,
    verdict: baseline.maxDegradation !== null && baseline.maxDegradation <= 20 ? 'PASS' : 'FAIL',
  },
];

writeCsv('gate-summary.csv', gateRows, ['gate', 'scope', 'requirement', 'measured', 'verdict']);

console.log('scenario       samples errors  err%  5xx   p50    p95    p99    gate');
for (const row of summary) {
  console.log([
    row.scenario.padEnd(15),
    String(row.samples).padStart(7),
    String(row.errors).padStart(6),
    String(row.errorRatePct).padStart(6),
    String(row.http5xx).padStart(4),
    String(row.p50Ms).padStart(6),
    String(row.p95Ms).padStart(6),
    String(row.p99Ms).padStart(6),
    row.gate,
  ].join(' '));
}
console.log(`\nbaseline comparison: max P95 degradation ${baseline.maxDegradation === null ? 'NOT_RUN' : `${round(baseline.maxDegradation)}%`} at ${baseline.worstLabel || 'n/a'}`);
console.log(`mixed-vs-read diagnostic: max P95 degradation ${mixed.maxDegradation === null ? 'NOT_RUN' : `${round(mixed.maxDegradation)}%`} at ${mixed.worstLabel || 'n/a'}`);
console.log('gate summary:', gateRows.map((row) => `${row.gate}=${row.verdict}`).join(', '));

const failed = gateRows.some((row) => row.verdict === 'FAIL');
process.exit(failed ? 1 : 0);
