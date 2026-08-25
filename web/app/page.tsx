'use client';

import { useEffect, useMemo, useState } from 'react';
import { BasketSummary } from '../components/BasketSummary';
import { ComparisonTable } from '../components/ComparisonTable';
import { buildBasket, buildComparison, type BasketItem } from '../lib/compare';
import { formatAge } from '../lib/freshness';
import type { CatalogueFile, PricesFile, RunsFile } from '../lib/types';
import { CATEGORY_LABELS, TENURES } from '../lib/types';

interface Loaded {
  prices: PricesFile;
  catalogue: CatalogueFile;
  runs: RunsFile;
}

/** ?items=bed:2,refrigerator:1 — the whole query lives in the URL (FR-1.6). */
function parseItems(value: string | null): BasketItem[] {
  if (!value) return [];
  const items: BasketItem[] = [];
  for (const part of value.split(',')) {
    const [slug, qtyRaw] = part.split(':');
    const category = slug?.toUpperCase().replace(/-/g, '_');
    const qty = Math.min(Math.max(Number(qtyRaw ?? 1) || 1, 1), 9);
    if (category && CATEGORY_LABELS[category]) items.push({ category, qty });
  }
  return items;
}

function serializeItems(items: BasketItem[]): string {
  return items.map((i) => `${i.category.toLowerCase().replace(/_/g, '-')}:${i.qty}`).join(',');
}

export default function Home() {
  const [tenure, setTenure] = useState<number>(12);
  const [basket, setBasket] = useState<BasketItem[]>([]);
  const [data, setData] = useState<Loaded | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState<Date | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const fromUrl = Number(params.get('tenure'));
    if (TENURES.includes(fromUrl as (typeof TENURES)[number])) setTenure(fromUrl);
    setBasket(parseItems(params.get('items')));
    setNow(new Date());
    const tick = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(tick);
  }, []);

  useEffect(() => {
    const url = new URL(window.location.href);
    if (tenure === 12) url.searchParams.delete('tenure');
    else url.searchParams.set('tenure', String(tenure));
    if (basket.length === 0) url.searchParams.delete('items');
    else url.searchParams.set('items', serializeItems(basket));
    window.history.replaceState(null, '', url.toString());
  }, [tenure, basket]);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const get = async <T,>(name: string): Promise<T> => {
          const response = await fetch(`/data/${name}`, { cache: 'no-store' });
          if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
          return response.json() as Promise<T>;
        };
        const [prices, catalogue, runs] = await Promise.all([
          get<PricesFile>('prices.json'),
          get<CatalogueFile>('catalogue.json'),
          get<RunsFile>('runs.json'),
        ]);
        if (!cancelled) setData({ prices, catalogue, runs });
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

  const shownCategories = basket.length > 0 ? basket.map((i) => i.category) : categories;

  const comparisons = useMemo(() => {
    if (!data || !now) return null;
    return shownCategories.map((category) => {
      const productIds = new Set(data.catalogue.products.filter((p) => p.category === category).map((p) => p.id));
      const records = data.prices.records.filter((r) => productIds.has(r.canonicalProductId));
      return {
        category,
        comparison: buildComparison(records, data.catalogue.products, tenure, now),
      };
    });
  }, [data, shownCategories, tenure, now]);

  const basketResult = useMemo(() => {
    if (!data || !now || basket.length === 0) return null;
    return buildBasket(data.prices.records, data.catalogue.products, basket, tenure, now);
  }, [data, basket, tenure, now]);

  const latestScrapedAt = useMemo(() => {
    if (!data) return null;
    let latest: string | null = null;
    for (const record of data.prices.records) {
      if (!latest || record.scrapedAt > latest) latest = record.scrapedAt;
    }
    return latest;
  }, [data]);

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

  const loaded = data && now;

  return (
    <main>
      <div className={`home${loaded ? ' has-results' : ''}`}>
        <h1 className="wordmark">
          Rent<span className="accent">Radar</span>
        </h1>
        <p className="tagline">Compare furniture &amp; appliance rental prices in Bengaluru</p>

        <div className="controls">
          <select aria-label="City" disabled title="More cities later — Bengaluru first" defaultValue="bangalore">
            <option value="bangalore">Bengaluru</option>
          </select>
          <select aria-label="Tenure in months" value={tenure} onChange={(e) => setTenure(Number(e.target.value))}>
            {TENURES.map((months) => (
              <option key={months} value={months}>
                {months} months
              </option>
            ))}
          </select>
        </div>

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
            <p className="freshness-line">
              {latestScrapedAt ? `Prices last checked ${formatAge(latestScrapedAt, now)}` : 'No prices collected yet'}
            </p>

            {basketResult && <BasketSummary result={basketResult} tenure={tenure} />}

            {comparisons?.map(({ category, comparison }) => (
              <div className="category-section" key={category}>
                <h2>{CATEGORY_LABELS[category].plural}</h2>
                <ComparisonTable
                  comparison={comparison}
                  tenure={tenure}
                  now={now}
                  categoryLabel={CATEGORY_LABELS[category].plural}
                  providerTypes={Object.fromEntries(
                    Object.entries(data.runs.providers).map(([id, run]) => [id, run.integrationType]),
                  )}
                />
              </div>
            ))}
          </>
        )}
      </section>

      <footer>
        <span>Bengaluru</span>
        <a href="/status/">Pipeline status</a>
        <a href="https://github.com/piyushk-r/RentRadar" rel="noopener noreferrer" target="_blank">
          Source
        </a>
        <span>Prices belong to their providers — always confirm on the linked page.</span>
      </footer>
    </main>
  );
}
