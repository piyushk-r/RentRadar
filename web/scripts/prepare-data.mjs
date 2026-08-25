// Build-time data gate (NFR-A4): the web build must fail if data/ is malformed
// or a record is missing provenance. A broken build is a visible failure; a
// build that silently ships bad data is not.
//
// Validates ../data and copies it to public/data for the static export.

import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dataDir = resolve(webDir, '..', 'data');
const outDir = join(webDir, 'public', 'data');

const problems = [];

function fail(message) {
  problems.push(message);
}

function loadJson(name, required) {
  const file = join(dataDir, name);
  if (!existsSync(file)) {
    if (required) fail(`${name} is missing — run the pipeline first (it writes data/)`);
    return null;
  }
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (e) {
    fail(`${name} is not valid JSON: ${e.message}`);
    return null;
  }
}

const TENURES = new Set([3, 6, 9, 12, 18, 24]);
const AVAILABILITY = new Set(['IN_STOCK', 'OUT_OF_STOCK', 'UNKNOWN']);
const MONEY_FIELDS = [
  'monthlyPaise',
  'monthlyTaxPaise',
  'depositPaise',
  'deliveryFeePaise',
  'installationFeePaise',
  'otherFeesPaise',
  'discountPaise',
  'estimatedTotalPaise',
  'cashUpfrontPaise',
];

const prices = loadJson('prices.json', true);
const catalogue = loadJson('catalogue.json', true);
const runs = loadJson('runs.json', true);
loadJson('mappings.json', true);

if (prices && catalogue) {
  const catalogueIds = new Set((catalogue.products ?? []).map((p) => p.id));
  const records = prices.records ?? [];
  if (!Array.isArray(records)) {
    fail('prices.json has no records array');
  } else {
    records.forEach((record, index) => {
      const where = `prices.json record #${index} (${record.provider ?? '?'}/${record.externalId ?? '?'})`;
      // Attribution is non-negotiable (PRD section 13): provider, providerUrl, scrapedAt.
      if (!record.provider) fail(`${where}: missing provider`);
      if (!record.providerUrl) fail(`${where}: missing providerUrl`);
      if (!record.scrapedAt || Number.isNaN(Date.parse(record.scrapedAt))) fail(`${where}: missing or invalid scrapedAt`);
      if (!record.canonicalProductId) fail(`${where}: missing canonicalProductId`);
      if (!catalogueIds.has(record.canonicalProductId)) {
        fail(`${where}: canonicalProductId "${record.canonicalProductId}" not in catalogue.json`);
      }
      if (!TENURES.has(record.tenureMonths)) fail(`${where}: tenureMonths ${record.tenureMonths} is not a display tenure`);
      if (!AVAILABILITY.has(record.availability)) fail(`${where}: availability "${record.availability}" unknown`);
      for (const field of MONEY_FIELDS) {
        // Money stays integer paise; no floats in the pipeline or the JSON (PRD section 17).
        if (!Number.isInteger(record[field])) fail(`${where}: ${field} is not an integer (${record[field]})`);
      }
      if (record.monthlyPaise < 0 || record.estimatedTotalPaise < 0) fail(`${where}: negative money`);
    });
    if (records.length === 0) {
      fail('prices.json has zero records — refusing to build an empty comparison site');
    }
  }
}

if (runs && !runs.providers) {
  fail('runs.json has no providers map');
}

if (problems.length > 0) {
  console.error(`\nDATA VALIDATION FAILED — ${problems.length} problem(s):\n`);
  for (const problem of problems.slice(0, 50)) console.error(`  · ${problem}`);
  if (problems.length > 50) console.error(`  … and ${problems.length - 50} more`);
  console.error('\nThe site will not build over bad data (NFR-A4).\n');
  process.exit(1);
}

mkdirSync(outDir, { recursive: true });
for (const name of readdirSync(dataDir)) {
  if (name.endsWith('.json')) {
    copyFileSync(join(dataDir, name), join(outDir, name));
  }
}
console.log(`data validated and copied: ${(prices.records ?? []).length} price records, ${(catalogue.products ?? []).length} canonical products`);
