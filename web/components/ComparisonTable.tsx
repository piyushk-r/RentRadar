'use client';

import { Fragment, useState } from 'react';
import type { Cell, Comparison, Row } from '../lib/compare';
import { formatAge } from '../lib/freshness';
import { formatPaise } from '../lib/money';
import { ProviderName } from './ProviderMark';
import { PriceHistory } from './PriceHistory';

/**
 * One cell, one of four honest states: priced, not offered, out of stock, or
 * stale — each visually distinct, never blank (FR-2.3, PRD section 8). Plus
 * the tenure-specific state: the provider stocks the product but publishes no
 * plan for this tenure, which is said outright rather than derived.
 */
function PriceCell({ cell, tenure, now }: { cell: Cell; tenure: number; now: Date }) {
  if (cell.state === 'not-offered') {
    return <span className="state-note">Not offered</span>;
  }
  if (cell.state === 'not-published-for-tenure') {
    return (
      <span className="state-note">
        No {tenure}-month plan
        {cell.anyTenureRecord && (
          <>
            {' · '}
            <a href={cell.anyTenureRecord.providerUrl} rel="noopener noreferrer" target="_blank">
              view
            </a>
          </>
        )}
      </span>
    );
  }
  if (cell.state === 'out-of-stock' || !cell.record || !cell.band) {
    return <span className="state-note">Out of stock</span>;
  }

  const record = cell.record;
  const dim = cell.band === 'outdated' || cell.band === 'unverified';

  return (
    <div className={`price-cell${dim ? ' dim' : ''}`}>
      {cell.isBest && <span className="best-badge">Best price</span>}
      <div className="monthly">
        <a href={record.providerUrl} rel="noopener noreferrer" target="_blank">
          {formatPaise(record.monthlyPaise)}
        </a>
        <span className="per">/mo</span>
      </div>
      <div className="sub">
        {formatPaise(record.estimatedTotalPaise)} total · {formatPaise(record.depositPaise)} deposit (refundable)
      </div>
      {cell.band === 'outdated' && (
        <div className="stale-note">Price may be outdated — checked {formatAge(record.scrapedAt, now)}</div>
      )}
      {cell.band === 'unverified' && (
        <div className="unverified-note">Not verified — checked {formatAge(record.scrapedAt, now)}</div>
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

  /** Latest scrapedAt per provider column, for the header's checked-at line. */
  const columnCheckedAt: Record<string, string | null> = {};
  for (const provider of providers) {
    let latest: string | null = null;
    for (const row of rows) {
      const record = row.cells[provider]?.record ?? row.cells[provider]?.anyTenureRecord;
      if (record && (!latest || record.scrapedAt > latest)) latest = record.scrapedAt;
    }
    columnCheckedAt[provider] = latest;
  }

  return (
    <>
      <table className="compare">
        <caption className="visually-hidden">
          {categoryLabel ?? 'Rental'} prices in Bengaluru for a {tenure}-month tenure, by provider
        </caption>
        <thead>
          <tr>
            <th scope="col">Product</th>
            {providers.map((provider) => (
              <th key={provider} scope="col">
                <ProviderName provider={provider} />
                <span className="checked">
                  {providerTypes[provider] === 'MANUAL' ? 'manual sheet · ' : ''}
                  {columnCheckedAt[provider] ? `checked ${formatAge(columnCheckedAt[provider]!, now)}` : ''}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <Fragment key={row.product.id}>
              <tr>
                <th scope="row">
                  <div className="product-name">
                    {row.product.name}
                    {why[row.product.id] && (
                      <span className="why-rank" tabIndex={0} title={why[row.product.id]} aria-label="Why this ranked here">
                        ⓘ
                      </span>
                    )}
                  </div>
                  <div className="product-attrs">
                    {Object.entries(row.product.attributes)
                      .map(([key, value]) => `${key.replace(/_/g, ' ')}: ${value.replace(/_/g, ' ')}`)
                      .join(' · ')}
                  </div>
                  <button
                    type="button"
                    className="history-toggle"
                    aria-expanded={historyFor === row.product.id}
                    onClick={() => setHistoryFor((current) => (current === row.product.id ? null : row.product.id))}
                  >
                    {historyFor === row.product.id ? 'Hide price history' : 'Price history'}
                  </button>
                </th>
                {providers.map((provider) => (
                  <td key={provider}>
                    <PriceCell cell={row.cells[provider]} tenure={tenure} now={now} />
                  </td>
                ))}
              </tr>
              {historyFor === row.product.id && (
                <tr className="history-row">
                  <td colSpan={providers.length + 1}>
                    <PriceHistory productId={row.product.id} tenure={tenure} now={now} />
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td>Total per month</td>
            {providers.map((provider) => {
              const total = totals[provider];
              return (
                <td key={provider}>
                  {total.itemsCovered > 0 ? (
                    <>
                      {formatPaise(total.monthlyPaise)}
                      <div className="coverage-note">
                        {total.itemsCovered} of {total.itemsRequested} items · {formatPaise(total.depositPaise)} deposit
                      </div>
                    </>
                  ) : (
                    <span className="state-note">—</span>
                  )}
                </td>
              );
            })}
          </tr>
        </tfoot>
      </table>

      {/* Mobile: one card per product, providers ranked inside (PRD section 8). */}
      <div className="cards">
        {rows.map((row) => (
          <div className="card" key={row.product.id}>
            <h3>{row.product.name}</h3>
            {providers.map((provider) => (
              <div className="provider-line" key={provider}>
                <span className="who"><ProviderName provider={provider} size={16} /></span>
                <PriceCell cell={row.cells[provider]} tenure={tenure} now={now} />
              </div>
            ))}
            <button
              type="button"
              className="history-toggle"
              aria-expanded={historyFor === row.product.id}
              onClick={() => setHistoryFor((current) => (current === row.product.id ? null : row.product.id))}
            >
              {historyFor === row.product.id ? 'Hide price history' : 'Price history'}
            </button>
            {historyFor === row.product.id && <PriceHistory productId={row.product.id} tenure={tenure} now={now} />}
          </div>
        ))}
      </div>

    </>
  );
}
