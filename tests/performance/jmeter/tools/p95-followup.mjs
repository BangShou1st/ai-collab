#!/usr/bin/env node
/**
 * Follow-up analysis for the project-detail P95 regression.
 * Keeps the original FAIL gate untouched; this emits a separate re-measurement.
 *
 * Usage:
 *   node tools/p95-followup.mjs <baseline-b1.jtl> <baseline-b2.jtl> <followup-results-dir>
 */
import fs from 'node:fs';
import path from 'node:path';

const [b1Path, b2Path, followupDirArg] = process.argv.slice(2);
if (!b1Path || !b2Path || !followupDirArg) {
  console.error('usage: node p95-followup.mjs <baseline-b1.jtl> <baseline-b2.jtl> <followup-results-dir>');
  process.exit(2);
}
const followupDir = path.resolve(followupDirArg);
const outDir = followupDir;

const parseJtl = (file) => {
  const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
  const lines = text.split(/\r?\n/).filter((line) => line.length > 0);
  const clean = (value) => String(value).replace(/^"|"$/g, '');
  const header = lines.shift().split(',').map(clean);
  return lines.map((line) => {
    const values = line.split(',');
    const row = {};
    header.forEach((key, index) => { row[key] = clean(values[index] ?? ''); });
    return row;
  });
};

const pct = (sorted, percentile) => {
  if (sorted.length === 0) return null;
  const index = Math.min(sorted.length - 1, Math.ceil((percentile / 100) * sorted.length) - 1);
  return sorted[index];
};

let bootstrapSeed = 20261112;
const rand = () => {
  bootstrapSeed = (1103515245 * bootstrapSeed + 12345) & 0x7fffffff;
  return bootstrapSeed / 0x7fffffff;
};

const bootstrapP95 = (values, iterations = 1000) => {
  if (values.length === 0) return [null, null];
  const estimates = [];
  for (let i = 0; i < iterations; i++) {
    const sample = new Array(values.length);
    for (let j = 0; j < values.length; j++) sample[j] = values[Math.floor(rand() * values.length)];
    sample.sort((a, b) => a - b);
    estimates.push(pct(sample, 95));
  }
  estimates.sort((a, b) => a - b);
  return [pct(estimates, 2.5), pct(estimates, 97.5)];
};

const elapsed = (rows) => rows.map((row) => Number(row.elapsed)).filter((value) => Number.isFinite(value));
const stats = (name, rows, filter) => {
  const values = elapsed(rows.filter(filter)).sort((a, b) => a - b);
  const [ciLow, ciHigh] = bootstrapP95(values);
  return {
    dataset: name,
    samples: values.length,
    p50Ms: values.length ? Number(pct(values, 50).toFixed(3)) : null,
    p95Ms: values.length ? Number(pct(values, 95).toFixed(3)) : null,
    p95CiLowMs: ciLow === null ? null : Number(ciLow.toFixed(3)),
    p95CiHighMs: ciHigh === null ? null : Number(ciHigh.toFixed(3)),
    p99Ms: values.length ? Number(pct(values, 99).toFixed(3)) : null,
    maxMs: values.length ? Number(values[values.length - 1].toFixed(3)) : null,
  };
};

const baseFilter = (row) => /^GET \/api\/v1\/projects\/[0-9a-f-]{36}$/.test(row.label);
const followFilter = (row) => row.label === 'Follow-up GET project detail';
const b1 = parseJtl(b1Path);
const b2 = parseJtl(b2Path);
const rounds = [1, 2, 3].map((round) => {
  const file = path.join(followupDir, `project-detail-r${round}.jtl`);
  return { round, rows: fs.existsSync(file) ? parseJtl(file) : [] };
});
const warmupFile = path.join(followupDir, 'project-detail-warmup.jtl');
const warmup = fs.existsSync(warmupFile) ? parseJtl(warmupFile) : [];

const rows = [
  stats('baseline-b1', b1, baseFilter),
  stats('baseline-b2', b2, baseFilter),
  stats('followup-warmup', warmup, followFilter),
  ...rounds.map(({ round, rows: roundRows }) => stats(`followup-r${round}`, roundRows, followFilter)),
];

const writeCsv = (file, data, columns) => {
  const lines = [columns.join(',')];
  for (const row of data) lines.push(columns.map((column) => `"${row[column] ?? ''}"`).join(','));
  fs.writeFileSync(path.join(outDir, file), `${lines.join('\n')}\n`, 'utf8');
};

writeCsv('followup-p95.csv', rows, [
  'dataset', 'samples', 'p50Ms', 'p95Ms', 'p95CiLowMs', 'p95CiHighMs', 'p99Ms', 'maxMs',
]);

const baselinePool = [...elapsed(b1.filter(baseFilter)), ...elapsed(b2.filter(baseFilter))];
const followPool = rounds.flatMap(({ rows: roundRows }) => elapsed(roundRows.filter(followFilter)));
const baselineP95 = pct([...baselinePool].sort((a, b) => a - b), 95);
const followP95 = pct([...followPool].sort((a, b) => a - b), 95);
const comparison = [];
const addComparison = (name, left, right) => {
  if (!left || !right) return;
  const absDiff = right - left;
  const relDiff = (absDiff / left) * 100;
  comparison.push({
    comparison: name,
    baselineP95Ms: Number(left.toFixed(3)),
    followupP95Ms: Number(right.toFixed(3)),
    absDiffMs: Number(absDiff.toFixed(3)),
    relDiffPct: Number(relDiff.toFixed(2)),
    verdict: Math.abs(relDiff) <= 20 ? 'WITHIN_20_PCT' : 'OUTSIDE_20_PCT',
  });
};
addComparison('baseline-pooled -> followup-pooled', baselineP95, followP95);
const b1P95 = rows.find((row) => row.dataset === 'baseline-b1')?.p95Ms;
const b2P95 = rows.find((row) => row.dataset === 'baseline-b2')?.p95Ms;
for (const round of [1, 2, 3]) {
  const fuP95 = rows.find((row) => row.dataset === `followup-r${round}`)?.p95Ms;
  addComparison(`baseline-b1 -> followup-r${round}`, b1P95, fuP95);
  addComparison(`baseline-b2 -> followup-r${round}`, b2P95, fuP95);
}
writeCsv('followup-comparison.csv', comparison, [
  'comparison', 'baselineP95Ms', 'followupP95Ms', 'absDiffMs', 'relDiffPct', 'verdict',
]);

console.log('dataset             n       p50     p95     p95 CI            p99     max');
for (const row of rows) {
  console.log([
    row.dataset.padEnd(18),
    String(row.samples).padStart(6),
    String(row.p50Ms).padStart(7),
    String(row.p95Ms).padStart(7),
    `[${row.p95CiLowMs}, ${row.p95CiHighMs}]`.padEnd(16),
    String(row.p99Ms).padStart(7),
    String(row.maxMs).padStart(7),
  ].join(' '));
}
console.log('\ncomparison                                  absDiff   relDiff  verdict');
for (const row of comparison) {
  console.log(`${row.comparison.padEnd(42)} ${String(row.absDiffMs).padStart(8)} ${String(row.relDiffPct).padStart(8)}% ${row.verdict}`);
}
