'use client';

// Filters and sorting (PRD §11) — client-side over already-priced data (§18).
// Provider and freshness filters act on records before comparison; price and
// attribute filters act on rows. All state lives in the URL (FR-8.3).

import type { SortMode } from '../lib/rank';
import { SORT_LABELS } from '../lib/rank';
import { providerLabel } from '../lib/types';

export interface GlobalFilters {
  /** null = all providers */
  providers: string[] | null;
  monthlyMinRupees: number | null;
  monthlyMaxRupees: number | null;
  depositMaxRupees: number | null;
  /** hide prices older than 24h (PRD §11 freshness filter) */
  freshOnly: boolean;
  sort: SortMode;
}

export const DEFAULT_FILTERS: GlobalFilters = {
  providers: null,
  monthlyMinRupees: null,
  monthlyMaxRupees: null,
  depositMaxRupees: null,
  freshOnly: false,
  sort: 'cheapest-total',
};

function numberOrNull(value: string): number | null {
  const n = Number(value);
  return value.trim() !== '' && Number.isFinite(n) && n >= 0 ? n : null;
}

export function FilterBar({
  allProviders,
  filters,
  onChange,
}: {
  allProviders: string[];
  filters: GlobalFilters;
  onChange: (next: GlobalFilters) => void;
}) {
  const active = filters.providers ?? allProviders;

  function toggleProvider(id: string) {
    const next = active.includes(id) ? active.filter((p) => p !== id) : [...active, id];
    // Every provider selected reads as "no filter".
    onChange({ ...filters, providers: next.length === allProviders.length || next.length === 0 ? null : next });
  }

  return (
    <div className="filter-bar" role="group" aria-label="Filters and sorting">
      <span className="filter-group" role="group" aria-label="Providers">
        {allProviders.map((id) => (
          <button
            key={id}
            type="button"
            className={`filter-chip${active.includes(id) ? ' on' : ''}`}
            aria-pressed={active.includes(id)}
            onClick={() => toggleProvider(id)}
          >
            {providerLabel(id)}
          </button>
        ))}
      </span>

      <label className="filter-field">
        ₹/mo
        <input
          type="number"
          inputMode="numeric"
          min={0}
          placeholder="min"
          value={filters.monthlyMinRupees ?? ''}
          onChange={(e) => onChange({ ...filters, monthlyMinRupees: numberOrNull(e.target.value) })}
        />
        –
        <input
          type="number"
          inputMode="numeric"
          min={0}
          placeholder="max"
          value={filters.monthlyMaxRupees ?? ''}
          onChange={(e) => onChange({ ...filters, monthlyMaxRupees: numberOrNull(e.target.value) })}
        />
      </label>

      <label className="filter-field">
        Max deposit ₹
        <input
          type="number"
          inputMode="numeric"
          min={0}
          placeholder="any"
          value={filters.depositMaxRupees ?? ''}
          onChange={(e) => onChange({ ...filters, depositMaxRupees: numberOrNull(e.target.value) })}
        />
      </label>

      <label className="filter-check">
        <input
          type="checkbox"
          checked={filters.freshOnly}
          onChange={(e) => onChange({ ...filters, freshOnly: e.target.checked })}
        />
        Hide prices older than 24h
      </label>

      <label className="filter-field">
        Sort
        <select value={filters.sort} onChange={(e) => onChange({ ...filters, sort: e.target.value as SortMode })}>
          {(Object.keys(SORT_LABELS) as SortMode[]).map((mode) => (
            <option key={mode} value={mode}>
              {SORT_LABELS[mode]}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
