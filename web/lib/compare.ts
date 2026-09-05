// The browser's entire share of the comparison: pick the minimum, and add
// (PRD section 16, "the one rule that makes the split safe"). If arithmetic
// more complicated than min and sum appears here, a rule has leaked out of
// the Java pricing module and needs to go back.

import { eligibleForBestPrice, eligibleForTotals, freshnessBand, type FreshnessBand } from './freshness';
import type { CatalogueEntry, PriceRecord } from './types';

export type CellState = 'priced' | 'not-offered' | 'not-published-for-tenure' | 'out-of-stock';

export interface Cell {
  state: CellState;
  record: PriceRecord | null;
  band: FreshnessBand | null;
  isBest: boolean;
  /** For not-published-for-tenure: a link target so the user can still reach the provider page. */
  anyTenureRecord: PriceRecord | null;
}

export interface Row {
  product: CatalogueEntry;
  cells: Record<string, Cell>;
}

export interface ProviderTotal {
  itemsCovered: number;
  itemsRequested: number;
  monthlyPaise: number;
  estimatedTotalPaise: number;
  depositPaise: number;
}

export interface Comparison {
  providers: string[];
  rows: Row[];
  totals: Record<string, ProviderTotal>;
  latestScrapedAt: string | null;
}

// ---- the basket engine (PRD section 9) ----

export interface BasketItem {
  category: string;
  qty: number;
}

export interface ItemPick {
  category: string;
  qty: number;
  provider: string;
  product: CatalogueEntry;
  record: PriceRecord;
}

export interface BasketTotals {
  monthlyPaise: number;
  estimatedTotalPaise: number;
  depositPaise: number;
}

export interface SingleProviderOption extends BasketTotals {
  provider: string;
  coveredCategories: number;
  totalCategories: number;
}

export interface BasketResult {
  /** Cheapest cross-provider pick per item, fully explainable (FR-3.3). */
  picks: ItemPick[];
  /** Categories no provider can price right now — excluded from totals, reported (FR-3.4). */
  uncovered: string[];
  mixed: BasketTotals | null;
  mixedProviders: string[];
  /** Cheapest provider stocking every requested category (PRD section 9B). */
  bestSingle: SingleProviderOption | null;
  /** Providers with partial coverage, reported separately, never mixed in. */
  partialProviders: SingleProviderOption[];
  /** Monthly saving from splitting across providers; null when no single covers all. */
  deltaMonthlyPaise: number | null;
}

/**
 * Records eligible to be ranked at this tenure: in stock, that tenure, and
 * fresh enough for totals (older than 72h is excluded from rankings, PRD
 * section 13).
 */
function eligibleRecords(records: PriceRecord[], tenureMonths: number, now: Date): PriceRecord[] {
  return records.filter(
    (r) =>
      r.tenureMonths === tenureMonths &&
      r.availability === 'IN_STOCK' &&
      eligibleForTotals(freshnessBand(r.scrapedAt, now)),
  );
}

export function buildBasket(
  records: PriceRecord[],
  catalogue: CatalogueEntry[],
  basket: BasketItem[],
  tenureMonths: number,
  now: Date,
): BasketResult {
  const eligible = eligibleRecords(records, tenureMonths, now);
  const productById = new Map(catalogue.map((p) => [p.id, p]));
  const categoryOf = (r: PriceRecord) => productById.get(r.canonicalProductId)?.category ?? 'OTHER';

  const providers = [...new Set(eligible.map((r) => r.provider))].sort();

  // Cheapest record per (provider, category): min.
  const cheapest = new Map<string, PriceRecord>();
  for (const record of eligible) {
    const key = record.provider + '|' + categoryOf(record);
    const current = cheapest.get(key);
    if (!current || record.estimatedTotalPaise < current.estimatedTotalPaise) {
      cheapest.set(key, record);
    }
  }

  const picks: ItemPick[] = [];
  const uncovered: string[] = [];
  for (const item of basket) {
    let best: PriceRecord | null = null;
    for (const provider of providers) {
      const candidate = cheapest.get(provider + '|' + item.category) ?? null;
      if (candidate && (!best || candidate.estimatedTotalPaise < best.estimatedTotalPaise)) {
        best = candidate;
      }
    }
    if (!best) {
      uncovered.push(item.category);
    } else {
      picks.push({
        category: item.category,
        qty: item.qty,
        provider: best.provider,
        product: productById.get(best.canonicalProductId)!,
        record: best,
      });
    }
  }

  // Mixed total: sum of the picks, quantities included.
  let mixed: BasketTotals | null = null;
  if (picks.length > 0) {
    mixed = { monthlyPaise: 0, estimatedTotalPaise: 0, depositPaise: 0 };
    for (const pick of picks) {
      mixed.monthlyPaise += pick.record.monthlyPaise * pick.qty;
      mixed.estimatedTotalPaise += pick.record.estimatedTotalPaise * pick.qty;
      mixed.depositPaise += pick.record.depositPaise * pick.qty;
    }
  }

  // Per-provider basket: full coverage competes for cheapest-single (9B);
  // partial coverage is reported separately, never silently mixed in.
  const fullCoverage: SingleProviderOption[] = [];
  const partialProviders: SingleProviderOption[] = [];
  for (const provider of providers) {
    let covered = 0;
    const totals: BasketTotals = { monthlyPaise: 0, estimatedTotalPaise: 0, depositPaise: 0 };
    for (const item of basket) {
      const record = cheapest.get(provider + '|' + item.category);
      if (record) {
        covered += 1;
        totals.monthlyPaise += record.monthlyPaise * item.qty;
        totals.estimatedTotalPaise += record.estimatedTotalPaise * item.qty;
        totals.depositPaise += record.depositPaise * item.qty;
      }
    }
    if (covered === 0) continue;
    const option: SingleProviderOption = {
      provider,
      coveredCategories: covered,
      totalCategories: basket.length,
      ...totals,
    };
    if (covered === basket.length) {
      fullCoverage.push(option);
    } else {
      partialProviders.push(option);
    }
  }

  let bestSingle: SingleProviderOption | null = null;
  for (const option of fullCoverage) {
    if (!bestSingle || option.estimatedTotalPaise < bestSingle.estimatedTotalPaise) {
      bestSingle = option;
    }
  }

  return {
    picks,
    uncovered,
    mixed,
    mixedProviders: [...new Set(picks.map((p) => p.provider))].sort(),
    bestSingle,
    partialProviders,
    deltaMonthlyPaise: bestSingle && mixed ? bestSingle.monthlyPaise - mixed.monthlyPaise : null,
  };
}

