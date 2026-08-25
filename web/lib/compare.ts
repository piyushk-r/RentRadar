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

export function buildComparison(
  records: PriceRecord[],
  catalogue: CatalogueEntry[],
  tenureMonths: number,
  now: Date,
): Comparison {
  const providers = [...new Set(records.map((r) => r.provider))].sort();
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
