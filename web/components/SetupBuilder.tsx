'use client';

// Build my rental setup (PRD §12): "2BHK, two people, one year" → a basket.
// Templates come from data/setups.yml (FR-4.5) and are starting points only —
// applying one fills the ordinary basket, fully editable before comparison
// (FR-4.3). The engine downstream is the same one every search uses.

import { useState } from 'react';
import type { BasketItem } from '../lib/compare';
import type { SetupsFile } from '../lib/types';
import { CATEGORY_LABELS } from '../lib/types';

const APARTMENTS = [
  { id: '1bhk', label: '1BHK' },
  { id: '2bhk', label: '2BHK' },
  { id: '3bhk', label: '3BHK' },
];
const OCCUPANTS = [
  { id: 1, label: '1 person' },
  { id: 2, label: '2 people' },
  { id: 3, label: '3+' },
];

export function SetupBuilder({
  setups,
  onApply,
}: {
  setups: SetupsFile;
  onApply: (items: BasketItem[]) => void;
}) {
  const [apartment, setApartment] = useState('2bhk');
  const [occupants, setOccupants] = useState(2);

  const template = setups.setups.find((s) => s.apartment === apartment && s.occupants === occupants) ?? null;

  const preview = template
    ? Object.entries(template.items)
        .map(([slug, qty]) => {
          const category = slug.toUpperCase().replace(/-/g, '_');
          const label = CATEGORY_LABELS[category];
          if (!label) return null;
          return qty > 1 ? `${qty} ${label.plural.toLowerCase()}` : label.singular.toLowerCase();
        })
        .filter(Boolean)
        .join(', ')
    : null;

  function apply() {
    if (!template) return;
    const items: BasketItem[] = [];
    for (const [slug, qty] of Object.entries(template.items)) {
      const category = slug.toUpperCase().replace(/-/g, '_');
      if (CATEGORY_LABELS[category]) items.push({ category, qty });
    }
    onApply(items);
  }

  return (
    <div className="setup-builder">
      <span className="setup-lead">Build my setup:</span>
      <select aria-label="Apartment type" value={apartment} onChange={(e) => setApartment(e.target.value)}>
        {APARTMENTS.map((a) => (
          <option key={a.id} value={a.id}>
            {a.label}
          </option>
        ))}
      </select>
      <select aria-label="Occupants" value={occupants} onChange={(e) => setOccupants(Number(e.target.value))}>
        {OCCUPANTS.map((o) => (
          <option key={o.id} value={o.id}>
            {o.label}
          </option>
        ))}
      </select>
      <button type="button" className="setup-apply" disabled={!template} onClick={apply}>
        Suggest a basket
      </button>
      {preview && <span className="setup-preview">→ {preview} (edit freely below)</span>}
    </div>
  );
}
