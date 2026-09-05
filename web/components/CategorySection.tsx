'use client';

// One category's table. It asks for its own price file the first time it
// nears the viewport, so opening the home page does not pull all fourteen
// categories at once (FR-8.1) — the payload follows what the visitor reads.

import { useEffect, useMemo } from 'react';
import { buildComparison } from '../lib/compare';
import { filterRows, sortRows, type SortMode } from '../lib/rank';
import { useInView } from '../lib/useInView';
import type { CatalogueEntry, PriceRecord, ProviderStats } from '../lib/types';
import { CATEGORY_LABELS } from '../lib/types';
import { ComparisonTable } from './ComparisonTable';

export interface RowFilterValues {
  monthlyMinPaise: number | null;
  monthlyMaxPaise: number | null;
  depositMaxPaise: number | null;
}

export function CategorySection({
  category,
  records,
  loaded,
  catalogue,
  tenure,
  now,
  sort,
  rowFilters,
  attrFilters,
  stats,
  providerTypes,
  knownProviders,
  onNeedData,
  onAttrFilterChange,
}: {
  category: string;
  records: PriceRecord[];
  loaded: boolean;
  catalogue: CatalogueEntry[];
  tenure: number;
  now: Date;
  sort: SortMode;
  rowFilters: RowFilterValues;
  attrFilters: Record<string, string>;
  stats: ProviderStats | null;
  providerTypes: Record<string, string | null | undefined>;
  /** every active provider, so one with nothing here still shows a cell */
  knownProviders: string[];
  onNeedData: (category: string) => void;
  onAttrFilterChange: (category: string, key: string, value: string) => void;
}) {
  const { ref, inView } = useInView<HTMLDivElement>();

  useEffect(() => {
    if (inView) onNeedData(category);
  }, [inView, category, onNeedData]);

  const view = useMemo(() => {
    if (!loaded) return null;
    const comparison = buildComparison(records, catalogue, tenure, now, knownProviders);
    const scored = sortRows(filterRows(comparison.rows, { ...rowFilters, attributes: attrFilters }), sort, now, stats);
    const why: Record<string, string> = {};
    for (const row of scored) if (row.why) why[row.row.product.id] = row.why;

    // Attribute filter options: keys with more than one value in this category.
    const options: Record<string, string[]> = {};
    for (const product of catalogue.filter((p) => p.category === category)) {
      for (const [key, value] of Object.entries(product.attributes)) {
        const values = (options[key] ??= []);
        if (!values.includes(value)) values.push(value);
      }
    }
    for (const key of Object.keys(options)) {
      if (options[key].length < 2) delete options[key];
      else options[key].sort();
    }
    return { comparison, rows: scored.map((s) => s.row), why, options };
  }, [loaded, records, catalogue, tenure, now, sort, rowFilters, attrFilters, stats, category, knownProviders]);

  return (
    <div className="category-section" ref={ref}>
      <h2>{CATEGORY_LABELS[category].plural}</h2>
      {!view ? (
        <p className="loading">Loading {CATEGORY_LABELS[category].plural.toLowerCase()}…</p>
      ) : (
        <>
          {Object.keys(view.options).length > 0 && (
            <div className="attr-filters">
              {Object.entries(view.options).map(([key, values]) => (
                <label key={key}>
                  {key.replace(/_/g, ' ')}
                  <select
                    value={attrFilters[key] ?? ''}
                    onChange={(e) => onAttrFilterChange(category, key, e.target.value)}
                  >
                    <option value="">any</option>
                    {values.map((value) => (
                      <option key={value} value={value}>
                        {value.replace(/_/g, ' ')}
                      </option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
          )}
          <ComparisonTable
            comparison={view.comparison}
            orderedRows={view.rows}
            why={view.why}
            tenure={tenure}
            now={now}
            categoryLabel={CATEGORY_LABELS[category].plural}
            providerTypes={providerTypes}
          />
        </>
      )}
    </div>
  );
}
