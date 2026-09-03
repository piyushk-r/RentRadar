# Compliance record — Rentickle

Per the compliance gate in PRD §14. One input to a legal review, not a
substitute for it.

| Field | Value |
|---|---|
| Provider | Rentickle (`rentickle`) |
| Route | **No adapter.** Manual sheet only (`data/manual/rentickle.yml`), same footing as Cityfurnish / Furlenco / Payrentz. |
| Checked on | 27 Aug 2026 |
| Checked by | bookzee.in@gmail.com |
| Finding | `https://www.rentickle.com/robots.txt` and the homepage both answer **HTTP 429 with a Vercel Security Checkpoint** (anti-bot challenge) to our identified `RentRadarBot/0.1` User-Agent. The robots file itself is unreadable behind the challenge. |
| Consequence | The gate is explicit: never bypass an anti-bot measure, never spoof a browser or a named AI crawler, and a provider that blocks us is a provider we stop reading. A challenge page in front of `robots.txt` means we cannot even establish permission, so no adapter is written (the PRD §14 crawl-posture table, snapshotted 25 Aug 2026, predates this checkpoint). |
| Re-check | The checkpoint may be a temporary attack-mode toggle. Re-run the two `curl` checks before any future adapter work; if robots.txt becomes readable again, the Phase 1 posture (Playwright over the narrow permitted path set, city directories only) applies. |
| Data stored | Only what a human types into the manual sheet from pages read in an ordinary browser, `updatedAt` bumped on every edit. |
| Partnership | The right route while the checkpoint stands — an official feed request costs one email. |
