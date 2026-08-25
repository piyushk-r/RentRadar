# Compliance record — Cityfurnish, Furlenco, Payrentz (manual route)

Checked 25 Aug 2026 (PRD §14) and re-verified in the PRD's crawl-posture
table: each of these providers' `robots.txt` disallows the catalogue paths a
price crawl would need:

- **Cityfurnish** — disallows `/shop`, `/things/*`, and city paths including `/*/bangalore/`.
- **Furlenco** — 21 disallow rules covering the product category collections.
- **Payrentz** — disallows `/productlist/*`, `/product-detail/*`, `/category/*`, effectively the whole catalogue.

**Consequence:** no automated fetching of any kind, including "just once" or
via a headless browser. These providers enter the comparison only through
hand-maintained sheets in `data/manual/*.yml` — prices a human read on the
provider's site in an ordinary browser and typed in, with the sheet's
`updatedAt` as the freshness timestamp and every entry linking to the page it
was read on. Columns from manual sheets are labelled as such and age
visibly; a neglected sheet drops out of rankings past 72 h like any other
stale data.

**Partnership** is the real route for these three (PRD §14): a feed or API
conversation converts a manual column into a stable one. Outreach should be
sent before any public launch.
