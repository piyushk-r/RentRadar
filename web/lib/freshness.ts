// The freshness contract (PRD section 13), computed in the browser from
// scrapedAt against the current time — never baked in at build (FR-5.6).

export type FreshnessBand = 'fresh' | 'due' | 'outdated' | 'unverified';

const HOUR_MS = 60 * 60 * 1000;

export function ageMs(scrapedAt: string, now: Date): number {
  return now.getTime() - new Date(scrapedAt).getTime();
}

export function freshnessBand(scrapedAt: string, now: Date): FreshnessBand {
  const hours = ageMs(scrapedAt, now) / HOUR_MS;
  if (hours < 12) return 'fresh';
  if (hours < 24) return 'due';
  if (hours < 72) return 'outdated';
  return 'unverified';
}

/** Excluded from best-price badges past 24h (PRD section 13 trust rules). */
export function eligibleForBestPrice(band: FreshnessBand): boolean {
  return band === 'fresh' || band === 'due';
}

/** Excluded from totals and rankings past 72h; still listed with a link out. */
export function eligibleForTotals(band: FreshnessBand): boolean {
  return band !== 'unverified';
}

export function formatAge(scrapedAt: string, now: Date): string {
  const ms = ageMs(scrapedAt, now);
  if (ms < 0) return 'just now';
  const minutes = Math.floor(ms / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}
