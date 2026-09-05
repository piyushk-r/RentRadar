'use client';

// Prices change under the reader when the tenure or a filter changes. Jumping
// straight to the new figure hides that something moved; counting to it shows
// which numbers were affected without anyone having to hunt for them.
//
// Deliberately cosmetic: the value is always the resolved one from the
// pipeline, and the animation only affects what is painted on the way there.

import { useEffect, useRef, useState } from 'react';

const DURATION = 420;

export function useCountUp(value: number): number {
  const [shown, setShown] = useState(value);
  const from = useRef(value);

  useEffect(() => {
    const start = from.current;
    if (start === value) return;

    const reduced =
      typeof window !== 'undefined' &&
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (reduced) {
      from.current = value;
      setShown(value);
      return;
    }

    let frame = 0;
    const began = performance.now();
    const step = (time: number) => {
      const progress = Math.min((time - began) / DURATION, 1);
      const eased = 1 - Math.pow(1 - progress, 3); // ease-out cubic
      setShown(Math.round(start + (value - start) * eased));
      if (progress < 1) {
        frame = requestAnimationFrame(step);
      } else {
        from.current = value;
      }
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [value]);

  return shown;
}
