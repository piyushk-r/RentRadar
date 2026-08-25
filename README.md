# RentRadar

Compare furniture & appliance rental prices across Bengaluru in one place —
monthly rent, refundable deposit, and the true cost over your tenure, with a
visible last-checked time on every price.

**₹0/month by construction**: no server, no database, no Redis. A Java 21
pipeline runs on a GitHub Actions cron, scrapes permitted providers politely,
writes JSON, and commits it. **The repo is the database; `git log` is the
price history.** A static Next.js site reads those files and only ever does
`min` and `sum`.

Full requirements: [docs/CompareFurniture-PRD.md](docs/CompareFurniture-PRD.md).
Current phase: **Phase 0** — one provider (RentoMojo), one category
(refrigerators), the whole loop end to end.

## Map

```
.github/workflows/
  scrape.yml        cron "0 */12 * * *" + concurrency group (the TTL and the lock)
  keepalive.yml     defeats the 60-day scheduled-workflow disable
  ci.yml            pipeline tests + web build on push/PR
pipeline/           Java 21 · Spring Boot CommandLineRunner · Maven
  provider/         adapter interface + RentoMojo (SSR HTML, robots honoured at runtime)
  scraper/          polite HTTP client, robots.txt evaluation
  product/          normalizer + confidence-gated matcher (below threshold → review queue)
  pricing/          pure cost resolution, integer paise, all six tenures
  store/            data/ reader-writer: FR-5.4 merge, coverage guard, plausibility bounds
data/               the database (JSON, sorted keys, stable diffs)
  prices.json       one record per (listing, tenure): every cost figure pre-resolved
  catalogue.json    canonical products + attributes
  mappings.json     provider listing → canonical product (learned state)
  pending-matches.json  review queue; never reaches the site
  runs.json         per-provider outcome of the last run
web/                Next.js static export; browser does min and sum only
  scripts/prepare-data.mjs  build fails on malformed/unattributed data (NFR-A4)
```

## Run locally

Needs Java 21, Maven, Node 20+.

```sh
./run.sh          # or .\run.ps1 on Windows — tests, then a live pipeline run into data/
cd web
npm install
npm run dev       # or npm run build for the static export in web/out/
```

## Deploy

Host on **Cloudflare Pages** (not Vercel Hobby — its non-commercial clause is
the one free-tier term that could ever bite; PRD §22):

- Build command: `cd web && npm ci && npm run build`
- Output directory: `web/out`
- Every data commit from the scrape workflow triggers a rebuild, so the site
  is as fresh as the data.

## The rules that matter

- **Never fabricate a price.** Every record carries `provider`, `providerUrl`,
  `scrapedAt`; a record missing any of the three fails the web build.
- **A failed provider must not commit an empty result** (FR-5.4). Its previous
  values survive and their age climbs — visible on `/status` and in the UI.
- **Unmatched listings go to review** (`pending-matches.json`), never guessed
  into a comparison row.
- **Compliance gate before any adapter** (PRD §14): robots.txt re-checked at
  runtime, honest User-Agent with contact, no CAPTCHA/anti-bot circumvention,
  a handful of requests per day. Per-provider records in `docs/compliance/`.
- **Money is integer paise.** No floats anywhere in the pipeline or the JSON.
- Prices shown are the providers' advertised amounts (GST, where quoted
  separately, is stored in `monthlyTaxPaise` and not added into headline
  numbers).

## Notes

- Action SHAs in the workflows are pinned; if GitHub rejects one after a fork
  or an action re-release, re-pin against the tag noted in the comment.
- RentoMojo publishes tenures 3/6/9/11/12/24/36 — there is no 18-month plan,
  so the 18-month view honestly says "no 18-month plan" rather than deriving
  one (PRD §25).
