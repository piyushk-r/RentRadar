// Filtering and ordering over rows the pipeline already priced (PRD §11 and
// §18: filtering and sorting run client-side over loaded data). Nothing here
// computes a price — every rupee figure was resolved in Java; this module only
// picks minima and orders them. The one composite, the best-value score, is an
// *ordering* over those precomputed totals (§11), never a displayed price, and
// it is never the default sort — cheapest total is, because a user can verify
// it by hand.

import type { Cell, Row } from './compare';
import { freshnessBand } from './freshness';
import type { PriceRecord, ProviderStats } from './types';

export type SortMode =
  | 'cheapest-total'
  | 'cheapest-monthly'
  | 'lowest-deposit'
  | 'lowest-upfront'
  | 'recently-updated'
  | 'best-value';

export const SORT_LABELS: Record<SortMode, string> = {
  'cheapest-total': 'Cheapest total for tenure',
  'cheapest-monthly': 'Cheapest monthly',
  'lowest-deposit': 'Lowest deposit',
  'lowest-upfront': 'Lowest upfront cash',
  'recently-updated': 'Recently updated',
  'best-value': 'Best value',
};

export interface RowFilters {
  monthlyMinPaise: number | null;
  monthlyMaxPaise: number | null;
  depositMaxPaise: number | null;
  /** product attribute equality, from the catalogue (door_type, size, …) */
  attributes: Record<string, string>;
}

/** Every priced record on the row, one per provider column. */
function pricedRecords(row: Row): PriceRecord[] {
  const records: PriceRecord[] = [];
  for (const cell of Object.values(row.cells) as Cell[]) {
    if (cell.state === 'priced' && cell.record) records.push(cell.record);
  }
  return records;
}

/**
 * The row's best record *for a given measure*. Sorting by lowest deposit has
 * to look at each row's lowest deposit, not at the deposit of whichever
 * provider happens to win on total — otherwise the ordering answers a
 * different question than the one the user asked.
 */
export function bestRecordOfRow(row: Row, measure: (r: PriceRecord) => number = (r) => r.estimatedTotalPaise) {
  let best: PriceRecord | null = null;
  for (const record of pricedRecords(row)) {
    if (!best || measure(record) < measure(best)) best = record;
  }
  return best;
}

export function filterRows(rows: Row[], filters: RowFilters): Row[] {
  return rows.filter((row) => {
    for (const [key, value] of Object.entries(filters.attributes)) {
      if (row.product.attributes[key] !== value) return false;
    }
    const records = pricedRecords(row);
    if (records.length === 0) {
      // Rows with no priced cell only survive when no price filter is active.
      return filters.monthlyMinPaise == null && filters.monthlyMaxPaise == null && filters.depositMaxPaise == null;
    }
    // A row survives if *any* provider satisfies the filters — hiding a row
    // because its cheapest-total column breaches a deposit cap would hide the
    // column that meets it.
    return records.some((record) => {
      if (filters.monthlyMinPaise != null && record.monthlyPaise < filters.monthlyMinPaise) return false;
      if (filters.monthlyMaxPaise != null && record.monthlyPaise > filters.monthlyMaxPaise) return false;
      if (filters.depositMaxPaise != null && record.depositPaise > filters.depositMaxPaise) return false;
      return true;
    });
  });
}

// ---- the best-value score (§11) ----
//
//   score = normalized(estimatedTotalForTenure)     // dominant term
//         + W_UPFRONT × normalized(cashRequiredUpfront)
//         − W_FRESH   × freshnessConfidence(scrapedAt)
//         − W_RELIABLE × providerReliability(30d success rate)
//
// Deterministic and documented; the breakdown is exposed so the UI can show
// "why this ranked here" (§11). Lower is better.

const W_UPFRONT = 0.3;
const W_FRESH = 0.15;
const W_RELIABLE = 0.1;

function freshnessConfidence(scrapedAt: string, now: Date): number {
  switch (freshnessBand(scrapedAt, now)) {
    case 'fresh':
      return 1;
    case 'due':
      return 0.8;
    case 'outdated':
      return 0.4;
    default:
      return 0; // unverified records are already out of rankings entirely
  }
}

export interface ScoredRow {
  row: Row;
  score: number | null;
  /** the "why this ranked here" breakdown, one line per term */
  why: string | null;
}

function normalizer(values: number[]): (v: number) => number {
  const min = Math.min(...values);
  const max = Math.max(...values);
  return max > min ? (v: number) => (v - min) / (max - min) : () => 0;
}

/** The measure each sort mode ranks on; the row's best cell for it wins. */
const MEASURES: Record<SortMode, (r: PriceRecord) => number> = {
  'cheapest-total': (r) => r.estimatedTotalPaise,
  'cheapest-monthly': (r) => r.monthlyPaise,
  'lowest-deposit': (r) => r.depositPaise,
  'lowest-upfront': (r) => r.cashUpfrontPaise,
  'recently-updated': (r) => -new Date(r.scrapedAt).getTime(),
  'best-value': (r) => r.estimatedTotalPaise,
};

export function sortRows(
  rows: Row[],
  mode: SortMode,
  now: Date,
  stats: ProviderStats | null,
): ScoredRow[] {
  const measure = MEASURES[mode] ?? MEASURES['cheapest-total'];
  const withBest = rows.map((row) => ({ row, best: bestRecordOfRow(row, measure) }));
  const priced = withBest.filter((r) => r.best !== null);
  const unpriced = withBest.filter((r) => r.best === null).map(({ row }) => ({ row, score: null, why: null }));

  if (mode === 'best-value') {
    const normTotal = normalizer(priced.map((r) => r.best!.estimatedTotalPaise));
    const normUpfront = normalizer(priced.map((r) => r.best!.cashUpfrontPaise));
    const scored: ScoredRow[] = priced.map(({ row, best }) => {
      const reliability = stats?.providers[best!.provider]?.successRate30d ?? 1;
      const fresh = freshnessConfidence(best!.scrapedAt, now);
      const terms = {
        total: normTotal(best!.estimatedTotalPaise),
        upfront: W_UPFRONT * normUpfront(best!.cashUpfrontPaise),
        fresh: W_FRESH * fresh,
        reliable: W_RELIABLE * reliability,
      };
      const score = terms.total + terms.upfront - terms.fresh - terms.reliable;
      const why = [
        `total for tenure: ${terms.total.toFixed(2)} (dominant)`,
        `+ upfront cash × ${W_UPFRONT}: ${terms.upfront.toFixed(2)}`,
        `− freshness × ${W_FRESH}: ${terms.fresh.toFixed(2)}`,
        `− provider reliability × ${W_RELIABLE}: ${terms.reliable.toFixed(2)}`,
        `= ${score.toFixed(2)} (lower ranks higher)`,
      ].join('\n');
      return { row, score, why };
    });
    scored.sort((a, b) => a.score! - b.score! || a.row.product.name.localeCompare(b.row.product.name));
    return [...scored, ...unpriced];
  }

  const plain: ScoredRow[] = priced
    .sort((a, b) => measure(a.best!) - measure(b.best!) || a.row.product.name.localeCompare(b.row.product.name))
    .map(({ row }) => ({ row, score: null, why: null }));
  return [...plain, ...unpriced];
}
