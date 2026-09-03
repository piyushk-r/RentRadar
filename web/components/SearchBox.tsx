'use client';

// The free-text entry point (AC-3.1). What it understood is shown back as
// ordinary chips and filters the user can correct — the parse is a suggestion
// the UI makes visible, never a hidden interpretation.

import { useState } from 'react';
import { parseQuery, type ParsedQuery } from '../lib/nlq';
import { CATEGORY_LABELS } from '../lib/types';

const EXAMPLES = [
  '2 beds and a fridge for 12 months',
  'bachelor setup under ₹3000',
  'washing machine with deposit under 5000',
];

export function SearchBox({ onParsed }: { onParsed: (parsed: ParsedQuery) => void }) {
  const [text, setText] = useState('');
  const [lastParse, setLastParse] = useState<ParsedQuery | null>(null);

  function submit(value: string) {
    const trimmed = value.trim();
    if (!trimmed) return;
    const parsed = parseQuery(trimmed);
    setLastParse(parsed);
    if (parsed.items.length > 0) onParsed(parsed);
  }

  return (
    <div className="searchbox">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit(text);
        }}
      >
        <input
          type="search"
          value={text}
          aria-label="Describe what you need"
          placeholder="Describe what you need — “2 beds and a fridge for 12 months”"
          onChange={(e) => setText(e.target.value)}
        />
        <button type="submit">Search</button>
      </form>

      {!lastParse && (
        <p className="search-examples">
          Try:{' '}
          {EXAMPLES.map((example, i) => (
            <span key={example}>
              {i > 0 && ' · '}
              <button
                type="button"
                className="linkish"
                onClick={() => {
                  setText(example);
                  submit(example);
                }}
              >
                {example}
              </button>
            </span>
          ))}
        </p>
      )}

      {lastParse && (
        <p className="search-readback">
          {lastParse.items.length > 0 ? (
            <>
              Showing{' '}
              <b>
                {lastParse.items
                  .map((i) => `${i.qty > 1 ? `${i.qty} ` : ''}${(i.qty > 1
                    ? CATEGORY_LABELS[i.category].plural
                    : CATEGORY_LABELS[i.category].singular
                  ).toLowerCase()}`)
                  .join(', ')}
              </b>
              {lastParse.tenureMonths ? ` for ${lastParse.tenureMonths} months` : ''}
              {lastParse.monthlyMaxRupees ? ` under ₹${lastParse.monthlyMaxRupees}/mo` : ''}
              {lastParse.depositMaxRupees ? ` with deposit under ₹${lastParse.depositMaxRupees}` : ''}.
            </>
          ) : (
            <>Nothing recognized yet — pick categories below, or try one of the examples.</>
          )}
          {lastParse.unrecognized.length > 0 && (
            <span className="search-unparsed"> Ignored: {lastParse.unrecognized.join(', ')}.</span>
          )}
        </p>
      )}
    </div>
  );
}
