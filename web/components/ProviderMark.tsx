'use client';

// A provider's own mark beside its name, so a column is identifiable at a
// glance rather than by reading. The marks are each provider's published
// favicon, stored locally (never hotlinked, which would spend their
// bandwidth) and used only to identify them next to a link to their listing.
//
// If a mark is missing or fails to load the name still stands on its own —
// identity never depends on the image.

import { useState } from 'react';
import { providerLabel } from '../lib/types';

const MARKS: Record<string, string> = {
  rentomojo: '/logos/rentomojo.ico',
  guarented: '/logos/guarented.ico',
  furlenco: '/logos/furlenco.ico',
};

export function ProviderMark({ provider, size = 18 }: { provider: string; size?: number }) {
  const [broken, setBroken] = useState(false);
  const src = MARKS[provider];
  if (!src || broken) return null;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      className="provider-mark"
      src={src}
      alt=""
      aria-hidden="true"
      width={size}
      height={size}
      loading="lazy"
      onError={() => setBroken(true)}
    />
  );
}

export function ProviderName({ provider, size = 18 }: { provider: string; size?: number }) {
  return (
    <span className="provider-name">
      <ProviderMark provider={provider} size={size} />
      {providerLabel(provider)}
    </span>
  );
}
