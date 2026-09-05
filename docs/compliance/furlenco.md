# Compliance record — Furlenco

Per the compliance gate in PRD §14. One input to a legal review, not a
substitute for it.

| Field | Value |
|---|---|
| Provider | Furlenco (`furlenco`) |
| Route | Headless-browser rendering of permitted city category pages (`SCRAPE_BROWSER`) — prices are client-rendered; no internal JSON endpoint is called directly |
| Checked on | 4 Sep 2026 |
| Checked by | bookzee.in@gmail.com |
| robots.txt | `User-Agent: *` with **21 `Disallow` rules**, all of them legacy collection paths (`/3-seater-sofa-on-rent`, `/bedroom-combos-on-rent`, `/z-*-on-rent`, …). It also advertises `Sitemap: https://www.furlenco.com/sitemap.xml`. Cross-checked every one of the sitemap's **948 URLs against those rules: zero are disallowed**, including the `/bengaluru/…` category pages this adapter reads. Re-fetched and honoured at runtime before every fetch and every browser navigation (`PoliteHttpClient.requireAllowed`). |
| Correction to PRD §14 | That table (snapshotted 25 Aug 2026) records Furlenco as "Disallowed → partnership or manual", reading the 21 rules as covering the catalogue. They do not cover the per-city pages, which is why this adapter exists. The verdict was too broad, not wrong in spirit — the specific collection paths it named are still closed and are not read. |
| Discovery | Published sitemap → per-city category pages (`/bengaluru/refrigerators-on-rent` and 9 more). Product pages are not fetched: the category cards already carry name, price and listing URL, which is one request per category instead of one per product. |
| Delivery-location gate | Product content sits behind a "Select Delivery Location" modal; until a city is chosen the page renders skeleton placeholders. The adapter answers it by typing a Bengaluru pincode into the page's own field, as a visitor does. This is the site's published UI, not an access control — no login, CAPTCHA, paywall or anti-bot measure is involved or circumvented. |
| Request volume | ~10 page renders per run, twice a day, ≥2.5 s between navigations. |
| User-Agent | `RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; …)` set on the browser context — no spoofed browser identity |
| API | None published. |
| Access controls | None bypassed; if Furlenco blocks us, we stop reading. |
| Data stored | Name, URL, image URL, category, published monthly price, list price, `scrapedAt`. Every price links to the original listing. |
| **Pricing model — open question** | Furlenco publishes **one monthly rate per product with no tenure selector on the page**, and no minimum-tenure statement was found in the rendered DOM. That rate is recorded as published against each display tenure, and `rawAttributes.pricing_model` says so on every listing. Nothing is derived — but if Furlenco enforces a minimum tenure at checkout, the shorter-tenure rows would be misleading and should be dropped. **Confirm the minimum tenure before public launch.** |
| Bundles | Combos, trios and multi-item cards are excluded, as with Guarented: a bundle priced as one line is not a comparable single product. |
| Terms of use | To review and record relevant clauses before public launch (AC-1.8). |
| Partnership | Worth sending — an official feed is the only stable integration. |
