'use client';

// Price history for one canonical product (AC-2.1): renders only points a run
// actually collected — no backfill, no interpolation. An empty chart saying
// "collecting since <date>" is the correct output. "Lowest in the last 90
// days" appears only once 90 days of runs exist (AC-2.2).

import { useEffect, useMemo, useState } from 'react';
import { formatPaise } from '../lib/money';
import type { HistoryFile, HistorySeries } from '../lib/types';
import { providerLabel } from '../lib/types';

// Categorical slots from a CVD-validated palette (first three validate
// all-pairs; adjacent-safe beyond). Assigned to providers in sorted order —
// fixed per entity, never cycled per chart.
const SERIES_COLORS = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4'];
const INK_MUTED = '#898781';
const GRID = '#e1e0d9';
const BASELINE = '#c3c2b7';

const W = 640;
const H = 220;
const PAD = { top: 16, right: 96, bottom: 28, left: 64 };
const DAY_MS = 24 * 3600 * 1000;

interface Hover {
  x: number;
  y: number;
  date: string;
  paise: number;
  label: string;
}

function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

export function PriceHistory({ productId, tenure, now }: { productId: string; tenure: number; now: Date }) {
  const [history, setHistory] = useState<HistoryFile | null | 'missing'>(null);
  const [hover, setHover] = useState<Hover | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch(`/data/history/${productId}.json`, { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : 'missing'))
      .then((h) => {
        if (!cancelled) setHistory(h as HistoryFile | 'missing');
      })
      .catch(() => {
        if (!cancelled) setHistory('missing');
      });
    return () => {
      cancelled = true;
    };
  }, [productId]);

  const view = useMemo(() => {
    if (!history || history === 'missing') return null;
    const series = history.series.filter((s) => s.tenureMonths === tenure && s.points.length > 0);
    if (series.length === 0) return null;

    const providers = [...new Set(series.map((s) => s.provider))].sort();
    const colorOf = (provider: string) => SERIES_COLORS[providers.indexOf(provider) % SERIES_COLORS.length];
    // Second listing of the same provider keeps its provider's hue, dashed.
    const seenPerProvider = new Map<string, number>();

    const t0 = Math.min(...series.map((s) => new Date(s.points[0][0]).getTime()));
    const t1 = now.getTime();
    const maxPaise = Math.max(...series.flatMap((s) => s.points.map((p) => p[1])));
    const x = (t: number) => PAD.left + ((t - t0) / Math.max(t1 - t0, 1)) * (W - PAD.left - PAD.right);
    const y = (paise: number) => H - PAD.bottom - (paise / (maxPaise * 1.1)) * (H - PAD.top - PAD.bottom);

    const lines = series.map((s) => {
      const nth = seenPerProvider.get(s.provider) ?? 0;
      seenPerProvider.set(s.provider, nth + 1);
      // Step-after: a price holds until the next observed change, then extends to now.
      let d = '';
      s.points.forEach(([date, paise], i) => {
        const px = x(new Date(date).getTime());
        const py = y(paise);
        d += i === 0 ? `M${px},${py}` : `H${px}V${py}`;
      });
      d += `H${x(t1)}`;
      return { series: s, d, color: colorOf(s.provider), dashed: nth > 0 };
    });

    // AC-2.2: the 90-day claim needs 90 days of collection first, and it may
    // only quote prices a visitor could still act on — a listing delisted two
    // months ago is not "the lowest in the last 90 days".
    let lowest90: number | null = null;
    if (t1 - new Date(history.collectingSince).getTime() >= 90 * DAY_MS) {
      const windowStart = t1 - 90 * DAY_MS;
      const stillListed = (s: HistorySeries) =>
        s.lastSeen != null && t1 - new Date(s.lastSeen).getTime() <= 3 * DAY_MS;
      let min = Infinity;
      for (const s of series.filter(stillListed)) {
        let carried: number | null = null;
        for (const [date, paise] of s.points) {
          const t = new Date(date).getTime();
          if (t <= windowStart) carried = paise;
          else min = Math.min(min, paise);
        }
        if (carried !== null) min = Math.min(min, carried);
      }
      if (Number.isFinite(min)) lowest90 = min;
    }

    const yTicks = [0.25, 0.5, 0.75, 1].map((f) => Math.round(maxPaise * f));
    return { series, providers, lines, x, y, t0, t1, yTicks, lowest90, colorOf };
  }, [history, tenure, now]);

  if (history === null) {
    return <p className="history-note">Loading history…</p>;
  }
  if (history === 'missing' || !view) {
    const since = history !== 'missing' && history !== null ? history.collectingSince : null;
    return (
      <p className="history-note">
        {since
          ? `Collecting price history since ${shortDate(since)} — no changes recorded at this tenure yet.`
          : 'Collecting price history — the next scheduled runs add real observations; nothing is backfilled.'}
      </p>
    );
  }

  const collectedDays = Math.floor((view.t1 - new Date(history.collectingSince).getTime()) / DAY_MS);

  return (
    <figure className="history">
      <figcaption className="history-caption">
        Monthly price at {tenure} months, from {shortDate(history.collectingSince)}
        {view.lowest90 !== null ? (
          <> · lowest in the last 90 days: <b>{formatPaise(view.lowest90)}</b></>
        ) : (
          <> · collecting for {collectedDays} day{collectedDays === 1 ? '' : 's'} — 90-day lows appear at 90</>
        )}
      </figcaption>

      {view.providers.length >= 2 && (
        <div className="history-legend">
          {view.providers.map((p) => (
            <span key={p}>
              <i style={{ background: view.colorOf(p) }} /> {providerLabel(p)}
            </span>
          ))}
        </div>
      )}

      <div className="history-plot">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Price history chart for ${productId}`}>
          {view.yTicks.map((paise) => (
            <g key={paise}>
              <line x1={PAD.left} x2={W - PAD.right} y1={view.y(paise)} y2={view.y(paise)} stroke={GRID} strokeWidth={1} />
              <text x={PAD.left - 8} y={view.y(paise) + 4} textAnchor="end" fontSize={11} fill={INK_MUTED}>
                {formatPaise(paise)}
              </text>
            </g>
          ))}
          <line x1={PAD.left} x2={W - PAD.right} y1={view.y(0)} y2={view.y(0)} stroke={BASELINE} strokeWidth={1} />
          <text x={PAD.left} y={H - 8} fontSize={11} fill={INK_MUTED}>
            {shortDate(new Date(view.t0).toISOString())}
          </text>
          <text x={W - PAD.right} y={H - 8} textAnchor="end" fontSize={11} fill={INK_MUTED}>
            today
          </text>

          {view.lines.map(({ series: s, d, color, dashed }) => (
            <g key={`${s.provider}|${s.externalId}`}>
              <path d={d} fill="none" stroke={color} strokeWidth={2} strokeDasharray={dashed ? '5 4' : undefined} />
              {s.points.map(([date, paise]) => (
                <circle
                  key={date}
                  cx={view.x(new Date(date).getTime())}
                  cy={view.y(paise)}
                  r={4}
                  fill={color}
                  stroke="#ffffff"
                  strokeWidth={2}
                  onMouseEnter={() =>
                    setHover({
                      x: view.x(new Date(date).getTime()),
                      y: view.y(paise),
                      date,
                      paise,
                      label: providerLabel(s.provider),
                    })
                  }
                  onMouseLeave={() => setHover(null)}
                />
              ))}
              {/* Direct label at line end — identity never rides on color alone. */}
              <text
                x={view.x(view.t1) + 6}
                y={view.y(s.points[s.points.length - 1][1]) + 4}
                fontSize={11}
                fill="#52514e"
              >
                {providerLabel(s.provider)}
              </text>
            </g>
          ))}
        </svg>
        {hover && (
          <div className="history-tooltip" style={{ left: `${(hover.x / W) * 100}%`, top: `${(hover.y / H) * 100}%` }}>
            <b>{formatPaise(hover.paise)}</b>/mo · {hover.label} · {shortDate(hover.date)}
          </div>
        )}
      </div>

      <details className="history-table">
        <summary>Data table</summary>
        <table>
          <thead>
            <tr>
              <th>Observed</th>
              <th>Provider</th>
              <th>Listing</th>
              <th>Monthly</th>
            </tr>
          </thead>
          <tbody>
            {view.series.flatMap((s: HistorySeries) =>
              s.points.map(([date, paise]) => (
                <tr key={`${s.provider}|${s.externalId}|${date}`}>
                  <td>{shortDate(date)}</td>
                  <td>{providerLabel(s.provider)}</td>
                  <td>{s.externalId}</td>
                  <td>{formatPaise(paise)}</td>
                </tr>
              )),
            )}
          </tbody>
        </table>
      </details>
    </figure>
  );
}
