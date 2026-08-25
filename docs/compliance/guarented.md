# Compliance record — Guarented

Per the compliance gate in PRD §14. One input to a legal review, not a
substitute for it.

| Field | Value |
|---|---|
| Provider | Guarented (`guarented`) |
| Route | Headless-browser rendering of permitted pages (`SCRAPE_BROWSER`) — prices are painted client-side; no internal JSON endpoint is called directly |
| Checked on | 26 Aug 2026 |
| Checked by | bookzee.in@gmail.com |
| robots.txt | `User-agent: *` disallows only `/cart`, `/checkout`, `/info/dashboard`, `/not-found`, and tracking-parameter URLs (`/*?utm_`, `/*?gclid=`). Category and product paths are permitted. Re-fetched and honoured at runtime before every fetch and every browser navigation (`PoliteHttpClient.requireAllowed`). |
| Discovery | Published `sitemap.xml` (permitted) — product URLs under `/bangalore/rent/furniture/beds/`, `/furniture/mattresses/`, `/appliances/fridges/`, `/appliances/washing-machine/`. Combos excluded. |
| Request volume | ~40 page renders per run, twice a day, ≥2.5 s between navigations. Per-product tenure options are read by driving the page's own selector, as a user would. |
| User-Agent | `RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; …)` set on the browser context — no spoofed browser identity |
| API | None published. |
| Access controls | None bypassed; if Guarented blocks us, we stop reading. |
| Data stored | Name, URL, image URL, category, availability, published tenure-band prices, deposit, `scrapedAt`. Every price links to the original listing. |
| Tenure model | Guarented prices by commitment band ("3+ / 6+ / 12+ / 31+ months"). A display tenure is priced by the band containing it — a lookup of their published price, not a derived figure. Raw bands are recorded on each listing. |
| Terms of use | To review and record relevant clauses before public launch (AC-1.8). |
| Partnership | Outreach worth sending — an official feed is the only stable integration. |
