'use client';

import { useEffect, useMemo, useState } from 'react';
import { ComparisonTable } from '../components/ComparisonTable';
import { buildComparison } from '../lib/compare';
import { formatAge } from '../lib/freshness';
import type { CatalogueFile, PricesFile, RunsFile } from '../lib/types';
import { TENURES } from '../lib/types';

interface Loaded {
  prices: PricesFile;
  catalogue: CatalogueFile;
  runs: RunsFile;
}

export default function Home() {
  const [tenure, setTenure] = useState<number>(12);
  const [data, setData] = useState<Loaded | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState<Date | null>(null);

  // Tenure is a first-class query parameter (FR-1.2) and the URL stays
  // shareable (FR-1.6). Read once on mount, write on change.
  useEffect(() => {
    const fromUrl = Number(new URLSearchParams(window.location.search).get('tenure'));
    if (TENURES.includes(fromUrl as (typeof TENURES)[number])) {
      setTenure(fromUrl);
    }
    setNow(new Date());
    const tick = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(tick);
  }, []);

  useEffect(() => {
    const url = new URL(window.location.href);
    if (tenure === 12) {
      url.searchParams.delete('tenure');
    } else {
      url.searchParams.set('tenure', String(tenure));
    }
    window.history.replaceState(null, '', url.toString());
  }, [tenure]);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const [prices, catalogue, runs] = await Promise.all([
          fetch('/data/prices.json', { cache: 'no-store' }).then((r) => {
            if (!r.ok) throw new Error(`prices.json: HTTP ${r.status}`);
            return r.json() as Promise<PricesFile>;
          }),
          fetch('/data/catalogue.json', { cache: 'no-store' }).then((r) => {
            if (!r.ok) throw new Error(`catalogue.json: HTTP ${r.status}`);
            return r.json() as Promise<CatalogueFile>;
          }),
          fetch('/data/runs.json', { cache: 'no-store' }).then((r) => {
            if (!r.ok) throw new Error(`runs.json: HTTP ${r.status}`);
            return r.json() as Promise<RunsFile>;
          }),
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

  const comparison = useMemo(() => {
    if (!data || !now) return null;
    return buildComparison(data.prices.records, data.catalogue.products, tenure, now);
  }, [data, tenure, now]);

  return (
    <main>
      <div className={`home${comparison ? ' has-results' : ''}`}>
        <h1 className="wordmark">
          Rent<span className="accent">Radar</span>
        </h1>
        <p className="tagline">Compare furniture &amp; appliance rental prices in Bengaluru</p>
        <div className="controls">
          <label>
            <span className="visually-hidden">Category</span>
            {/* City and category are single-valued in this phase; the controls stay
                visible so the intent is legible (FR-1.1). */}
            <select aria-label="Category" defaultValue="refrigerator" disabled title="More categories coming — refrigerators first">
              <option value="refrigerator">Refrigerators</option>
            </select>
          </label>
          <label>
            <select aria-label="Tenure in months" value={tenure} onChange={(e) => setTenure(Number(e.target.value))}>
              {TENURES.map((months) => (
                <option key={months} value={months}>
                  {months} months
                </option>
              ))}
            </select>
          </label>
        </div>
      </div>

      <section className="results" aria-live="polite">
        {error && (
          <p className="empty">
            Price data could not be loaded ({error}). The pipeline may not have produced data yet — see{' '}
            <a href="/status/">status</a>.
          </p>
        )}
        {!error && !comparison && <p className="loading">Loading prices…</p>}
        {comparison && now && (
          <>
            <p className="freshness-line">
              {comparison.latestScrapedAt
                ? `Prices last checked ${formatAge(comparison.latestScrapedAt, now)}`
                : 'No prices collected yet'}
            </p>
            <ComparisonTable comparison={comparison} tenure={tenure} now={now} />
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
