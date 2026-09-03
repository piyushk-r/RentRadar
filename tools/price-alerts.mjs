// Price alerts (AC-3.2): diff the last two commits of the price files and
// report notable moves. No subscription system, no database, no addresses to
// store — the delivery mechanism is a GitHub issue, which emails whoever
// watches the repo. That is the whole feature.
//
// Prints a Markdown report to stdout, or nothing at all when nothing moved
// (the workflow then opens no issue — silence is the normal outcome).
//
// Usage: node tools/price-alerts.mjs [--threshold 10]   (from the repo root)

import { execFileSync } from 'node:child_process';

const MAX_BUFFER = 64 * 1024 * 1024;
const thresholdArg = process.argv.indexOf('--threshold');
/** Percent move worth reporting. Small drift is noise, not news. */
const THRESHOLD = thresholdArg > -1 ? Number(process.argv[thresholdArg + 1]) : 10;
/** The tenure the report speaks in — the same one the landing pages use. */
const TENURE = 12;

function git(...args) {
  // stderr is piped, not inherited: a path absent from an older commit is
  // expected, and git's "fatal:" line would otherwise pollute the report.
  return execFileSync('git', args, {
    encoding: 'utf8',
    maxBuffer: MAX_BUFFER,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function showJson(ref) {
  try {
    return JSON.parse(git('show', ref));
  } catch {
    return null;
  }
}

/** Every price record at a commit, across the split files and the legacy one. */
function recordsAt(sha) {
  const records = [];
  const legacy = showJson(`${sha}:data/prices.json`);
  if (legacy?.records) records.push(...legacy.records);
  let names = [];
  try {
    names = git('ls-tree', '--name-only', sha, 'data/prices/').trim().split('\n').filter(Boolean);
  } catch {
    /* no split directory at that commit */
  }
  for (const name of names) {
    if (!name.endsWith('.json')) continue;
    const file = showJson(`${sha}:${name}`);
    if (file?.records) records.push(...file.records);
  }
  return records;
}

const commits = git('log', '-2', '--format=%H', '--', 'data/prices.json', 'data/prices')
  .trim()
  .split('\n')
  .filter(Boolean);

if (commits.length < 2) {
  // One data commit means there is nothing to compare against yet.
  process.exit(0);
}

const [current, previous] = commits;
const key = (r) => `${r.provider}|${r.externalId}|${r.tenureMonths}`;
const before = new Map(recordsAt(previous).map((r) => [key(r), r]));
const after = recordsAt(current);

const catalogue = showJson(`${current}:data/catalogue.json`);
const names = new Map((catalogue?.products ?? []).map((p) => [p.id, p.name]));

const drops = [];
const rises = [];
for (const record of after) {
  if (record.tenureMonths !== TENURE) continue;
  const old = before.get(key(record));
  if (!old || old.monthlyPaise === record.monthlyPaise || old.monthlyPaise === 0) continue;
  const changePercent = ((record.monthlyPaise - old.monthlyPaise) / old.monthlyPaise) * 100;
  if (Math.abs(changePercent) < THRESHOLD) continue;
  const entry = {
    name: names.get(record.canonicalProductId) ?? record.canonicalProductId,
    provider: record.provider,
    listing: record.providerName,
    url: record.providerUrl,
    fromPaise: old.monthlyPaise,
    toPaise: record.monthlyPaise,
    changePercent,
  };
  (changePercent < 0 ? drops : rises).push(entry);
}

if (drops.length === 0 && rises.length === 0) {
  process.exit(0);
}

const rupees = (paise) => `₹${(paise / 100).toLocaleString('en-IN')}`;
const bySize = (a, b) => Math.abs(b.changePercent) - Math.abs(a.changePercent);

const lines = [];
lines.push(`Comparing the last two price commits at the ${TENURE}-month tenure; moves of ${THRESHOLD}% or more only.`);
lines.push('');
for (const [title, group] of [['Price drops', drops.sort(bySize)], ['Price rises', rises.sort(bySize)]]) {
  if (group.length === 0) continue;
  lines.push(`## ${title} (${group.length})`);
  lines.push('');
  lines.push('| Row | Provider | Was | Now | Change |');
  lines.push('|---|---|---|---|---|');
  for (const e of group) {
    const sign = e.changePercent > 0 ? '+' : '';
    lines.push(
      `| ${e.name} | ${e.provider} | ${rupees(e.fromPaise)} | [${rupees(e.toPaise)}](${e.url}) | ${sign}${e.changePercent.toFixed(0)}% |`,
    );
  }
  lines.push('');
}
lines.push(`Prices are the providers' own; confirm on the linked page before acting. Compared \`${previous.slice(0, 7)}\` → \`${current.slice(0, 7)}\`.`);
console.log(lines.join('\n'));
