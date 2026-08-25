# Manual price sheets

Cityfurnish, Furlenco and Payrentz disallow crawling their catalogue paths
(PRD §14), so their prices can only enter through hand-maintained YAML sheets
here — one file per provider, same fields as scraped records.

Not populated in Phase 0. When these sheets exist, their `scrapedAt` is the
commit date, so a neglected sheet goes stale visibly and drops out of rankings
past 72 h like any other stale price (PRD §17).
