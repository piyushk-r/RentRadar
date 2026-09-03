'use client';

import { useEffect, useState } from 'react';
import { formatAge } from '../../lib/freshness';
import type { RunsFile } from '../../lib/types';
import { providerLabel } from '../../lib/types';
import { ProviderMark } from '../../components/ProviderMark';
import { tidyMessage as tidy } from '../../lib/tidy';

/**
 * The admin panel that isn't one (PRD section 19): public, read-only pipeline
 * health from runs.json. If the pipeline has been dead for a week, this page
 * says so to anyone (FR-7.6).
 */
/** At most this many warnings per provider; the rest are counted, not listed. */
const MAX_WARNINGS = 5;

export default function Status() {
  const [runs, setRuns] = useState<RunsFile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState<Date | null>(null);

  useEffect(() => {
    setNow(new Date());
    fetch('/data/runs.json', { cache: 'no-store' })
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json() as Promise<RunsFile>;
      })
      .then(setRuns)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  const providers = runs ? Object.entries(runs.providers) : [];

  return (
    <main>
      <div className="status-wrap">
        <a className="back-link" href="/">
          ← RentRadar
        </a>
        <h1>Pipeline status</h1>
        <p className="status-sub">
          {runs?.lastRun && now
            ? `Last run finished ${formatAge(runs.lastRun.finishedAt, now)}. Data refreshes on a 12-hour schedule; delays under platform load are normal.`
            : 'Waiting for run data…'}
        </p>

        {error && <p className="empty">runs.json could not be loaded ({error}).</p>}

        {runs && now && (
          <table className="status">
            <thead>
              <tr>
                <th scope="col">Provider</th>
                <th scope="col">Route</th>
                <th scope="col">Status</th>
                <th scope="col">Last success</th>
                <th scope="col">Products</th>
                <th scope="col">Detail</th>
              </tr>
            </thead>
            <tbody>
              {providers.map(([id, run]) => (
                <tr key={id}>
                  <th scope="row">
                    <span className="provider-name">
                      <ProviderMark provider={id} />
                      {run.displayName ?? providerLabel(id)}
                    </span>
                  </th>
                  <td>
                    {run.integrationType === 'MANUAL'
                      ? 'manual sheet'
                      : run.integrationType === 'SCRAPE_BROWSER'
                        ? 'browser'
                        : run.integrationType === 'SCRAPE_HTML'
                          ? 'html'
                          : run.integrationType === 'API'
                            ? 'api'
                            : '—'}
                  </td>
                  <td>
                    <span className={`chip ${run.status.toLowerCase()}`}>{run.status}</span>
                  </td>
                  <td>{run.lastSuccessAt ? formatAge(run.lastSuccessAt, now) : 'never'}</td>
                  <td>
                    {run.status === 'FAILED' ? '—' : run.productsFound}
                    {run.coverageDeltaPercent != null && run.coverageDeltaPercent !== 0 && (
                      <span className="coverage-note">
                        {' '}
                        ({run.coverageDeltaPercent > 0 ? '+' : ''}
                        {run.coverageDeltaPercent}% vs. previous)
                      </span>
                    )}
                  </td>
                  <td>
                    {run.error && <div className="error-text">{tidy(run.error)}</div>}
                    {run.warnings.slice(0, MAX_WARNINGS).map((warning) => (
                      <div key={warning} className="coverage-note">
                        {tidy(warning)}
                      </div>
                    ))}
                    {run.warnings.length > MAX_WARNINGS && (
                      <div className="coverage-note">
                        …and {run.warnings.length - MAX_WARNINGS} more of the same kind
                      </div>
                    )}
                    {!run.error && run.warnings.length === 0 && '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <footer>
        <span>RentRadar · Bengaluru</span>
        <a href="https://github.com/piyushk-r/RentRadar" rel="noopener noreferrer" target="_blank">
          Source
        </a>
      </footer>
    </main>
  );
}