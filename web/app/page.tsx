'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BasketSummary } from '../components/BasketSummary';
import { CategorySection } from '../components/CategorySection';
import { DEFAULT_FILTERS, FilterBar, type GlobalFilters } from '../components/FilterBar';
import { PincodeCheck } from '../components/PincodeCheck';
import { SearchBox } from '../components/SearchBox';
import { SetupBuilder } from '../components/SetupBuilder';
import { buildBasket, buildComparison, type BasketItem } from '../lib/compare';
import { formatAge, freshnessBand, eligibleForBestPrice } from '../lib/freshness';
import type { ParsedQuery } from '../lib/nlq';
import { type SortMode, SORT_LABELS } from '../lib/rank';
import { useInView } from '../lib/useInView';
import type {
  CatalogueFile,
  CitiesFile,
  PriceRecord,
  PricesFile,
  ProviderStats,
  RunsFile,
  SetupsFile,
} from '../lib/types';
import { CATEGORY_LABELS, categorySlug, TENURES } from '../lib/types';

interface Loaded {
  catalogue: CatalogueFile;
  runs: RunsFile;
  setups: SetupsFile | null;
  cities: CitiesFile | null;
  stats: ProviderStats | null;
}

/** ?items=bed:2,refrigerator:1 — the whole query lives in the URL (FR-1.6). */
function parseItems(value: string | null): BasketItem[] {
  if (!value) return [];
  const items: BasketItem[] = [];
  for (const part of value.split(',')) {
    const [slug, qtyRaw] = part.split(':');
    const category = slug?.toUpperCase().replace(/-/g, '_');
    const qty = Math.min(Math.max(Number(qtyRaw ?? 1) || 1, 1), 9);
    // One entry per category: a repeated slug would render duplicate sections
    // under the same React key and confuse the quantity controls.
    if (category && CATEGORY_LABELS[category] && !items.some((i) => i.category === category)) {
      items.push({ category, qty });
    }
  }
  return items;
}

function serializeItems(items: BasketItem[]): string {
  return items.map((i) => `${categorySlug(i.category)}:${i.qty}`).join(',');
}

/** ?f=refrigerator:door_type:double_door — per-category attribute filters. */
function parseAttrFilters(value: string | null): Record<string, Record<string, string>> {
  const out: Record<string, Record<string, string>> = {};
  if (!value) return out;
  for (const part of value.split(',')) {
    const [slug, key, attr] = part.split(':');
    const category = slug?.toUpperCase().replace(/-/g, '_');
    if (category && CATEGORY_LABELS[category] && key && attr) {
      (out[category] ??= {})[key] = attr;
    }
  }
  return out;
}

function serializeAttrFilters(filters: Record<string, Record<string, string>>): string {
  const parts: string[] = [];
  for (const [category, attrs] of Object.entries(filters)) {
    for (const [key, value] of Object.entries(attrs)) {
      parts.push(`${categorySlug(category)}:${key}:${value}`);
    }
  }
  return parts.join(',');
}

/** Stable identity for "no attribute filters", so sections do not re-render. */
const EMPTY_ATTRS: Record<string, string> = {};

/** Popular comparisons (FR-1.7): each a pre-filled query, each also a §22 landing page. */
const POPULAR: { label: string; items: string }[] = [
  { label: 'Bed', items: 'bed:1' },
  { label: 'Fridge', items: 'refrigerator:1' },
  { label: 'Washing machine', items: 'washing-machine:1' },
  { label: 'Mattress', items: 'mattress:1' },
  { label: '2BHK setup', items: 'bed:2,mattress:2,refrigerator:1,washing-machine:1,sofa:1,dining-table:1,wardrobe:2' },
  { label: 'Bachelor setup', items: 'bed:1,mattress:1,refrigerator:1,washing-machine:1' },
];

