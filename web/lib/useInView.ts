'use client';

// Fires once when an element first comes near the viewport. Used to defer a
// category's price file until the visitor actually reaches that section
// (FR-8.1: someone comparing fridges should not download the sofas).

import { useEffect, useRef, useState } from 'react';

export function useInView<T extends HTMLElement>(rootMargin = '400px') {
  const ref = useRef<T | null>(null);
  const [inView, setInView] = useState(false);

  useEffect(() => {
    if (inView) return;
    const element = ref.current;
    if (!element) return;
    // No IntersectionObserver (old browser, or a crawler): show everything
    // rather than an empty page.
    if (typeof IntersectionObserver === 'undefined') {
      setInView(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setInView(true);
          observer.disconnect();
        }
      },
      { rootMargin },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [inView, rootMargin]);

  return { ref, inView };
}
