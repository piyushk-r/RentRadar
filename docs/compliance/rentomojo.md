# Compliance record — RentoMojo

Per the compliance gate in PRD §14. This record is one input to a legal
review, not a substitute for it.

| Field | Value |
|---|---|
| Provider | RentoMojo (`rentomojo`) |
| Route | Server-rendered HTML on permitted paths (`SCRAPE_HTML`) |
| Checked on | 25 Aug 2026 |
| Checked by | bookzee.in@gmail.com |
| robots.txt | `User-agent: *` disallows only `/admins/`, `/admin-preview/`, `/order/summary/`, `/user/`, `/checkout/`, `/email_login_client`, `/blog/tag/`. Category and product paths are permitted. Snapshot: `pipeline/src/test/resources/fixtures/rentomojo/robots.txt`. Re-fetched and honoured at runtime on every run by `PoliteHttpClient`. |
| Paths read | `/bangalore/appliances/refrigerators-on-rent` (listing) and `/bangalore/appliances/rent-*/{id}` (product pages) |
| Request volume | ~9 requests per run, twice a day, ≥2.5 s apart, exponential-style backoff then loud failure on 429/5xx |
| User-Agent | `RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; personal, non-commercial price comparison; contact: bookzee.in@gmail.com)` — no browser or named-crawler spoofing |
| API | None published (no developer portal / partner feed found on 25 Aug 2026). The Nuxt SSR state embedded in the served page is parsed; no internal JSON endpoint is called. Recorded as scraping, not API. |
| Access controls | No CAPTCHA, login, paywall or anti-bot measure is bypassed. If RentoMojo blocks us, we stop reading (PRD §14). |
| Data stored | Only what comparison needs: name, URL, image URL, category, availability, published tenure prices, deposit, installation charge, `scrapedAt`. Users are linked to the original listing on every price. |
| Terms of use | To review and record relevant clauses before Phase 1 ships (AC-1.8). Phase 0 is a private, non-commercial pipeline validation. |
| Partnership | Outreach worth sending before Phase 1 — an official feed is the only stable integration (PRD §14). |
