// Price history from git (PRD section 17, AC-2.1): the repository is the
// database, so `git log` over the price files IS the time series. This script
// replays every commit that touched price data and emits one JSON file per
// canonical product with the points a run actually collected — no backfill,
// no interpolation. It also derives provider reliability over the last 30
// days of runs.json history (the w3 input of the best-value score, PRD §11).
//
// Deterministic on purpose: same git history in, same bytes out. Timestamps
// are commit dates, not wall clock, so re-running does not churn the diff.
//
// Usage: node tools/build-history.mjs   (from the repo root)

import { execFileSync } from 'node:child_process';
import { mkdirSync, readdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const HISTORY_DIR = join('data', 'history');
const STATS_FILE = join('data', 'provider-stats.json');
const MAX_BUFFER = 64 * 1024 * 1024;

function git(...args) {
  // stderr is piped, not inherited: asking for a path that did not exist at an
  // older commit is expected here, and git's "fatal:" line is not a problem.
  return execFileSync('git', args, {
    encoding: 'utf8',
    maxBuffer: MAX_BUFFER,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function gitShowJson(ref) {
  try {
    return JSON.parse(git('show', ref));
  } catch {
    return null; // file absent at that commit, or unparseable — skip honestly
  }
}

/**
 * Commits that touched the given paths, oldest first, as {sha, date}.
 *
 * `--full-history` is load-bearing: with a pathspec, git's default history
 * simplification prunes commits from a merged branch when the merge result
 * matches one parent for those paths. After merging a week of scheduled
 * refreshes that silently hid 19 of 23 price commits — the time series would
 * have lost most of its points with nothing to indicate it.
 */
function commitsTouching(...paths) {
  const out = git('log', '--full-history', '--reverse', '--format=%H|%cI', '--', ...paths).trim();
  if (!out) return [];
  return out.split('\n').map((line) => {
    const [sha, date] = line.split('|');
    return { sha, date };
  });
}

/** All price records at a commit: legacy data/prices.json or split data/prices/. */
function recordsAt(sha) {
  const records = [];
  const legacy = gitShowJson(`${sha}:data/prices.json`);
  if (legacy?.records) records.push(...legacy.records);
  let names = [];
  try {
    names = git('ls-tree', '--name-only', sha, 'data/prices/').trim().split('\n').filter(Boolean);
  } catch {
    // data/prices/ absent at that commit
  }
  for (const name of names) {
    if (!name.endsWith('.json')) continue;
    const file = gitShowJson(`${sha}:${name}`);
    if (file?.records) records.push(...file.records);
  }
  return records;
}

// A shallow clone has no history to walk: every series would collapse to one
// point and this script would commit that flattening over the real record.
// Refuse instead (the workflow checks out with fetch-depth: 0).
if (git('rev-parse', '--is-shallow-repository').trim() === 'true') {
  console.error('refusing to rebuild history from a shallow clone — check out with fetch-depth: 0');
  process.exit(1);
}

// ---- walk price history ----

const priceCommits = commitsTouching('data/prices.json', 'data/prices');
console.log(`walking ${priceCommits.length} commits of price history`);

// key = productId | provider | externalId | tenure → { points, last }
const seriesByKey = new Map();
const firstSeenByProduct = new Map();

for (const { sha, date } of priceCommits) {
  for (const record of recordsAt(sha)) {
    if (!record.canonicalProductId) continue;
    const key = [record.canonicalProductId, record.provider, record.externalId, record.tenureMonths].join('|');
    let series = seriesByKey.get(key);
    if (!series) {
      series = {
        productId: record.canonicalProductId,
        provider: record.provider,
        externalId: record.externalId,
        tenureMonths: record.tenureMonths,
        points: [],
        last: null,
        lastSeen: null,
      };
      seriesByKey.set(key, series);
    }
    // Only real changes become points; a run that re-observed the same price
    // is not a new fact about the price.
    if (series.last !== record.monthlyPaise) {
      series.points.push([date, record.monthlyPaise]);
      series.last = record.monthlyPaise;
    }
    // The last commit that still carried this listing. Points record changes
    // only, so without this a delisted product's final price looks current.
    series.lastSeen = date;
    if (!firstSeenByProduct.has(record.canonicalProductId)) {
      firstSeenByProduct.set(record.canonicalProductId, date);
    }
  }
}

const byProduct = new Map();
for (const series of seriesByKey.values()) {
  if (!byProduct.has(series.productId)) byProduct.set(series.productId, []);
  byProduct.get(series.productId).push(series);
}

mkdirSync(HISTORY_DIR, { recursive: true });
const written = new Set();
for (const [productId, seriesList] of [...byProduct.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
  seriesList.sort(
    (a, b) =>
      a.provider.localeCompare(b.provider) ||
      a.externalId.localeCompare(b.externalId) ||
      a.tenureMonths - b.tenureMonths,
  );
  const out = {
    productId,
    collectingSince: firstSeenByProduct.get(productId),
    series: seriesList.map(({ provider, externalId, tenureMonths, points, lastSeen }) => ({
      provider,
      externalId,
      tenureMonths,
      lastSeen,
      points,
    })),
  };
  const fileName = `${productId}.json`;
  writeFileSync(join(HISTORY_DIR, fileName), JSON.stringify(out, null, 2) + '\n');
  written.add(fileName);
}
// A product that vanished from history entirely (e.g. a renamed canonical id)
// must not leave a ghost file behind.
for (const name of readdirSync(HISTORY_DIR)) {
  if (name.endsWith('.json') && !written.has(name)) rmSync(join(HISTORY_DIR, name));
}
console.log(`history: ${written.size} product files in ${HISTORY_DIR}`);

// ---- provider reliability over the last 30 days of runs ----

const runCommits = commitsTouching('data/runs.json');
const newest = runCommits.at(-1);
if (newest) {
  // Compare instants, not ISO strings: git's %cI carries the commit's own UTC
  // offset, so a lexicographic compare against a Z-normalized cutoff silently
  // widens or narrows the window.
  const cutoff = new Date(newest.date).getTime() - 30 * 24 * 3600 * 1000;
  const tallies = new Map(); // provider → { ok, failed }
  for (const { sha, date } of runCommits) {
    if (new Date(date).getTime() < cutoff) continue;
    const runs = gitShowJson(`${sha}:data/runs.json`);
    if (!runs?.providers) continue;
    for (const [provider, run] of Object.entries(runs.providers)) {
      const tally = tallies.get(provider) ?? { ok: 0, failed: 0 };
      if (run.status === 'FAILED') tally.failed += 1;
      else tally.ok += 1;
      tallies.set(provider, tally);
    }
  }
  const providers = {};
  for (const [provider, { ok, failed }] of [...tallies.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    providers[provider] = {
      successRate30d: Math.round((ok / (ok + failed)) * 100) / 100,
      samples: ok + failed,
    };
  }
  writeFileSync(STATS_FILE, JSON.stringify({ asOf: newest.date, providers }, null, 2) + '\n');
  console.log(`provider stats: ${Object.keys(providers).length} providers over ${runCommits.length} run commits`);
} else if (!existsSync(STATS_FILE)) {
  writeFileSync(STATS_FILE, JSON.stringify({ asOf: null, providers: {} }, null, 2) + '\n');
}
