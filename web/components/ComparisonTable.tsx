'use client';

import { useState } from 'react';
import type { Cell, Comparison, Row } from '../lib/compare';
import { formatAge } from '../lib/freshness';
import { formatPaise } from '../lib/money';
import { ProviderName } from './ProviderMark';
import { PriceHistory } from './PriceHistory';

/**
 * One product per card, one panel per provider inside it. A flat grid reads
 * like a spreadsheet: what a reader actually does here is compare a handful of
 * offers for one product, so the offer is the unit that gets a surface, and
 * the row it belongs to is the container.
 *
 * Each panel keeps the four honest states — priced, not offered, out of stock,
 * or no plan at this tenure — visually distinct and never blank (FR-2.3).
 */
function OfferPanel({
  provider,
  cell,
  tenure,
  now,
  checkedAt,
  integrationType,
}: {
  provider: string;
  cell: Cell;
  tenure: number;
  now: Date;
  checkedAt: string | null;
  integrationType?: string | null;
}) {
  const priced = cell.state === 'priced' && cell.record && cell.band;
  const dim = cell.band === 'outdated' || cell.band === 'unverified';
  const record = cell.record;

  return (
    <div
      className={[
        'offer',
        priced ? 'is-priced' : 'is-absent',
        cell.isBest ? 'is-best' : '',
        dim ? 'is-dim' : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      <div className="offer-head">
        <ProviderName provider={provider} size={17} />
        {cell.isBest && <span className="best-badge">Best</span>}
      </div>

      {priced && record ? (
        <>
          <div className="offer-price">
            <a href={record.providerUrl} rel="noopener noreferrer" target="_blank">
              {formatPaise(record.monthlyPaise)}
            </a>
            <span className="per">/mo</span>
          </div>
          <dl className="offer-facts">
            <div>
              <dt>Total</dt>
              <dd>{formatPaise(record.estimatedTotalPaise)}</dd>
            </div>
            <div>
              <dt>Deposit</dt>
              <dd>{formatPaise(record.depositPaise)}</dd>
            </div>
          </dl>
          {cell.band === 'outdated' && (
            <div className="stale-note">May be outdated — checked {formatAge(record.scrapedAt, now)}</div>
          )}
          {cell.band === 'unverified' && (
            <div className="unverified-note">Not verified — checked {formatAge(record.scrapedAt, now)}</div>
          )}
        </>
      ) : (
        <div className="offer-absent">
          {cell.state === 'not-offered' && 'Not offered'}
          {cell.state === 'out-of-stock' && 'Out of stock'}
          {cell.state === 'not-published-for-tenure' && (
            <>
              No {tenure}-month plan
              {cell.anyTenureRecord && (
                <>
                  {' · '}
                  <a href={cell.anyTenureRecord.providerUrl} rel="noopener noreferrer" target="_blank">
                    view
                  </a>
                </>
              )}
            </>
          )}
        </div>
      )}

      {/* The stale notes above already carry the checked-at time; repeating it
          in the footer would just be noise. */}
      {!dim && (
        <div className="offer-foot">
          {integrationType === 'MANUAL' ? 'manual sheet · ' : ''}
          {checkedAt ? `checked ${formatAge(checkedAt, now)}` : ''}
        </div>
      )}
    </div>
  );
}

export function ComparisonTable({
  comparison,
  tenure,
  now,
  categoryLabel,
  providerTypes = {},
  orderedRows,
  why = {},
}: {
  comparison: Comparison;
  tenure: number;
  now: Date;
  categoryLabel?: string;
  /** integrationType per provider id from runs.json — manual columns are labelled as such. */
  providerTypes?: Record<string, string | null | undefined>;
  /** rows in the caller's filter/sort order; defaults to the comparison's own */
  orderedRows?: Row[];
  /** best-value breakdowns per product id ("why this ranked here", §11) */
  why?: Record<string, string>;
}) {
  const { providers, totals } = comparison;
  const rows = orderedRows ?? comparison.rows;
  const [historyFor, setHistoryFor] = useState<string | null>(null);

  if (rows.length === 0) {
    return <p className="empty">No priced products yet for this selection.</p>;
  }

  /** Latest scrapedAt per provider, for each panel's checked-at line. */
  const checkedAt: Record<string, string | null> = {};
  for (const provider of providers) {
    let latest: string | null = null;
    for (const row of rows) {
      const record = row.cells[provider]?.record ?? row.cells[provider]?.anyTenureRecord;
      if (record && (!latest || record.scrapedAt > latest)) latest = record.scrapedAt;
    }
    checkedAt[provider] = latest;
  }

  return (
    <div className="rows" aria-label={`${categoryLabel ?? 'Rental'} prices for a ${tenure}-month tenure`}>
      {rows.map((row) => (
        <article className="row-card" key={row.product.id}>
          <header className="row-head">
            <div>
              <h3 className="product-name">
                {row.product.name}
                {why[row.product.id] && (
                  <span className="why-rank" tabIndex={0} title={why[row.product.id]} aria-label="Why this ranked here">
                    ⓘ
                  </span>
                )}
              </h3>
              <div className="product-attrs">
                {Object.entries(row.product.attributes)
                  .map(([key, value]) => `${key.replace(/_/g, ' ')}: ${value.replace(/_/g, ' ')}`)
                  .join(' · ')}
              </div>
            </div>
            <button
              type="button"
              className="history-toggle"
              aria-expanded={historyFor === row.product.id}
              onClick={() => setHistoryFor((current) => (current === row.product.id ? null : row.product.id))}
            >
              {historyFor === row.product.id ? 'Hide history' : 'Price history'}
            </button>
          </header>

          <div className="offers" style={{ '--offer-count': providers.length } as React.CSSProperties}>
            {providers.map((provider) => (
              <OfferPanel
                key={provider}
                provider={provider}
                cell={row.cells[provider]}
                tenure={tenure}
                now={now}
                checkedAt={checkedAt[provider]}
                integrationType={providerTypes[provider]}
              />
            ))}
          </div>

          {historyFor === row.product.id && (
            <div className="row-history">
              <PriceHistory productId={row.product.id} tenure={tenure} now={now} />
            </div>
          )}
        </article>
      ))}

      <div className="totals-card">
        <div className="totals-label">Total per month, over the items each provider stocks</div>
        <div className="offers" style={{ '--offer-count': providers.length } as React.CSSProperties}>
          {providers.map((provider) => {
            const total = totals[provider];
            return (
              <div className="offer is-total" key={provider}>
                <div className="offer-head">
                  <ProviderName provider={provider} size={17} />
                </div>
                {total.itemsCovered > 0 ? (
                  <>
                    <div className="offer-price">{formatPaise(total.monthlyPaise)}</div>
                    <div className="offer-foot">
                      {total.itemsCovered} of {total.itemsRequested} items ·{' '}
                      {formatPaise(total.depositPaise)} deposit
                    </div>
                  </>
                ) : (
                  <div className="offer-absent">—</div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