export default function Home() {
  const [tenure, setTenure] = useState<number>(12);
  const [city, setCity] = useState<string>('bangalore');
  const [basket, setBasket] = useState<BasketItem[]>([]);
  const [filters, setFilters] = useState<GlobalFilters>(DEFAULT_FILTERS);
  const [attrFilters, setAttrFilters] = useState<Record<string, Record<string, string>>>({});
  const [data, setData] = useState<Loaded | null>(null);
  // Per-category price files (FR-8.1); the legacy single file is the fallback.
  const [priceMap, setPriceMap] = useState<Record<string, PriceRecord[]>>({});
  const [legacy, setLegacy] = useState<PriceRecord[] | null>(null);
  const fetchedSlugs = useRef(new Set<string>());
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState<Date | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const fromUrl = Number(params.get('tenure'));
    if (TENURES.includes(fromUrl as (typeof TENURES)[number])) setTenure(fromUrl);
    setBasket(parseItems(params.get('items')));
    setAttrFilters(parseAttrFilters(params.get('f')));
    const sort = params.get('sort');
    // A non-numeric ?min= is ignored rather than kept as NaN, which would be
    // written straight back into the URL and stick there.
    const money = (key: string): number | null => {
      const raw = params.get(key);
      if (raw == null || raw.trim() === '') return null;
      const value = Number(raw);
      return Number.isFinite(value) && value >= 0 ? value : null;
    };
    // An empty list means "no filter", the same as absent — never "show
    // nothing", which is what a hand-typed ?providers= would otherwise do.
    const providers = params.get('providers')?.split(',').filter(Boolean);
    setFilters({
      providers: providers && providers.length > 0 ? providers : null,
      monthlyMinRupees: money('min'),
      monthlyMaxRupees: money('max'),
      depositMaxRupees: money('dep'),
      freshOnly: params.get('fresh') === '1',
      sort: sort && sort in SORT_LABELS ? (sort as SortMode) : 'cheapest-total',
    });
    setNow(new Date());
    const tick = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(tick);
  }, []);

  // The query state stays in the URL so a comparison is shareable (FR-8.3).
  useEffect(() => {
    const url = new URL(window.location.href);
    const setOrDelete = (key: string, value: string | null) => {
      if (value) url.searchParams.set(key, value);
      else url.searchParams.delete(key);
    };
    setOrDelete('tenure', tenure === 12 ? null : String(tenure));
    setOrDelete('items', basket.length === 0 ? null : serializeItems(basket));
    setOrDelete('providers', filters.providers?.join(',') ?? null);
    setOrDelete('min', filters.monthlyMinRupees != null ? String(filters.monthlyMinRupees) : null);
    setOrDelete('max', filters.monthlyMaxRupees != null ? String(filters.monthlyMaxRupees) : null);
    setOrDelete('dep', filters.depositMaxRupees != null ? String(filters.depositMaxRupees) : null);
    setOrDelete('fresh', filters.freshOnly ? '1' : null);
    setOrDelete('sort', filters.sort === 'cheapest-total' ? null : filters.sort);
    setOrDelete('f', serializeAttrFilters(attrFilters) || null);
    window.history.replaceState(null, '', url.toString());
  }, [tenure, basket, filters, attrFilters]);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const get = async <T,>(name: string, required = true): Promise<T | null> => {
          const response = await fetch(`/data/${name}`, { cache: 'no-store' });
          if (!response.ok) {
            if (required) throw new Error(`${name}: HTTP ${response.status}`);
            return null;
          }
          return response.json() as Promise<T>;
        };
        const [catalogue, runs, setups, cities, stats] = await Promise.all([
          get<CatalogueFile>('catalogue.json'),
          get<RunsFile>('runs.json'),
          get<SetupsFile>('setups.json', false),
          get<CitiesFile>('cities.json', false),
          get<ProviderStats>('provider-stats.json', false),
        ]);
        if (!cancelled) setData({ catalogue: catalogue!, runs: runs!, setups, cities, stats });
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    }
    load();
    return () => {
      cancelled = true;
    };
  }, []);

  /** Categories that actually have data, in display order. */
  const categories = useMemo(() => {
    if (!data) return [];
    const present = new Set(data.catalogue.products.map((p) => p.category));
    return Object.keys(CATEGORY_LABELS).filter((c) => present.has(c));
  }, [data]);

  // Memoized: this array is an effect dependency, so a fresh identity every
  // render would re-run the price fetch and every comparison.
  const shownCategories = useMemo(
    () => (basket.length > 0 ? basket.map((i) => i.category) : categories),
    [basket, categories],
  );

  /**
   * Fetch one category's price file, once. Called by a section when it nears
   * the viewport, and eagerly for the basket's categories (their totals are
   * shown at the top of the page, above those sections).
   */
  const loadCategory = useCallback(async (category: string) => {
    const slug = categorySlug(category);
    if (fetchedSlugs.current.has(slug)) return;
    fetchedSlugs.current.add(slug);
    try {
      const response = await fetch(`/data/prices/${slug}.json`, { cache: 'no-store' });
      if (response.ok) {
        const file = (await response.json()) as PricesFile;
        setPriceMap((current) => ({ ...current, [slug]: file.records }));
      } else if (!fetchedSlugs.current.has('__legacy__')) {
        // Pre-split data: one prices.json for everything.
        fetchedSlugs.current.add('__legacy__');
        const fallback = await fetch('/data/prices.json', { cache: 'no-store' });
        if (fallback.ok) {
          setLegacy(((await fallback.json()) as PricesFile).records);
        } else {
          fetchedSlugs.current.delete('__legacy__');
        }
      }
    } catch {
      // A failed fetch must not poison the guard — let a later view retry.
      fetchedSlugs.current.delete(slug);
    }
  }, []);

  useEffect(() => {
    if (!data) return;
    for (const item of basket) void loadCategory(item.category);
  }, [data, basket, loadCategory]);

  const loadedCategories = useMemo(() => {
    const loaded = new Set<string>();
    if (legacy) return null; // legacy file covers everything at once
    for (const slug of Object.keys(priceMap)) loaded.add(slug);
    return loaded;
  }, [priceMap, legacy]);

  /** Records for a category, after the record-level filters (city, provider, freshness). */
  const recordsFor = useCallback(
    (category: string): PriceRecord[] => {
      if (!data || !now) return [];
      let records: PriceRecord[];
      if (legacy) {
        const ids = new Set(data.catalogue.products.filter((p) => p.category === category).map((p) => p.id));
        records = legacy.filter((r) => ids.has(r.canonicalProductId));
      } else {
        records = priceMap[categorySlug(category)] ?? [];
      }
      // Records carry their city, so a second city needs no code change here.
      records = records.filter((r) => r.city === city);
      if (filters.providers) records = records.filter((r) => filters.providers!.includes(r.provider));
      if (filters.freshOnly) {
        records = records.filter((r) => eligibleForBestPrice(freshnessBand(r.scrapedAt, now)));
      }
      return records;
    },
    [data, now, legacy, priceMap, city, filters.providers, filters.freshOnly],
  );

  const rowFilterValues = useMemo(
    () => ({
      monthlyMinPaise: filters.monthlyMinRupees != null ? filters.monthlyMinRupees * 100 : null,
      monthlyMaxPaise: filters.monthlyMaxRupees != null ? filters.monthlyMaxRupees * 100 : null,
      depositMaxPaise: filters.depositMaxRupees != null ? filters.depositMaxRupees * 100 : null,
    }),
    [filters.monthlyMinRupees, filters.monthlyMaxRupees, filters.depositMaxRupees],
  );

  const basketReady = basket.every((item) => !loadedCategories || loadedCategories.has(categorySlug(item.category)));

  const basketResult = useMemo(() => {
    if (!data || !now || basket.length === 0 || !basketReady) return null;
    const records = basket.flatMap((item) => recordsFor(item.category));
    return buildBasket(records, data.catalogue.products, basket, tenure, now);
  }, [data, basket, basketReady, tenure, now, recordsFor]);

  const allProviders = useMemo(() => {
    const ids = new Set<string>();
    for (const records of Object.values(priceMap)) for (const r of records) ids.add(r.provider);
    for (const r of legacy ?? []) ids.add(r.provider);
    return [...ids].sort();
  }, [priceMap, legacy]);

  const latestScrapedAt = useMemo(() => {
    let latest: string | null = null;
    const scan = (records: PriceRecord[]) => {
      for (const r of records) if (!latest || r.scrapedAt > latest) latest = r.scrapedAt;
    };
    for (const records of Object.values(priceMap)) scan(records);
    scan(legacy ?? []);
    return latest;
  }, [priceMap, legacy]);

  function toggleCategory(category: string) {
    setBasket((current) => {
      const existing = current.find((i) => i.category === category);
      if (existing) return current.filter((i) => i.category !== category);
      return [...current, { category, qty: 1 }];
    });
  }

  function bumpQty(category: string, delta: number) {
    setBasket((current) =>
      current
        .map((i) => (i.category === category ? { ...i, qty: Math.min(Math.max(i.qty + delta, 0), 9) } : i))
        .filter((i) => i.qty > 0),
    );
  }

  function setAttrFilter(category: string, key: string, value: string) {
    setAttrFilters((current) => {
      const next = { ...current, [category]: { ...current[category] } };
      if (value === '') delete next[category][key];
      else next[category][key] = value;
      if (Object.keys(next[category]).length === 0) delete next[category];
      return next;
    });
  }

  const providerTypes = useMemo(
    () =>
      data
        ? Object.fromEntries(Object.entries(data.runs.providers).map(([id, run]) => [id, run.integrationType]))
        : {},
    [data],
  );

  /** A parsed free-text query becomes ordinary basket + filter state (AC-3.1). */
  function applyParsedQuery(parsed: ParsedQuery) {
    setBasket(parsed.items.map((i) => ({ category: i.category, qty: i.qty })));
    if (parsed.tenureMonths) setTenure(parsed.tenureMonths);
    setFilters((current) => ({
      ...current,
      monthlyMaxRupees: parsed.monthlyMaxRupees ?? current.monthlyMaxRupees,
      depositMaxRupees: parsed.depositMaxRupees ?? current.depositMaxRupees,
      sort: parsed.sort ?? current.sort,
    }));
  }

  const loaded = data && now;
  const cities = data?.cities?.cities ?? [];
  const currentCity = cities.find((c) => c.id === city) ?? cities[0] ?? null;

  return (
    <main>
      <div className={`home${loaded ? ' has-results' : ''}`}>
        <h1 className="wordmark">
          Rent<span className="accent">Radar</span>
        </h1>
        <p className="tagline">
          Compare furniture &amp; appliance rental prices in {currentCity?.label ?? 'Bengaluru'}
        </p>

        <SearchBox onParsed={applyParsedQuery} />

        <div className="controls">
          <select
            aria-label="City"
            value={city}
            disabled={cities.length < 2}
            title={cities.length < 2 ? 'More cities later — Bengaluru first' : undefined}
            onChange={(e) => setCity(e.target.value)}
          >
            {(cities.length > 0 ? cities : [{ id: 'bangalore', label: 'Bengaluru' }]).map((c) => (
              <option key={c.id} value={c.id}>
                {c.label}
              </option>
            ))}
          </select>
          <select aria-label="Tenure in months" value={tenure} onChange={(e) => setTenure(Number(e.target.value))}>
            {TENURES.map((months) => (
              <option key={months} value={months}>
                {months} months
              </option>
            ))}
          </select>
        </div>

        {loaded && data.setups && (
          <SetupBuilder setups={data.setups} onApply={(items) => setBasket(items)} />
        )}

        {loaded && (
          <div className="chips" role="group" aria-label="What do you need?">
            {categories.map((category) => {
              const item = basket.find((i) => i.category === category);
              return (
                <span key={category} className={`chip-toggle${item ? ' on' : ''}`}>
                  <button type="button" className="chip-main" aria-pressed={!!item} onClick={() => toggleCategory(category)}>
                    {item ? CATEGORY_LABELS[category].singular : CATEGORY_LABELS[category].plural}
                  </button>
                  {item && (
                    <span className="qty">
                      <button type="button" aria-label={`Fewer ${CATEGORY_LABELS[category].plural}`} onClick={() => bumpQty(category, -1)}>
                        −
                      </button>
                      <b>{item.qty}</b>
                      <button type="button" aria-label={`More ${CATEGORY_LABELS[category].plural}`} onClick={() => bumpQty(category, 1)}>
                        +
                      </button>
                    </span>
                  )}
                </span>
              );
            })}
          </div>
        )}

        {loaded && basket.length === 0 && (
          <p className="popular">
            Popular:{' '}
            {POPULAR.map((p, i) => (
              <span key={p.label}>
                {i > 0 && ' · '}
                <a href={`/?items=${p.items}`} onClick={(e) => { e.preventDefault(); setBasket(parseItems(p.items)); }}>
                  {p.label}
                </a>
              </span>
            ))}
          </p>
        )}
      </div>

      <section className="results" aria-live="polite">
        {error && (
          <p className="empty">
            Price data could not be loaded ({error}). The pipeline may not have produced data yet — see{' '}
            <a href="/status/">status</a>.
          </p>
        )}
        {!error && !loaded && <p className="loading">Loading prices…</p>}

        {loaded && (
          <>
            <div className="results-bar">
              <span className="freshness-line">
                {latestScrapedAt ? `Prices last checked ${formatAge(latestScrapedAt, now)}` : 'Loading prices…'}
              </span>
              {/* The whole query already lives in the URL (FR-8.3); this just
                  hands it over without asking anyone to select the address bar. */}
              <button
                type="button"
                className="share-link"
                onClick={() => {
                  navigator.clipboard
                    ?.writeText(window.location.href)
                    .then(() => {
                      setCopied(true);
                      window.setTimeout(() => setCopied(false), 2000);
                    })
                    .catch(() => setCopied(false));
                }}
              >
                {copied ? '✓ Link copied' : 'Copy link'}
              </button>
            </div>

            {allProviders.length > 0 && (
              <FilterBar allProviders={allProviders} filters={filters} onChange={setFilters} />
            )}

            {basketResult && <BasketSummary result={basketResult} tenure={tenure} />}

            {shownCategories.map((category) => (
              <CategorySection
                key={category}
                category={category}
                records={recordsFor(category)}
                loaded={!loadedCategories || loadedCategories.has(categorySlug(category))}
                catalogue={data.catalogue.products}
                tenure={tenure}
                now={now}
                sort={filters.sort}
                rowFilters={rowFilterValues}
                attrFilters={attrFilters[category] ?? EMPTY_ATTRS}
                stats={data.stats}
                providerTypes={providerTypes}
                onNeedData={loadCategory}
                onAttrFilterChange={setAttrFilter}
              />
            ))}
          </>
        )}
      </section>

      <footer>
        <span>{currentCity?.label ?? 'Bengaluru'}</span>
        {loaded && currentCity && (
          <PincodeCheck serviceability={currentCity} liveProviders={allProviders} />
        )}
        <a href="/status/">Pipeline status</a>
        <a href="https://github.com/piyushk-r/RentRadar" rel="noopener noreferrer" target="_blank">
          Source
        </a>
        <span>Prices belong to their providers — always confirm on the linked page.</span>
      </footer>
    </main>
  );
}
