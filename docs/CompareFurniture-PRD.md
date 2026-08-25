# CompareFurniture — Product Requirements (Draft v1.1, zero-cost build)

_25 Aug 2026 · Bengaluru · personal project · ₹0/month · [Read the formatted version](https://claude.ai/code/artifact/98335d26-881c-42e2-a0fc-417279175386)_

> **The shape of the build**
> - **No server, no database, no Redis.** Spring Boot becomes a `CommandLineRunner` that runs on a GitHub Actions cron, writes JSON, and commits it. The repo is the database; `git log` is the price history.
> - **The 12h TTL is a cron line; the distributed lock is `concurrency.group`.** Refresh can't be user-triggered, so there's nothing to stampede.
> - **Java resolves every cost figure ahead of time.** The browser only does `min` and `sum` — no business logic duplicated across languages.
> - **Verified constraints:** Vercel Hobby is non-commercial only (so host on Cloudflare Pages); GitHub disables scheduled workflows after 60 days of repo inactivity (so a keepalive is required).
> - **Provider access:** no official API exists for any of them. RentoMojo serves prices in HTML; Guarented and Rentickle need Playwright (free in Actions); Cityfurnish, Furlenco and Payrentz are blocked by robots.txt and become manual sheets.

---

## 1. What we are building

A search box, a comparison table, and a number the user can trust. Everything else in this document exists to make those three things correct.

A user tells us their city, how long they are renting for, and what they need. We return every provider's price for each item, the cheapest way to buy the whole basket, and the true cost over the rental period — rent plus deposit plus delivery plus installation, with refundable money separated from money that is gone.

The product is thin on features and heavy on data integrity. The hard parts are not the UI; they are keeping eleven independent providers' prices current without hammering their sites, normalizing "Single Door Fridge" and "Samsung 192L" into the same comparable row, and never showing a number we cannot stand behind.

Scope change recommended before build starts

The proposed Phase 1 provider set includes two providers whose `robots.txt` currently disallows exactly the listing paths we would need to read. Section 14 has the evidence per provider. The recommendation is to ship Phase 1 on **RentoMojo, Rentickle and Guarented**, and route **Cityfurnish and Furlenco** through partnership or a manual-entry adapter until a feed exists.

#### The promise

Compare furniture and appliance rental prices across Bengaluru in one place.

#### The question we answer

"What is the cheapest way to rent the things I need, for the months I need them?"

#### The line we don't cross

No fabricated prices, no stale data presented as current, no paid placement inside a "cheapest" ranking.

#### The budget

₹0 per month, permanently. Not "cheap" — zero. That constraint does more design work than any feature in this document.

What the budget removes

An always-on server, a managed database and a Redis instance were the only line items with a price. All three existed to serve concurrency this project will never have. Removing them takes the distributed lock, the TTL orchestrator, the admin authentication layer and the API rate limiter with them — roughly a third of the original build, deleted rather than descoped.

What survives is everything that made the product correct: adapter isolation, normalization, the cost model, and the freshness rules. Those are engineering problems, not scaling problems, and they are free.

## 2. Every line item, and what it costs

The target is not a small bill. It is no bill, no card on file, and no service that can start charging because a threshold moved.

| Need | Free service used | Included | Our estimated use |
|---|---|---|---|
| Scheduled compute for scraping | GitHub Actions, standard runner | 2,000 min/mo private — unlimited on public repos | ~480 min/mo — 24% of budget |
| Headless browser | Playwright on the Actions Ubuntu runner | included | ₹0 |
| Data storage | The Git repository itself | soft limit ~1 GB | a few MB/yr of JSON |
| Price history | Git commit history | included | ₹0 |
| Job mutex | Actions `concurrency` group | included | ₹0 |
| Site hosting | Cloudflare Pages or Vercel Hobby | 500 builds/mo · generous transfer | ~60 builds/mo |
| Failure alerting | GitHub Actions failure email | included | ₹0 |
| Domain | `*.pages.dev` / `*.vercel.app` subdomain | free | ₹0 |
| *Custom domain* | *the only thing on this page that costs money* | — | ~₹1,000/yr — declined |

### Two conditions on the free tiers

- C-1**Vercel Hobby is non-commercial only.** Their docs state it plainly: the Hobby plan “restricts users to non-commercial, personal use only”. The moment an affiliate link appears on the site, the project needs Vercel Pro at \$20/mo or a move to Cloudflare Pages, which carries no such clause. Building on Cloudflare Pages from the start avoids the choice entirely — see §22.
- C-2**GitHub disables scheduled workflows after 60 days of repository inactivity.** Commits made by the workflow’s own token may not reset that clock. The cron therefore needs a keepalive — a monthly manual commit, or a second workflow that touches the repo — or the site silently stops updating while still looking healthy.

The honest version of “free”

Free tiers are a business decision by someone else, and they change. The mitigation is not to find better free tiers — it is that nothing here is hard to move. The pipeline is a Java CLI and the data is JSON files in a repo; if Actions changes terms, the same CLI runs on any machine with a cron entry, including a laptop. Portability is the actual insurance policy, not the current price list.

## 3. Six tabs and no answer

Furniture rental in Bengaluru is a mature, fragmented market. A person setting up a flat has to visit RentoMojo, Cityfurnish, Furlenco, Rentickle and Guarented separately, re-enter the same tenure on each, and then do arithmetic that none of the sites do for them.

### Why the arithmetic is the actual problem

Headline monthly rent is not comparable across providers, because the rest of the cost structure differs and is disclosed late — often only at checkout.

| What varies             | Why it breaks naive comparison                                                                                              |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| Security deposit        | Refundable, but locks up cash. A ₹500/mo item with a ₹10,000 deposit beats a ₹550/mo item with ₹2,000 only at long tenures. |
| Tenure pricing          | Monthly rate usually falls with a longer commitment. A 3-month quote and a 24-month quote are different products.           |
| Delivery & installation | Sometimes free, sometimes per-item, sometimes waived above a basket value.                                                  |
| Basket effects          | Bundle discounts and free-delivery thresholds mean the cheapest item-by-item pick is not always the cheapest basket.        |
| Serviceability          | A price is meaningless if the provider does not deliver to Whitefield or Electronic City.                                   |

The result is that people either pick on brand recognition, or spend an evening in a spreadsheet. We are building the spreadsheet, once, correctly.

## 4. Goals and non-goals

### Goals

- **Comparable prices.** Normalize heterogeneous catalogues into canonical products so a row genuinely compares like with like.
- **Honest totals.** Show estimated total cost for the chosen tenure, with refundable deposit stated separately from sunk cost.
- **Two answers, not one.** Cheapest cross-provider basket *and* cheapest single-provider basket, with the delta between them.
- **Fresh, attributable data.** Every price carries a provider, a source URL and a checked-at timestamp, surfaced in the UI.
- **Fail partially, never wholly.** One broken provider degrades one column, not the site.
- **Replaceable providers.** Adding or swapping a provider must not touch the comparison engine.

### Non-goals

- **We are not a rental marketplace.** No checkout, no inventory, no delivery, no KYC. We hand the user off to the provider.
- **No cities beyond Bengaluru in v1.** The schema is multi-city; the product is not.
- **No accounts in Phase 1.** Comparison works anonymously.
- **No used/resale, no rent-to-own modelling, no B2B/office bulk quoting.**
- **No real-time price fetch on user request.** Prices are served from our store; refresh is asynchronous.
- **No circumvention.** We do not defeat CAPTCHAs, anti-bot systems, logins or paywalls to obtain data (§14).

## 5. Who this is for

All primary users share one trait: **a move-in date**. They are deciding under time pressure, usually within a week of taking possession of a flat.

#### The relocator

Moving to Bengaluru for a job, 12–24 month horizon, furnishing a 1BHK or 2BHK from zero. Highest basket value, most price-sensitive to deposit.

#### The bachelor flat-share

2–3 people splitting a flat. Needs multiples of the same item. Optimizes hard on monthly rent; tenure often uncertain.

#### The short-stay professional

3–6 months on a project. Deposit dominates their total cost; a low headline rent can be the worse deal.

#### The gap-filler

Owns most things, needs one appliance — a washing machine or a fridge. Single-item comparison, arrives from search.

### Jobs to be done

- When I am furnishing a flat, I want one view of every provider's price, so I don't have to trust that the first site I opened was fair.
- When I compare two quotes, I want the deposit and fees folded into a single number for my actual tenure, so I can tell which is genuinely cheaper.
- When I find a good price, I want to know it is current, so I don't arrive at the provider's site and find a different number.
- When mixing providers saves little, I want to know that, so I can choose one company and one delivery date instead.

## 6. How I know it works

This is a personal project, so click-through and conversion targets would be theatre. The metrics that matter are the ones that tell me whether the data is true and the pipeline is alive — and every one of them is computable inside the build, with no analytics service.

| Layer    | Metric                                          | Target          | Where it comes from                                                  |
|----------|-------------------------------------------------|-----------------|----------------------------------------------------------------------|
| Truth    | Sampled prices matching the provider’s own page | ≥ 97%           | Manual spot-check, 20 items, monthly                                 |
| Truth    | Prices displayed with age under 12 h            | ≥ 95%           | Computed at build from `scrapedAt`                                   |
| Liveness | Consecutive successful scheduled runs           | ≥ 95% of runs   | `runs.json`, written every run                                       |
| Liveness | Days since last successful run                  | ≤ 1             | Status page — the single number that says the project is still alive |
| Coverage | Canonical products priced by ≥ 2 providers      | ≥ 70%           | Computed at build                                                    |
| Coverage | Provider products auto-matched without review   | ≥ 85%           | Normalizer output                                                    |
| Cost     | Actions minutes consumed per month              | \< 50% of quota | GitHub billing page                                                  |

Coverage is still the metric that decides whether this is a comparison site or a list of links. With three providers the bar drops from three prices per row to two — below that, a row has nothing to compare.

## 7. The search

The homepage does one thing: collect city, tenure and a basket. The hero states the promise and the form sits directly beneath it — no scroll required on a laptop.

- FR-1.1**City.** Defaults to Bengaluru and is the only selectable value in v1. The control is present and disabled-with-reason, not hidden, so the multi-city intent is legible.
- FR-1.2**Tenure.** 3 / 6 / 9 / 12 / 18 / 24 months plus Custom. Default 12. Tenure is a first-class query parameter, not a display filter — it changes which price rows are selected.
- FR-1.3**Basket.** Multi-select over 15 categories: bed, mattress, refrigerator, washing machine, sofa, wardrobe, study table, office chair, dining table, TV, air conditioner, microwave, air cooler, water purifier, other.
- FR-1.4**Quantity.** Each selected category carries a quantity (default 1). Two beds is the common case, not an edge case.
- FR-1.5**Variant is optional at search time.** A user picks "Bed"; they refine to "Queen, with storage" on the results page. Forcing variant choice up front costs completions.
- FR-1.6**Shareable state.** The full query serializes into the URL so a comparison can be sent to a flatmate.
- FR-1.7**Popular comparisons.** Below the form: bed, fridge, washing machine, mattress, 2BHK setup, bachelor setup — each a pre-filled query, and each an indexable landing page (§22).

## 8. Comparison results

Desktop gets a matrix: canonical products down the left, providers across the top, monthly price in the cells, a total row at the bottom. Mobile gets one card per product with providers ranked inside it. Same data, two shapes; the matrix does not survive a 390px viewport and should not be forced to.

- FR-2.1Cells show monthly price for the selected tenure, with a secondary line for the estimated total when the total-cost view is toggled on.
- FR-2.2The lowest price in each row carries a Best price badge. Ties are shown as ties, not arbitrarily ordered.
- FR-2.3A provider with no matching product shows an explicit Not offered, never a blank cell — blank reads as an error or a zero.
- FR-2.4A provider whose last successful refresh is older than 24h shows its column dimmed with Checked 28h ago. A provider in hard failure shows the last known price with an explicit staleness label, or is dropped from the total with a footnote.
- FR-2.5The per-provider total row includes only items that provider actually stocks, and is annotated with how many of the requested items it covers (e.g. *3 of 4 items*). A 3-item total must never be visually comparable to a 4-item total without that label.
- FR-2.6Every price cell links to the provider's own listing for that product.
- FR-2.7A freshness line sits above the table: *Prices last checked 2 hours ago* / *Refreshing — showing latest available prices* / *Updated just now*.

Design constraint

Coverage gaps are the honest failure mode of a comparison site and must be designed for, not hidden. A cell is one of four states — priced, not offered, out of stock, or stale — and each has a distinct visual treatment.

## 9. The cheapest-basket engine

This is the product's core computation and it must be deterministic, explainable, and independent of any provider code. It produces two answers side by side.

### A. Cheapest cross-provider basket

For each requested item, pick the provider offering the lowest true cost for that item at the chosen tenure; sum. Because provider-level effects exist (free delivery above a basket threshold, bundle discounts), the greedy per-item pick is only correct once those effects are applied at basket level. The engine therefore evaluates candidate assignments and applies provider-level adjustments before comparing totals.

### B. Cheapest single-provider basket

For each provider that stocks *all* requested items, compute the basket total with that provider's bundle rules; take the minimum. Providers with partial coverage are reported separately, never silently mixed into this figure.

### C. The delta

Present the saving from splitting across providers explicitly. If it is small, say so — a ₹120/month saving spread over four deliveries, four delivery windows and four support relationships is a real trade-off the user should make consciously.

Cheapest combination · 12 months

Queen bed — Provider A₹550

Mattress — Provider B₹349

Refrigerator — Provider A₹499

Washing machine — Provider B₹659

Total per month₹2,057

vs. cheapest single provider₹2,180

You save by splitting₹123 / mo

Illustrative layout only. Every figure in production comes from the price store; no example price in this document may appear in code.

### Requirements

- FR-3.1The engine reads only from the normalized price store. It has no knowledge of providers beyond their IDs and fee rules, and no network calls.
- FR-3.2Ranking is by *estimated total cost over the selected tenure*, never by monthly rent alone (§10).
- FR-3.3Every result is explainable: the response includes the per-item chosen provider, unit price, quantity and the adjustments applied.
- FR-3.4Items with no price from any provider are excluded from totals and reported as uncovered.
- FR-3.5Sponsored or featured status has zero weight in this computation. Ever. (§22)

## 10. What a rental actually costs

One formula, applied identically to every provider, is what makes the comparison fair.

    estimatedTotal = (monthlyPrice × tenureMonths)
                   + deliveryFee
                   + installationFee
                   + otherMandatoryFees
                   - discounts

    refundableHeld  = securityDeposit
    cashRequiredUpfront = securityDeposit + deliveryFee + installationFee + firstMonthRent

Deposit is deliberately outside `estimatedTotal` — it comes back. But it is cash the user does not have for the duration, so it gets equal billing in the UI as a separate, clearly labelled figure. Three numbers are always shown together: **monthly**, **estimated total (non-refundable)**, and **refundable deposit**.

| Field                              | Treatment                                                                                       | Shown as                   |
|------------------------------------|-------------------------------------------------------------------------------------------------|----------------------------|
| Monthly rent                       | × tenure                                                                                        | Sunk                       |
| Security deposit                   | Held, returned at end                                                                           | Refundable — separate line |
| Delivery fee                       | One-off, may be threshold-waived                                                                | Sunk                       |
| Installation fee                   | One-off, per item                                                                               | Sunk                       |
| Other mandatory charges            | One-off or recurring; must be labelled                                                          | Sunk                       |
| Discounts                          | Subtracted; source recorded                                                                     | Reduces sunk               |
| Relocation / early-closure charges | Phase 2 Not modelled in v1; noted in provider detail if published | —                          |

Why this is the ranking key

₹500/mo with a ₹10,000 deposit and ₹550/mo with a ₹2,000 deposit are the same headline story and different decisions. Over 3 months the deposit dominates; over 24 months the rent does. Ranking on monthly price alone would give the wrong answer to the short-stay user — one of our four core personas.

## 11. Product detail, filters, sorting

### Product detail

Opening a cell shows the provider's actual listing as we hold it: name, image, brand, model, size, features, monthly price, deposit, delivery, installation, minimum rental period, availability, provider, last-checked timestamp, and a link out. The last-checked timestamp is not fine print — it is a primary trust signal and sits next to the price.

### Filters

| Filter                | Values                                              |
|-----------------------|-----------------------------------------------------|
| Category              | The 15 categories in FR-1.3                         |
| Provider              | Multi-select                                        |
| Monthly price         | Min / max                                           |
| Tenure                | 3 / 6 / 9 / 12 / 18 / 24 / custom                   |
| Deposit               | Max deposit — the filter short-stay users need most |
| Refrigerator attrs    | Single / double door; capacity band                 |
| Washing machine attrs | Top / front load; capacity band                     |
| Bed attrs             | Single / double / queen / king; storage yes-no      |
| Freshness             | Hide prices older than 24h                          |

### Sorting

- Cheapest monthly · Cheapest total for tenure · Lowest deposit · Lowest upfront cash · Recently updated
- **Best value** — the composite score below. It is never the default sort in v1; *cheapest total* is, because it is the one a user can verify by hand.

#### Best-value score

A single ordering over estimated total cost, adjusted for confidence. Deterministic, documented, and shown with a "why this ranked here" breakdown on hover.

    score = normalized(estimatedTotalForTenure)          // dominant term
          + w1 × normalized(cashRequiredUpfront)
          - w2 × freshnessConfidence(scrapedAt)         // stale data ranks lower
          - w3 × providerReliability(30d success rate)

Freshness enters the ranking rather than only the UI: a price we are less sure of should not win a comparison on a technicality.

## 12. Build my rental setup

Most users do not think in SKUs; they think "2BHK, two people, one year". This feature converts a flat description into a basket and runs the standard engine on it. It is the highest-value acquisition surface and the natural landing page for relocation search traffic.

- FR-4.1Inputs: apartment type (1BHK / 2BHK / 3BHK), occupants (1 / 2 / 3+), tenure.
- FR-4.2Output: a suggested basket from a maintained template — e.g. 2BHK/2 people → 2 queen beds, 2 mattresses, 1 refrigerator, 1 washing machine, 1 sofa, 1 dining table, 2 wardrobes.
- FR-4.3The basket is fully editable before comparison. The template is a starting point, not an answer.
- FR-4.4Results show cheapest mixed-provider total, cheapest single-provider total, and the monthly saving between them.
- FR-4.5Templates live in `data/setups.yml` — editing one is a commit, not a code change, and the site rebuilds itself.

## 13. The freshness contract

The original design had a 12-hour TTL, a request-triggered background refresh and a distributed lock to stop five hundred simultaneous visitors from starting five hundred scrapes. With no server, that entire mechanism collapses into one line of YAML.

    on:
      schedule:
        - cron: "0 */12 * * *"     # the TTL, expressed as a schedule
    concurrency:
      group: scrape                # the distributed lock, expressed as a policy
      cancel-in-progress: false

Refresh is no longer something a user can trigger, so there is nothing to stampede. Reads and writes were decoupled by construction the moment the site became static.

_Traffic and refresh cadence are fully independent. The lock existed to protect providers from our own users; with a static site there is no path from a visit to a request against a provider at all._

### Rules

- FR-5.1The pipeline runs on a 12-hour cron. Every price record carries `scrapedAt`, written by the run that produced it.
- FR-5.2Providers are refreshed independently within a run. One provider failing does not abort the others, and each writes its own `scrapedAt`.
- FR-5.3`concurrency.group` guarantees one run at a time. A manual `workflow_dispatch` and the cron share the group, so a hand-triggered run cannot collide with a scheduled one.
- FR-5.4**A failed provider must not commit an empty result.** The run writes the new file only for providers that succeeded; the previous committed values survive untouched and their age keeps climbing. This is what surfaces failure honestly, and it is the single most important rule in the pipeline.
- FR-5.5Scheduled Actions are best-effort and can be delayed under platform load. The UI therefore reports actual data age, never “last updated 12 hours ago” inferred from the schedule.
- FR-5.6Freshness is computed in the browser from `scrapedAt` against the current time — not baked in at build, which would let a cached page claim a price is fresh forever.

### Trust rules for displayed data

| Age of price | UI treatment                                                                                                          |
|--------------|-----------------------------------------------------------------------------------------------------------------------|
| \< 12 h      | Checked 3h ago — normal presentation                                                     |
| 12–24 h      | Normal presentation; a run is due or in flight                                                                        |
| \> 24 h      | Price may be outdated — de-emphasized, excluded from “best price” badges               |
| \> 72 h      | Not verified — excluded from totals and rankings; product still listed with a link out |

Non-negotiable

Never fabricate a price. Never show an unavailable product as available. Never present stale data as current. Every price record must carry `provider`, `providerUrl` and `scrapedAt` — a record missing any of the three fails the build rather than reaching the site.

## 14. Provider integration and its constraints

Each provider is an independent, replaceable adapter behind one interface. No provider may assume it has an API, and no provider's failure may be visible outside its own column.

### Does an official API exist? No.

Checked on 25 Aug 2026: none of the six major providers publishes a developer portal, API documentation, or a partner data API, and no public affiliate product feed was found for any of them. Every integration in Phase 1 is therefore either a permitted crawl or a manual sheet. Partnership outreach is still worth doing — it is the only route that turns a fragile adapter into a stable one — but the plan must not depend on it.

Not an API

Two of the crawlable sites render prices client-side, which means their front ends call internal JSON endpoints that are visible in devtools. These are **not** APIs: undocumented, unversioned, unlicensed, and changeable without notice — and Rentickle’s `robots.txt` explicitly disallows `/api`. Reading them is scraping with less warning when it breaks, and it must not be recorded as `integration_type = API`. Render the permitted page in a browser instead, or negotiate a real feed.

### Extraction preference order

1.  **Official API** — always preferred, always worth asking for. None exists today for any provider.
2.  **Partner or affiliate feed** — comes with commercial alignment, and is the outcome partnership outreach should aim for. None found publicly today.
3.  **Published structured data** — JSON-LD `Product`/`Offer` markup and sitemaps, where crawling that path is permitted.
4.  **Server-rendered HTML** — permitted paths only.
5.  **Headless browser (Playwright)** — only where prices render client-side and the path is permitted. Assume Jsoup alone is insufficient for several of these sites.
6.  **Manual entry** — an admin-maintained price sheet with the same freshness metadata, used as an explicit, labelled fallback.

### Observed crawl posture

`robots.txt` checked directly on 25 Aug 2026. This is a snapshot of a file that changes without notice — the adapter must re-fetch and honour it at runtime, and this table is documentation, not the enforcement mechanism.

| Provider                                           | Listing / product paths under `User-agent: *`                                                                                                                    | Verdict                                   | Route                        |
|----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------|------------------------------|
| **RentoMojo**                                      | Allowed. Disallows only admin, user, checkout, order-summary and blog tags. Publishes per-city sitemaps including Bengaluru.                                     | Crawlable    | Structured data / SSR HTML   |
| **Rentickle**                                      | Allowed. City directories including Bengaluru explicitly permitted. Disallows `/catalog`, `/catalogsearch`, `/api`, `/_next` and all query-string URLs (`/*?`).  | Crawlable    | SSR HTML, clean paths only   |
| **Guarented**                                      | Allowed. Disallows only cart, checkout, dashboard, not-found and tracking-parameter URLs.                                                                        | Crawlable    | SSR HTML / Playwright        |
| **Cityfurnish**                                    | Disallows `/shop`, `/things/*` product paths and city paths including `/*/bangalore/`. Grants named AI crawlers full access, but our crawler is not one of them. | Disallowed | Partnership or manual        |
| **Furlenco**                                       | 21 disallow rules covering product category collections — sofa, bedroom, dining, storage and workspace listing paths.                                            | Disallowed | Partnership or manual        |
| **Payrentz**                                       | Disallows `/productlist/*`, `/product-detail/*`, `/category/*`, `/page/*` and `/tag/*` — effectively the entire catalogue.                                       | Disallowed | Partnership or manual        |
| Rentzy, Cozi Rental, Furnish Rent, Adams, Fabrento | Not yet verified. Bengaluru serviceability and catalogue depth also unconfirmed.                                                                                 | Unverified  | Gate before any adapter work |

Consequence for Phase 1

Three of the five proposed MVP providers are crawlable today; two are not. Building the MVP as specified would either produce two empty columns or require ignoring the providers' own crawl directives. Recommended: ship on RentoMojo, Rentickle and Guarented, open partnership conversations with Cityfurnish and Furlenco in parallel, and give both a manual adapter so their columns are populated and honestly labelled from day one.

A comparison site with three real providers is credible. One with five columns, two of them fabricated or unlawfully obtained, is not.

### Extraction route per provider

Each provider’s Bengaluru pages were fetched on 25 Aug 2026 to establish whether prices exist in the server response or are painted by JavaScript. This decides the adapter’s cost, not just its shape.

| Provider                        | Discovery                                                                                   | Prices in server HTML                                                                                     | Adapter                                       | Effort             |
|---------------------------------|---------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|-----------------------------------------------|--------------------|
| **RentoMojo**                   | Per-city sitemap at `/sitemap/bangalore`; product paths under `/bangalore/furniture/rent-…` | Yes names and monthly prices present in the response                         | HTTP fetch + HTML parse                       | Low                |
| **Guarented**                   | `sitemap.xml` carries clean per-city product URLs                                           | No client-rendered                                                         | Playwright                                    | Medium             |
| **Rentickle**                   | City directories only; `/catalog`, `/_next` and query-string URLs disallowed                | No city context resolved in JS — paths render as `/undefined/…` without it | Playwright, narrow permitted path set         | High               |
| Cityfurnish, Furlenco, Payrentz | —                                                                                           | —                                                                                                         | Manual sheet; partnership pursued in parallel | Ongoing human cost |

Infrastructure consequence — reversed

On a paid always-on server, two Playwright adapters would have meant sizing a container for browser memory and paying for it around the clock. In a GitHub Actions runner that cost is zero: the runner is a full Ubuntu box with 4 vCPU and enough memory, it exists for eight minutes twice a day, and standard runner minutes are free within quota. The expensive option became the free one.

What Playwright still costs is *fragility*, not money. A browser adapter has more ways to break silently than an HTML one, which is why FR-6.4 treats a zero-product result as a failure rather than an empty catalogue.

### Compliance gate — every provider, before an adapter is written

- Read the provider's terms of use; record the date and the relevant clauses in the provider record.
- Fetch and evaluate `robots.txt` for the exact paths the adapter would read.
- Prefer an API or feed; send the partnership request before writing a scraper.
- Identify ourselves in the User-Agent with a contact URL. No spoofing a browser or a named AI crawler.
- Rate-limit conservatively; a 12-hour TTL means a handful of requests per provider per day, not a crawl.
- **Never** bypass CAPTCHA, authentication, paywalls, anti-bot measures or any access control. A provider that blocks us is a provider we stop reading.
- Store only what comparison needs; attribute the source and link users to the original listing.
- Legal sign-off recorded per provider before that adapter ships. This document is not legal advice, and the crawl posture above is one input to that review, not a substitute for it.

### The adapter interface

    public interface RentalProvider {
        Provider getProvider();

        // What this adapter can actually do, so the engine can degrade honestly
        ProviderCapabilities getCapabilities();      // hasApi, supportsDeposit,
                                                     // supportsTenurePricing, isManual

        List<RentalProduct> fetchProducts(String city, RentalCategory category);

        ProviderRefreshResult refresh(String city);  // never throws to the caller;
                                                     // failures are returned as data
    }

- FR-6.1Adapters are isolated: separate thread pool, per-provider timeout and circuit breaker. A hung Playwright session cannot stall the fleet.
- FR-6.2`refresh()` returns a result object carrying counts, warnings and errors. Exceptions are caught at the adapter boundary and converted; the orchestrator never sees a raw provider exception.
- FR-6.3Adapters emit raw provider payloads to the normalizer. They do not write to the price store directly and know nothing of the comparison engine.
- FR-6.4An adapter that returns zero products where it previously returned many is treated as a failure, not as "provider has no stock". Sudden coverage collapse is the primary signal that a page structure changed.
- FR-6.5Swapping a scraper for an official API must be a change to one class and its config, with no change to normalization, pricing or comparison code.

## 15. Making prices comparable

Without normalization there is no product, only a list of links. Providers name the same object four different ways:

    "Single Door Fridge"   "Single Door Refrigerator"
    "190L Refrigerator"    "Samsung 192L Fridge"
                    ↓
    REFRIGERATOR_SINGLE_DOOR  ·  capacity_band: 150-200L

Canonical products carry structured attributes — category, size, storage, material, brand, model, capacity — because comparison happens on attributes, not on strings. A queen bed with storage and a queen bed without are different rows.

### Pipeline

1.  **Extract** — adapter returns the provider's raw fields verbatim, including the original name and URL.
2.  **Parse** — deterministic rules pull capacity, size, door count, load type and brand out of the name and spec block.
3.  **Match** — a rules-plus-fuzzy matcher proposes a canonical product. High-confidence matches auto-link.
4.  **Review** — anything below the confidence threshold lands in an admin review queue. Unmatched items are never guessed into a comparison row.
5.  **Persist** — the accepted mapping is stored in `provider_products` so it is applied automatically on every subsequent refresh.

Estimate honestly

Normalization, not scraping, is where this project will spend its unplanned weeks. Budget for a review queue and a human in it from day one. Auto-matching at 100% is not a realistic goal; auto-matching at 85% with a fast review UI is.

## 16. A pipeline, not a server

The modular monolith survives — same modules, same boundaries, same ArchUnit rules. What changes is its entry point: instead of `SpringApplication.run` serving HTTP forever, it is a `CommandLineRunner` that executes once, writes files, and exits. Spring Boot is still doing the work; it just stops waiting for requests.

_Nothing in this picture runs continuously, and nothing in it has a bill. The two components that would have cost money — a server and a database — are replaced by a cron trigger and the repository itself._

### Where each module ends up

| Module                  | Fate                                                                                        | Runs                               |
|-------------------------|---------------------------------------------------------------------------------------------|------------------------------------|
| `provider/`, `scraper/` | Unchanged                                                                                   | In the Actions runner, twice a day |
| `product/` (normalizer) | Unchanged                                                                                   | Same run                           |
| `pricing/`              | Unchanged, and now more important — it fully resolves every cost so the client never has to | Same run                           |
| `refresh/`              | Mostly deleted. TTL → cron, lock → `concurrency`, queue → the run itself                    | —                                  |
| `comparison/`           | Splits: cost resolution stays in Java, basket selection moves to TypeScript                 | Browser                            |
| `admin/`                | Becomes a generated status page and a pull-request workflow (§19)                           | Build time                         |
| `common/`               | Unchanged                                                                                   | —                                  |

The one rule that makes the split safe

Java resolves **every** cost figure ahead of time — monthly, deposit, fees and estimated total for each of the six tenures, per provider-product. The browser then only ever does two operations: pick the minimum, and add. No business logic is duplicated across languages, because the client has none. If the browser is ever seen doing arithmetic more complicated than `min` and `sum`, a rule has leaked out of `pricing/` and needs to go back.

    rental-comparator/
    ├── .github/workflows/
    │   ├── scrape.yml            cron + concurrency group + commit step
    │   └── keepalive.yml         defeats the 60-day workflow disable (§02, C-2)
    ├── pipeline/                 Java 21 · Spring Boot · CommandLineRunner
    │   ├── provider/  scraper/  product/  pricing/  common/
    │   └── src/test/             golden-file fixtures per adapter
    ├── data/                     the database
    │   ├── prices.json           resolved costs, all tenures
    │   ├── catalogue.json        canonical products + attributes
    │   ├── mappings.json         provider listing → canonical product
    │   ├── manual/*.yml          hand-maintained providers
    │   └── runs.json             per-provider outcome of the last run
    └── web/                      Next.js static export → Pages

- NFR-A1`pricing/` must not import from `provider/` or `scraper/`. ArchUnit test in CI, as before.
- NFR-A2`pricing/` stays a pure function — no I/O, no clock, no randomness — which is what lets its whole output be serialized to a file.
- NFR-A3The pipeline is a plain CLI with no dependency on GitHub. `./run.sh` on any machine produces the same `data/` output, which is the escape hatch if the free tier ever changes.
- NFR-A4The web build must fail if `data/` is malformed or a record is missing provenance. A broken build is a visible failure; a build that silently ships bad data is not.

## 17. The repository is the database

PostgreSQL is gone. The tables it would have held become JSON files that the pipeline writes and the site reads — and because they are versioned, three problems solve themselves for free.

#### Price history

`git log -p data/prices.json` is the full time series, timestamped and attributable, from the first run onward. No table, no migration, no retention policy.

#### Auditability

Every price change is a diff with an author and a commit message. “When did this jump ₹100?” is a `git blame`.

#### Rollback

A bad scrape that mangles the catalogue is one `git revert` away from being off the site.

| File                      | Written by                | Shape                                                                                                                                                 |
|---------------------------|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| data/catalogue.json       | Normalizer                | Canonical products: id, category, brand, model, size, attributes                                                                                      |
| data/mappings.json        | Normalizer + human review | provider listing → canonical product, with name, URL and match confidence. Reviewed changes arrive by pull request.                                   |
| data/prices.json          | Pricing module            | One record per (listing, tenure): monthly, deposit, delivery, installation, other, discount, resolved total, availability, `scrapedAt`, `providerUrl` |
| data/manual/\*.yml        | A human, by hand          | Same fields, for the three providers we may not crawl. `scrapedAt` is the commit date, so a neglected sheet goes stale visibly.                       |
| data/runs.json            | Every run                 | Per provider: started, finished, status, products found, error, coverage vs. previous run                                                             |
| data/pending-matches.json | Normalizer                | Listings below the confidence threshold, awaiting review. Never reaches the site.                                                                     |

### Constraints, now enforced by the build

- A price record missing `providerUrl`, `provider` or `scrapedAt` fails the build. Attribution was a `NOT NULL`; it is now a schema validation step.
- Money stays integer paise. No floats in the pipeline or in the JSON.
- Costs are pre-resolved for all six tenures at write time, so the client never computes a total.
- Files are written sorted and pretty-printed with a stable key order — otherwise every run produces a meaningless whole-file diff and the git-as-history trick stops being readable.
- A run that would delete more than a set share of an existing provider’s products fails instead of committing. Catastrophic diffs are the signature of a broken adapter, and the point of using git is that this is checkable before it ships.

Where this design runs out

This works because the dataset is small — a few hundred products, a few hundred kilobytes. It stops working somewhere around tens of megabytes, or when two writers need to touch the data at once, or when the site needs to query rather than load. None of those are on this project’s path. If one ever is, the migration is a real database and an import script, and the pipeline above does not change.

## 18. Data contract, not an API

There are no endpoints, because there is no server. The site loads static JSON and does the rest in the browser. That inverts one thing worth being explicit about: the whole price dataset is public and downloadable by anyone who opens devtools. For this project that is acceptable — arguably a feature — but it must be a decision rather than a discovery.

| Route                          | Serves                                                            |
|--------------------------------|-------------------------------------------------------------------|
| /data/catalogue.json           | Canonical products and attributes, loaded once                    |
| /data/prices.json              | Resolved costs for every listing and tenure                       |
| /data/runs.json                | Pipeline health, powering the freshness banner and status page    |
| /data/history/{productId}.json | Price points for one product, generated at build from git history |
| /bangalore/{category}-on-rent  | Pre-rendered category page (§22)                                  |
| /status                        | Generated pipeline status page (§19)                              |

Comparison, filtering, sorting and basket selection all run client-side over the loaded data. At a few hundred products this is instantaneous and needs no index — the p95 latency requirement from the original design is satisfied by there being no network call to make.

- FR-8.1Split `prices.json` per category if it passes roughly 300 KB uncompressed, so a visitor comparing fridges does not download the sofas.
- FR-8.2Data files are content-hashed at build so a new deploy invalidates the cache; a visitor must never see yesterday’s prices with today’s freshness label.
- FR-8.3The query state stays in the URL, so a comparison remains shareable with no server to store it.

## 19. Admin without an admin panel

An admin dashboard with login, roles and mutation endpoints was the other thing the server was carrying. Both of its jobs have cheaper homes, and neither needs authentication because neither writes anything a stranger could reach.

### Monitoring → a generated status page

`/status` is rendered at build time from `runs.json`. It is public, read-only, and has nothing to protect.

| Provider    | Last success | Status                                  | Products | Detail                                   |
|-------------|--------------|-----------------------------------------|----------|------------------------------------------|
| RentoMojo   | 2h ago       | OK         | 182      | —                                        |
| Guarented   | 2h ago       | Degraded | 93       | Coverage down 38% vs. previous run       |
| Rentickle   | 26h ago      | Failing  | —        | Selector returned no nodes — last 2 runs |
| Cityfurnish | 5d ago       | Stale    | 41       | Manual sheet not updated                 |

Layout illustration. Figures are not real.

### Review queue → a pull request

When the normalizer cannot confidently match a listing, the run opens a pull request adding the proposed mapping to `mappings.json`. Reviewing means reading a diff and clicking merge; the next run picks it up. Manual price entry is the same motion — edit a YAML file, commit.

This is a better review tool than the dashboard would have been: it has history, comments, blame and revert built in, it works from a phone, and it costs nothing.

- FR-7.1Every run writes per-provider outcome, product count and coverage delta against the previous run.
- FR-7.2A failed run fails the workflow, so GitHub sends its standard failure email. That is the entire alerting system.
- FR-7.3Alert conditions beyond hard failure: coverage drop past threshold, a provider stale past 24h, or a price change outside a plausibility bound — a ₹599 bed at ₹59 is a parse bug, not a sale.
- FR-7.4Unmatched listings open a pull request; they never reach the site unreviewed.
- FR-7.5Disabling a provider is a flag in config plus a commit — its column disappears on the next build, with no deploy pipeline to wait for.
- FR-7.6The status page is linked from the site footer. If the pipeline has been dead for a week, a visitor can find that out.

20 — Non-functional

## 20. Non-functional requirements

| Area             | Requirement                                                                                                                                             |
|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Latency          | Comparison is instant — a static file load and an in-memory `min`. The original 500 ms p95 target is met by there being no request to make.             |
| Frontend         | Interactive within 2.5 s on a mid-range Android over 4G. Data payload under 300 KB uncompressed per category.                                           |
| Partial failure  | One provider failing degrades one column with an explicit label. A failed adapter never overwrites good data (FR-5.4).                                  |
| Pipeline runtime | A full run finishes inside 15 minutes, comfortably under the Actions job timeout and inside the monthly minute budget.                                  |
| Politeness       | A handful of requests per provider per run, twice a day. Exponential backoff on 429/5xx; the run fails loudly rather than retrying into a block.        |
| Accessibility    | WCAG AA. The comparison matrix uses real `<table>` semantics with scoped headers.                                                                       |
| Mobile           | Card layout below 900px. No horizontal page scroll at 360px.                                                                                            |
| Observability    | Run logs live in the Actions run history — free, searchable, retained. `runs.json` carries the structured summary.                                      |
| Testing          | Golden-file tests per adapter against captured HTML fixtures, so a markup change fails CI rather than the site. Property tests on the pricing function. |
| Portability      | The pipeline runs identically on a laptop. No GitHub-specific code outside the workflow YAML.                                                           |

## 21. Security, mostly by subtraction

Most of the original security section protected things that no longer exist. There is no server to attack, no admin login to brute-force, no database to inject into, and no user data to leak — the site collects nothing and stores nothing. What is left is short and real.

- **Untrusted strings.** Product names come from third-party websites. They are the one genuine injection vector left: escape on render, and never pass scraped HTML through `dangerouslySetInnerHTML`.
- **Schema validation at build.** Malformed scraped data should fail the build, not reach the page. This doubles as the integrity check in §17.
- **Repository secrets.** No provider credentials in the pipeline at all — we read only public pages. If that ever changes, credentials go in Actions secrets, never in `data/` or the committed config.
- **Private repository.** Keeping the repo private hides the adapter internals and the scraped dataset. A public repo makes them a portfolio piece and gives unlimited Actions minutes; both are defensible, and the choice should be made deliberately rather than by default.
- **Workflow permissions.** The scrape workflow needs `contents: write` and nothing more. Pin third-party actions to a commit SHA rather than a tag.
- **Outbound links** carry `rel="noopener noreferrer"`.

The threat that actually applies to this project is not an attacker — it is publishing a wrong number with confidence. §13 and §17 are the security-relevant sections.

## 22. SEO, and the moment money changes things

### SEO

A statically generated site is the best possible shape for this. Each page below is pre-rendered at build from the same data the comparison uses, with real prices and a visible last-checked timestamp.

    /bangalore/furniture-on-rent          /bangalore/sofa-on-rent
    /bangalore/bed-on-rent                /bangalore/rentomojo-vs-guarented
    /bangalore/mattress-on-rent           /bangalore/2bhk-furniture-rental
    /bangalore/refrigerator-on-rent       /bangalore/bachelor-room-setup
    /bangalore/washing-machine-on-rent    /bangalore/cheap-furniture-rental

No page is generated without data behind it. A thin page with an empty table is worse than no page. Since the site rebuilds twice a day, every page is as fresh as the data — which is a genuine advantage over the providers’ own static landing pages.

### Revenue, and why it is out of scope

The moment an affiliate link appears, three things change at once, and it is worth seeing them together before deciding it is worth it:

| What changes                        | Consequence                                                                                                                                                                                                                                    |
|-------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Vercel Hobby no longer applies      | Their terms restrict Hobby to non-commercial personal use. Either \$20/mo for Pro, or host on Cloudflare Pages, which has no equivalent clause. **Building on Cloudflare Pages from day one keeps this door open for free.**                   |
| The crawl posture changes character | Reading a provider’s public pages for a hobby project and doing it to earn commission are not the same conversation with that provider. §14’s compliance gate would need revisiting per provider.                                              |
| Neutrality needs defending          | Sponsored status must still carry zero weight in any ranking or “cheapest” calculation, sponsored results must be labelled, and commercial relationships disclosed. Easy to write, harder to hold once one provider pays and another does not. |

Decision

No revenue in any phase. The project stays non-commercial, which keeps every free tier valid, keeps the provider conversation simple, and removes the only real threat to the integrity rule. The one design choice made now to preserve optionality is hosting on Cloudflare Pages rather than Vercel.

## 23. Phasing and acceptance

The goal is the whole system built properly, not a demo — so the phases keep the full architecture and stage it by provider count and feature depth rather than by cutting corners.

### Phase 0 — The skeleton that proves the loop

One provider, one category, end to end. This exists to prove the pipeline shape before any adapter effort is sunk into it.

- AC-0.1Actions cron runs the Java CLI, scrapes RentoMojo refrigerators, writes `data/prices.json`, commits, and the site rebuilds — unattended, twice in a row.
- AC-0.2The site shows those prices with a live-computed age.
- AC-0.3Killing the adapter mid-run leaves the previous committed data intact (FR-5.4).
- AC-0.4Total spend: ₹0. Actions minutes used are recorded as a baseline.

### Phase 1 — MVP

**Providers:** RentoMojo (HTML), Guarented and Rentickle (Playwright); Cityfurnish, Furlenco and Payrentz as manual YAML sheets, labelled as such. **Categories:** bed, mattress, refrigerator, washing machine.

- AC-1.1Four items and a 12-month tenure produce a populated matrix with at least 2 providers priced per row.
- AC-1.2Cheapest cross-provider basket and cheapest single-provider basket both shown, with the delta.
- AC-1.3Every price shows monthly, estimated total for the tenure, deposit separately, and a checked-at timestamp.
- AC-1.4Every price links to the provider’s own listing.
- AC-1.5All cost figures are resolved in Java; the browser only picks minima and sums. Verified by inspection of the client bundle.
- AC-1.6`/status` is live and linked from the footer.
- AC-1.7A manual audit of 20 sampled prices against provider sites matches at ≥ 97%.
- AC-1.8Compliance record complete per shipped provider: terms reviewed, robots checked, route recorded, dated.
- AC-1.9Keepalive workflow in place and verified against the 60-day disable.

### Phase 2 — Depth

All 15 categories · filters and sorting · price history from git · Build my setup · pincode serviceability · SEO pages · the pull-request review queue.

- AC-2.1Price history is generated by walking git history. It renders only where real collected points exist — no backfill, no interpolation. An empty chart reading “collecting since \<date\>” is the correct output.
- AC-2.2“Lowest in the last 90 days” appears only once 90 days of runs exist.
- AC-2.3An unmatched listing opens a pull request and stays off the site until merged.
- AC-2.4Data files stay under the payload budget, or are split per category.

### Phase 3 — If it is still fun

Natural-language search · multi-city · price alerts.

- AC-3.1Natural-language search converts free text to a structured query and hands it to the existing engine. The model never computes or estimates a price.
- AC-3.2Price alerts have no free serverless home worth the complexity. If built, they are a second Actions workflow that diffs the last two commits of `prices.json` and sends an email — not a subscription system.
- AC-3.3Adding a second city requires config and serviceability data only, no code change.

## 24. Risks

| Risk                                                                                                                                       | Impact                                | Mitigation                                                                                                                                                                                                       |
|--------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **The cron silently stops.** GitHub disables scheduled workflows after 60 days of repo inactivity, and bot commits may not reset the clock | High   | The classic failure of an unattended hobby project: the site looks fine and the data is three months old. Keepalive workflow, plus the age label and `/status` making staleness visible to anyone, including me. |
| **Provider crawl restrictions** block half the catalogue                                                                                   | High   | Confirmed for 3 of 6 majors (§14). Ship on the crawlable three; manual sheets for the rest; re-check robots at runtime every run.                                                                                |
| **Normalization is wrong** and comparisons mislead                                                                                         | High   | Confidence-gated matching, pull-request review, accuracy audit as a release gate, no auto-match below threshold.                                                                                                 |
| **Adapters break silently** on a redesign                                                                                                  | Medium | Zero results treated as failure, coverage-delta check, plausibility bounds, catastrophic-diff guard before commit, golden-file tests in CI.                                                                      |
| **Free tier terms change**                                                                                                                 | Medium | The pipeline is a plain CLI and the data is files in a repo. Worst case it runs on a laptop with a cron entry (NFR-A4). Hosting on Cloudflare Pages avoids the one clause that already bites.                    |
| **Manual sheets go stale** because nobody is paid to update them                                                                           | Medium | Their `scrapedAt` is the commit date, so they age visibly and drop out of rankings past 72h like any other stale price. A neglected column removes itself rather than lying.                                     |
| **Interest fades** and the project is abandoned mid-way                                                                                    | Medium | The realistic end state for a personal project. Phase 0 is deliberately a complete working loop, so an abandoned project is still a working one. Nothing accrues cost while ignored.                             |
| **Anti-bot escalation** after the site gets noticed                                                                                        | Low     | Twice-daily polite requests from a labelled agent are unlikely to trigger it. If blocked, the provider becomes manual — never evasion.                                                                           |

## 25. Open questions

Three questions from the first draft are now answered: the stack stays Java (as a scheduled CLI, not a server), the goal is the full system rather than a quick demo, and there is no revenue model. What remains:

Public repo or private?

Public gives unlimited Actions minutes and makes the project a portfolio piece; it also publishes every adapter and the scraped dataset, which providers can read. Private keeps both hidden and still fits comfortably in the 2,000-minute quota at roughly 480 minutes a month. **Needed before the first commit** — changing later leaks history either way.

Is Rentickle worth building in Phase 1?

It is the hardest adapter of the three — client-rendered, city context resolved in JS, with `/catalog`, `/_next` and query-string URLs all disallowed, leaving a narrow permitted path set. Two crawled providers plus manual sheets is a thinner but honest launch. Consider deferring it to Phase 2 and spending the time on normalization instead, which is where the real difficulty lives.

How often do the manual sheets actually get updated?

Three of six providers depend on someone hand-editing YAML. Realistically that happens weekly at best, which means those columns will usually sit in the “may be outdated” band. Decide now whether to ship them at all, or launch with the three crawlable providers and treat manual entry as an experiment — a permanently stale column may be worse than an absent one.

Does tenure pricing exist per provider, or do we derive it?

If a provider publishes only a 12-month rate, an honest 3-month comparison for them is impossible. Confirm per provider during adapter discovery; the fallback is an explicit “not published for this tenure”, never a derived figure.

Is deposit in or out of the headline “cheapest” number?

This document ranks on non-refundable total with deposit shown alongside. Ranking on total cash outlay instead would favour low-deposit providers and change the answer for short tenures. Worth testing on a few real people before Phase 1 ships.

Where do bundle and threshold discounts come from?

Free delivery above a basket value, and multi-item discounts, are often disclosed only at checkout. If the rules cannot be read, the basket total is an estimate and must be labelled as one.

What is it called, and does it need a domain?

A `*.pages.dev` subdomain keeps the running cost at exactly zero. A custom domain is around ₹1,000 a year and is the only line item that would break that. Worth deciding whether the project is for an audience or for the building of it — the answer changes whether the domain matters at all.

CompareFurniture — Product Requirements, Draft v1.1 · 25 August 2026 · Bengaluru · personal project, ₹0/month.  
All prices, provider figures and status rows shown in this document are layout illustrations. Crawl-posture findings in §14 were read from each provider’s live `robots.txt` on 25 Aug 2026; free-tier limits in §02 were read from the providers’ own documentation the same day. Neither is legal advice, and both change without notice.
