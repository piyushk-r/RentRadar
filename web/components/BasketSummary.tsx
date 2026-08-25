'use client';

import type { BasketResult } from '../lib/compare';
import { formatPaise } from '../lib/money';
import { CATEGORY_LABELS, providerLabel } from '../lib/types';

/**
 * The two answers and the delta (PRD section 9): cheapest cross-provider
 * basket, cheapest single provider, and what splitting actually saves —
 * stated plainly, because four delivery windows for ₹120/month is a real
 * trade-off the user should make consciously.
 */
export function BasketSummary({ result, tenure }: { result: BasketResult; tenure: number }) {
  const { picks, uncovered, mixed, mixedProviders, bestSingle, partialProviders, deltaMonthlyPaise } = result;

  return (
    <div className="basket">
      <h2>Your setup · {tenure} months</h2>

      {picks.length > 0 && (
        <table className="basket-picks">
          <caption className="visually-hidden">Cheapest provider for each item in your setup</caption>
          <tbody>
            {picks.map((pick) => (
              <tr key={pick.category}>
                <td className="pick-what">
                  {pick.qty > 1 ? `${pick.qty} × ` : ''}
                  {CATEGORY_LABELS[pick.category]?.singular ?? pick.category}
                </td>
                <td className="pick-who">
                  <a href={pick.record.providerUrl} rel="noopener noreferrer" target="_blank">
                    {pick.product.name}
                  </a>{' '}
                  · {providerLabel(pick.provider)}
                </td>
                <td className="pick-price">
                  {formatPaise(pick.record.monthlyPaise * pick.qty)}
                  <span className="per">/mo</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {uncovered.length > 0 && (
        <p className="uncovered">
          No current price for: {uncovered.map((c) => CATEGORY_LABELS[c]?.singular ?? c).join(', ')} — excluded from
          the totals below.
        </p>
      )}

      {mixed && (
        <div className="basket-answers">
          <div className="answer">
            <h3>Cheapest mix{mixedProviders.length > 1 ? ` (${mixedProviders.map(providerLabel).join(' + ')})` : ''}</h3>
            <p className="big">
              {formatPaise(mixed.monthlyPaise)}
              <span className="per">/mo</span>
            </p>
            <p className="small">
              {formatPaise(mixed.estimatedTotalPaise)} total over {tenure} months ·{' '}
              {formatPaise(mixed.depositPaise)} refundable deposit
            </p>
          </div>

          <div className="answer">
            <h3>Cheapest single provider</h3>
            {bestSingle ? (
              <>
                <p className="big">
                  {formatPaise(bestSingle.monthlyPaise)}
                  <span className="per">/mo</span>
                </p>
                <p className="small">
                  {providerLabel(bestSingle.provider)} · {formatPaise(bestSingle.estimatedTotalPaise)} total ·{' '}
                  {formatPaise(bestSingle.depositPaise)} deposit
                </p>
              </>
            ) : (
              <p className="small">No single provider currently stocks everything in your setup.</p>
            )}
          </div>

          {deltaMonthlyPaise !== null && (
            <div className="answer delta">
              <h3>Splitting saves</h3>
              <p className="big">
                {formatPaise(deltaMonthlyPaise)}
                <span className="per">/mo</span>
              </p>
              <p className="small">
                {deltaMonthlyPaise === 0
                  ? 'One provider is just as cheap — one delivery, one deposit to chase.'
                  : `Against ${mixedProviders.length} deliveries and ${mixedProviders.length} support relationships — your call.`}
              </p>
            </div>
          )}
        </div>
      )}

      {partialProviders.length > 0 && (
        <p className="partial">
          Partial coverage:{' '}
          {partialProviders
            .map(
              (p) =>
                `${providerLabel(p.provider)} (${p.coveredCategories} of ${p.totalCategories} items, ${formatPaise(p.monthlyPaise)}/mo)`,
            )
            .join(' · ')}
        </p>
      )}
    </div>
  );
}