export function buildComparison(
  records: PriceRecord[],
  catalogue: CatalogueEntry[],
  tenureMonths: number,
  now: Date,
  /**
   * The providers to show columns for. Pass the full active set, otherwise a
   * provider that stocks nothing in this category disappears from it — which
   * reads as "we never checked" when the honest answer is "not offered"
   * (FR-2.3: a cell is never blank).
   */
  knownProviders?: string[],
): Comparison {
  const providers = (knownProviders ?? [...new Set(records.map((r) => r.provider))]).slice().sort();
  const productsWithData = new Set(records.map((r) => r.canonicalProductId));
  const rows: Row[] = [];

  for (const product of catalogue.filter((p) => productsWithData.has(p.id))) {
    const productRecords = records.filter((r) => r.canonicalProductId === product.id);
    const cells: Record<string, Cell> = {};

    for (const provider of providers) {
      const providerRecords = productRecords.filter((r) => r.provider === provider);
      // Provider may list several products in the same canonical row (a 190L and
      // a 170L both land in 150–200L). The row shows the provider's cheapest.
      const atTenure = providerRecords.filter((r) => r.tenureMonths === tenureMonths);

      if (providerRecords.length === 0) {
        cells[provider] = { state: 'not-offered', record: null, band: null, isBest: false, anyTenureRecord: null };
      } else if (atTenure.length === 0) {
        cells[provider] = {
          state: 'not-published-for-tenure',
          record: null,
          band: null,
          isBest: false,
          anyTenureRecord: providerRecords[0],
        };
      } else {
        const inStock = atTenure.filter((r) => r.availability === 'IN_STOCK');
        if (inStock.length === 0) {
          cells[provider] = { state: 'out-of-stock', record: atTenure[0], band: null, isBest: false, anyTenureRecord: atTenure[0] };
        } else {
          let cheapest = inStock[0];
          for (const record of inStock) {
            if (record.estimatedTotalPaise < cheapest.estimatedTotalPaise) cheapest = record;
          }
          cells[provider] = {
            state: 'priced',
            record: cheapest,
            band: freshnessBand(cheapest.scrapedAt, now),
            isBest: false,
            anyTenureRecord: cheapest,
          };
        }
      }
    }

    // Best-price badge: ranked on estimated total for the tenure (FR-3.2),
    // only among cells fresh enough to be trusted, and only when there is
    // actually a comparison to win (two or more priced cells). Ties all badge.
    const contenders = providers
      .map((p) => cells[p])
      .filter((c) => c.state === 'priced' && c.record && c.band && eligibleForBestPrice(c.band));
    if (contenders.length >= 2) {
      let min = Infinity;
      for (const cell of contenders) {
        if (cell.record!.estimatedTotalPaise < min) min = cell.record!.estimatedTotalPaise;
      }
      for (const cell of contenders) {
        if (cell.record!.estimatedTotalPaise === min) cell.isBest = true;
      }
    }

    rows.push({ product, cells });
  }

  rows.sort((a, b) => a.product.name.localeCompare(b.product.name));

  // Per-provider totals over the items that provider actually stocks (FR-2.5),
  // excluding records too stale to rank (PRD section 13 trust rules).
  const totals: Record<string, ProviderTotal> = {};
  for (const provider of providers) {
    let itemsCovered = 0;
    let monthlyPaise = 0;
    let estimatedTotalPaise = 0;
    let depositPaise = 0;
    for (const row of rows) {
      const cell = row.cells[provider];
      if (cell.state === 'priced' && cell.record && cell.band && eligibleForTotals(cell.band)) {
        itemsCovered += 1;
        monthlyPaise += cell.record.monthlyPaise;
        estimatedTotalPaise += cell.record.estimatedTotalPaise;
        depositPaise += cell.record.depositPaise;
      }
    }
    totals[provider] = { itemsCovered, itemsRequested: rows.length, monthlyPaise, estimatedTotalPaise, depositPaise };
  }

  let latestScrapedAt: string | null = null;
  for (const record of records) {
    if (!latestScrapedAt || record.scrapedAt > latestScrapedAt) latestScrapedAt = record.scrapedAt;
  }

  return { providers, rows, totals, latestScrapedAt };
}
