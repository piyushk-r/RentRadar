// Build-time data gate (NFR-A4): the web build must fail if data/ is malformed
// or a record is missing provenance. A broken build is a visible failure; a
// build that silently ships bad data is not.
//
// Validates ../data and copies it to public/data for the static export.

import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { load as loadYaml } from 'js-yaml';

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

// Prices are split per category under data/prices/ (FR-8.1); the legacy
// single prices.json is accepted until the first post-split run commits.
function loadPriceFiles() {
  const pricesDir = join(dataDir, 'prices');
  if (existsSync(pricesDir)) {
    const files = readdirSync(pricesDir).filter((n) => n.endsWith('.json')).sort();
    if (files.length === 0) fail('data/prices/ exists but holds no category files');
    return files.map((name) => {
      const relative = `prices/${name}`;
      // The payload budget (AC-2.4): the split is the mechanism, so a single
      // category ballooning past it deserves a loud warning, not silence.
      const size = statSync(join(pricesDir, name)).size;
      if (size > 300 * 1024) console.warn(`WARN ${relative} is ${(size / 1024).toFixed(0)} KB — over the ~300 KB payload budget (FR-8.1)`);
      return { name: relative, data: loadJson(relative, true) };
    });
  }
  return [{ name: 'prices.json', data: loadJson('prices.json', true) }];
}

const priceFiles = loadPriceFiles();
const catalogue = loadJson('catalogue.json', true);
const runs = loadJson('runs.json', true);
loadJson('mappings.json', true);

let totalRecords = 0;
if (catalogue) {
  const catalogueIds = new Set((catalogue.products ?? []).map((p) => p.id));
  for (const { name, data } of priceFiles) {
    if (!data) continue;
    const records = data.records ?? [];
    if (!Array.isArray(records)) {
      fail(`${name} has no records array`);
      continue;
    }
    totalRecords += records.length;
    records.forEach((record, index) => {
      const where = `${name} record #${index} (${record.provider ?? '?'}/${record.externalId ?? '?'})`;
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
  }
  if (totalRecords === 0) {
    fail('zero price records — refusing to build an empty comparison site');
  }
}

// Setup templates (FR-4.5): YAML in data/, JSON for the browser.
const setupsYml = join(dataDir, 'setups.yml');
let setups = null;
if (existsSync(setupsYml)) {
  try {
    setups = loadYaml(readFileSync(setupsYml, 'utf8'));
    if (!Array.isArray(setups?.setups)) fail('setups.yml has no setups list');
    for (const setup of setups?.setups ?? []) {
      if (!setup.apartment || !setup.occupants || typeof setup.items !== 'object') {
        fail(`setups.yml: template "${setup.label ?? '?'}" needs apartment, occupants and items`);
      }
    }
  } catch (e) {
    fail(`setups.yml is not valid YAML: ${e.message}`);
  }
}

if (runs && !runs.providers) {
  fail('runs.json has no providers map');
}

// Cities (AC-3.3): the one place a new city is declared, so it is worth
// validating — a malformed entry would silently drop its landing pages.
const cities = loadJson('cities.json', false);
if (cities) {
  if (!Array.isArray(cities.cities) || cities.cities.length === 0) {
    fail('cities.json has no cities array');
  } else {
    for (const city of cities.cities) {
      const where = `cities.json city "${city.id ?? '?'}"`;
      if (!city.id || !city.label) fail(`${where}: needs an id and a label`);
      if (!Array.isArray(city.pincodeRanges)) fail(`${where}: pincodeRanges must be an array`);
      for (const [provider, record] of Object.entries(city.providers ?? {})) {
        if (!record.source || !record.checkedAt) {
          fail(`${where}: provider "${provider}" needs a source URL and the date it was checked`);
        }
      }
    }
    // Every city that claims serviceability should actually have prices.
    const pricedCities = new Set(priceFiles.flatMap(({ data }) => (data?.records ?? []).map((r) => r.city)));
    for (const city of cities.cities) {
      if (!pricedCities.has(city.id)) {
        console.warn(`WARN cities.json lists "${city.id}" but no price record carries that city — run the pipeline with --pipeline.city=${city.id}`);
      }
    }
  }
}

if (problems.length > 0) {
  console.error(`\nDATA VALIDATION FAILED — ${problems.length} problem(s):\n`);
  for (const problem of problems.slice(0, 50)) console.error(`  · ${problem}`);
  if (problems.length > 50) console.error(`  … and ${problems.length - 50} more`);
  console.error('\nThe site will not build over bad data (NFR-A4).\n');
  process.exit(1);
}

// Wipe first: a file deleted from data/ (prices.json after the per-category
// split, a retired history file) must not survive here and be served as data.
rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });
for (const name of readdirSync(dataDir)) {
  if (name.endsWith('.json')) {
    copyFileSync(join(dataDir, name), join(outDir, name));
  }
}
// Per-category prices and per-product history ship as directories.
for (const sub of ['prices', 'history']) {
  const from = join(dataDir, sub);
  if (!existsSync(from)) continue;
  const to = join(outDir, sub);
  mkdirSync(to, { recursive: true });
  for (const name of readdirSync(from)) {
    if (name.endsWith('.json')) copyFileSync(join(from, name), join(to, name));
  }
}
if (setups) {
  writeFileSync(join(outDir, 'setups.json'), JSON.stringify(setups, null, 2) + '\n');
}
console.log(`data validated and copied: ${totalRecords} price records in ${priceFiles.length} file(s), ${(catalogue.products ?? []).length} canonical products`);
