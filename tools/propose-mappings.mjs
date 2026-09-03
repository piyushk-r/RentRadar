// Review queue → a pull request (PRD section 19, FR-7.4 / AC-2.3).
//
// Turns data/pending-matches.json into a reviewable diff: every pending entry
// that carries a proposed canonical id becomes a mappings.json entry (plus a
// catalogue row if the id is new), and leaves the pending file. Entries with
// no proposal stay pending and are listed in the PR body for hand-editing.
// Reviewing means reading the diff and clicking merge; the next run applies
// the merged mappings automatically (PipelineRunner: existing mapping wins).
//
// The PR body is written to stdout; file edits happen in place. The workflow
// runs this on a branch, never on main.
//
// Usage: node tools/propose-mappings.mjs   (from the repo root)

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const DATA = 'data';

/**
 * A number that remembers how it was written. Java writes a double as `1.0`;
 * JavaScript renders the same value as `1`, which would rewrite every
 * confidence line in the file and be undone by the next pipeline run. Parsing
 * with the reviver's source text keeps the original spelling.
 */
class RawNumber {
  constructor(source) {
    this.source = source;
  }
  valueOf() {
    return Number(this.source);
  }
  toString() {
    return this.source;
  }
}

function read(name) {
  return JSON.parse(readFileSync(join(DATA, name), 'utf8'), function (key, value, context) {
    return typeof value === 'number' && context?.source !== undefined ? new RawNumber(context.source) : value;
  });
}

/**
 * Serializes exactly the way the Java pipeline does — Jackson's default pretty
 * printer with alphabetically sorted keys: two-space indent, a space on both
 * sides of the colon, and `{ }` for an empty object.
 *
 * This is not cosmetic. The whole review mechanism is "read a diff and click
 * merge" (PRD §19), and `JSON.stringify`'s `"key": value` differs from
 * Jackson's `"key" : value` on every single line — which turns an 8-line
 * review into a 4,000-line rewrite, and makes the next pipeline run undo it.
 */
function toJacksonJson(value, indent = 0) {
  const pad = '  '.repeat(indent);
  const padInner = '  '.repeat(indent + 1);
  if (value instanceof RawNumber) return value.source;
  if (typeof value === 'number') {
    // A number this script itself produced: Java would write it as a double.
    return Number.isInteger(value) ? `${value}.0` : String(value);
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return '[ ]';
    const items = value.map((item) => padInner + toJacksonJson(item, indent + 1));
    return '[\n' + items.join(',\n') + '\n' + pad + ']';
  }
  if (value && typeof value === 'object') {
    const keys = Object.keys(value).sort();
    if (keys.length === 0) return '{ }';
    const entries = keys.map((key) => `${padInner}${JSON.stringify(key)} : ${toJacksonJson(value[key], indent + 1)}`);
    return '{\n' + entries.join(',\n') + '\n' + pad + '}';
  }
  return JSON.stringify(value);
}

function write(name, value) {
  writeFileSync(join(DATA, name), toJacksonJson(value) + '\n');
}

const pendingFile = read('pending-matches.json');
const mappingsFile = read('mappings.json');
const catalogueFile = read('catalogue.json');

const proposed = (pendingFile.pending ?? []).filter((e) => e.proposedCanonicalId);
const unproposed = (pendingFile.pending ?? []).filter((e) => !e.proposedCanonicalId);

if (proposed.length === 0) {
  console.error('nothing to propose — every pending entry lacks a proposed canonical id');
  process.exit(2);
}

const catalogueIds = new Set(catalogueFile.products.map((p) => p.id));
const mapped = new Set(mappingsFile.mappings.map((m) => `${m.provider}|${m.externalId}`));

const newMappings = [];
const newCatalogueRows = [];
for (const entry of proposed) {
  if (mapped.has(`${entry.provider}|${entry.externalId}`)) continue;
  newMappings.push({
    provider: entry.provider,
    externalId: entry.externalId,
    providerName: entry.name,
    providerUrl: entry.url,
    canonicalProductId: entry.proposedCanonicalId,
    confidence: entry.confidence,
    matchedBy: 'review-queue',
    matchedAt: entry.firstSeenAt,
  });
  if (!catalogueIds.has(entry.proposedCanonicalId)) {
    catalogueIds.add(entry.proposedCanonicalId);
    const words = entry.proposedCanonicalId.replace(/-/g, ' ');
    newCatalogueRows.push({
      id: entry.proposedCanonicalId,
      category: entry.category ?? 'OTHER',
      name: words.charAt(0).toUpperCase() + words.slice(1),
      attributes: {},
    });
  }
}

mappingsFile.mappings = [...mappingsFile.mappings, ...newMappings].sort(
  (a, b) => a.provider.localeCompare(b.provider) || a.externalId.localeCompare(b.externalId),
);
catalogueFile.products = [...catalogueFile.products, ...newCatalogueRows].sort((a, b) =>
  a.id.localeCompare(b.id),
);
pendingFile.pending = unproposed;

write('mappings.json', mappingsFile);
write('catalogue.json', catalogueFile);
write('pending-matches.json', pendingFile);

// ---- PR body on stdout ----

const lines = [];
lines.push('The normalizer could not confidently match these listings; each proposed mapping below is a diff line in `data/mappings.json`. **Merging is the review** (FR-7.4): edit any wrong `canonicalProductId` on this branch first, or delete its lines to keep the listing off the site.');
lines.push('');
lines.push(`## Proposed (${newMappings.length})`);
lines.push('');
lines.push('| Provider | Listing | Proposed row | Confidence |');
lines.push('|---|---|---|---|');
for (const m of newMappings) {
  lines.push(`| ${m.provider} | [${m.providerName}](${m.providerUrl}) | \`${m.canonicalProductId}\` | ${m.confidence} |`);
}
if (newCatalogueRows.length > 0) {
  lines.push('');
  lines.push(`New catalogue rows minted for proposals: ${newCatalogueRows.map((r) => `\`${r.id}\``).join(', ')} — rename them in \`data/catalogue.json\` if the generated names read poorly.`);
}
if (unproposed.length > 0) {
  lines.push('');
  lines.push(`## Still unmatched, no proposal (${unproposed.length})`);
  lines.push('');
  lines.push('These stay in the review queue. To place one, hand-add a `data/mappings.json` entry (matchedBy `manual`) on this branch:');
  lines.push('');
  for (const e of unproposed) {
    lines.push(`- ${e.provider}: [${e.name}](${e.url})`);
  }
}
lines.push('');
lines.push('Listings in this queue never reach the site until merged (AC-2.3).');
console.log(lines.join('\n'));
