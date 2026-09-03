'use client';

// Pincode serviceability — honestly city-level. No provider publishes a
// machine-readable pincode list we may read, so this answers exactly what the
// hand-checked data can: is the pincode in Bengaluru, and which providers
// operate there. Exact-address confirmation stays on the provider's own site.

import { useState } from 'react';
import type { City } from '../lib/types';
import { providerLabel } from '../lib/types';

export function PincodeCheck({
  serviceability,
  liveProviders,
}: {
  serviceability: City;
  liveProviders: string[];
}) {
  const [pincode, setPincode] = useState('');

  const trimmed = pincode.trim();
  const valid = /^\d{6}$/.test(trimmed);
  const inCity =
    valid && serviceability.pincodeRanges.some(([lo, hi]) => Number(trimmed) >= lo && Number(trimmed) <= hi);

  const known = liveProviders.filter((p) => serviceability.providers[p]);

  return (
    <div className="pincode-check">
      <label>
        Serviceability
        <input
          inputMode="numeric"
          maxLength={6}
          placeholder={`${serviceability.label} pincode`}
          value={pincode}
          onChange={(e) => setPincode(e.target.value.replace(/\D/g, ''))}
        />
      </label>
      {valid && (
        <span className={`pincode-result${inCity ? ' ok' : ''}`}>
          {inCity ? (
            <>
              {trimmed} is in {serviceability.label} —{' '}
              {known.map((p) => providerLabel(p)).join(', ')} operate here (city-level; confirm your exact
              address on the provider&rsquo;s page).
            </>
          ) : (
            <>
              {trimmed} is outside {serviceability.label} — this comparison covers{' '}
              {serviceability.label} only for now.
            </>
          )}
        </span>
      )}
    </div>
  );
}
