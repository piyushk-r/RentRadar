// Natural-language search (AC-3.1): free text in, a structured query out,
// handed to the existing engine. Two rules shape the whole design.
//
// 1. It never computes or estimates a price. It only selects categories,
//    quantities, a tenure and filter bounds; every rupee still comes from the
//    pipeline's resolved records.
// 2. It is deterministic and local — a lookup table and a few regexes, not a
//    model. A static site has no server to call and no key to spend, so the
//    "model" here is vocabulary. Anything it cannot parse is reported as
//    unparsed rather than guessed, and the user's own words stay on screen.

import { CATEGORY_LABELS } from './types';
import type { SortMode } from './rank';
import { TENURES } from './types';

export interface ParsedQuery {
  items: { category: string; qty: number }[];
  tenureMonths: number | null;
  monthlyMaxRupees: number | null;
  depositMaxRupees: number | null;
  sort: SortMode | null;
  /** Words that matched nothing — shown back, never silently dropped. */
  unrecognized: string[];
}

/** Category vocabulary: the words people actually type, per canonical category. */
const SYNONYMS: Record<string, string[]> = {
  BED: ['bed', 'beds', 'cot', 'double bed', 'queen bed', 'king bed', 'single bed'],
  MATTRESS: ['mattress', 'mattresses', 'gadda'],
  REFRIGERATOR: ['fridge', 'fridges', 'refrigerator', 'refrigerators'],
  WASHING_MACHINE: ['washing machine', 'washing machines', 'washer', 'wm'],
  SOFA: ['sofa', 'sofas', 'couch', 'couches', 'settee'],
  WARDROBE: ['wardrobe', 'wardrobes', 'almirah', 'cupboard', 'cupboards'],
  STUDY_TABLE: ['study table', 'study tables', 'desk', 'desks', 'work table', 'wfh table'],
  OFFICE_CHAIR: ['office chair', 'office chairs', 'chair', 'chairs', 'study chair', 'desk chair'],
  DINING_TABLE: ['dining table', 'dining tables', 'dining set', 'dining'],
  TV: ['tv', 'tvs', 'television', 'televisions', 'led tv', 'smart tv'],
  AIR_CONDITIONER: ['ac', 'acs', 'air conditioner', 'air conditioners', 'aircon'],
  MICROWAVE: ['microwave', 'microwaves', 'oven', 'ovens'],
  AIR_COOLER: ['air cooler', 'air coolers', 'cooler', 'coolers'],
  WATER_PURIFIER: ['water purifier', 'water purifiers', 'ro', 'purifier', 'purifiers'],
};

/** Setup shorthands expand to the same templates the setup builder offers. */
const SETUP_PHRASES: { pattern: RegExp; items: Record<string, number> }[] = [
  {
    pattern: /\b(1\s*bhk|one\s*bhk|1bhk)\b/,
    items: { BED: 1, MATTRESS: 1, REFRIGERATOR: 1, WASHING_MACHINE: 1, WARDROBE: 1 },
  },
  {
    pattern: /\b(2\s*bhk|two\s*bhk|2bhk)\b/,
    items: { BED: 2, MATTRESS: 2, REFRIGERATOR: 1, WASHING_MACHINE: 1, SOFA: 1, DINING_TABLE: 1, WARDROBE: 2 },
  },
  {
    pattern: /\b(3\s*bhk|three\s*bhk|3bhk)\b/,
    items: { BED: 3, MATTRESS: 3, REFRIGERATOR: 1, WASHING_MACHINE: 1, SOFA: 1, DINING_TABLE: 1, WARDROBE: 3 },
  },
  {
    pattern: /\bbachelor\b/,
    items: { BED: 1, MATTRESS: 1, REFRIGERATOR: 1, WASHING_MACHINE: 1 },
  },
];

const NUMBER_WORDS: Record<string, number> = {
  a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9,
};

/** Longest phrases first, so "study chair" wins over "chair". */
const VOCABULARY = Object.entries(SYNONYMS)
  .flatMap(([category, words]) => words.map((word) => ({ category, word })))
  .sort((a, b) => b.word.length - a.word.length);

function normalize(text: string): string {
  return ` ${text.toLowerCase().replace(/[^a-z0-9₹.\s+-]/g, ' ').replace(/\s+/g, ' ').trim()} `;
}

/** "1.5k" / "1,500" / "₹1500" → 1500. Budgets only — never a price we display. */
function rupees(raw: string): number | null {
  const cleaned = raw.replace(/[₹,\s]/g, '');
  const thousands = /^(\d+(?:\.\d+)?)k$/.exec(cleaned);
  if (thousands) return Math.round(Number(thousands[1]) * 1000);
  const plain = /^(\d+(?:\.\d+)?)$/.exec(cleaned);
  return plain ? Math.round(Number(plain[1])) : null;
}

export function parseQuery(input: string): ParsedQuery {
  let text = normalize(input);
  const items = new Map<string, number>();
  const consumed: string[] = [];

  const consume = (phrase: string) => {
    text = text.replace(phrase, ' ');
    consumed.push(phrase.trim());
  };

  // Tenure: "for 6 months", "12 month", "1 year".
  const year = /\b(\d+)\s*(?:year|yr)s?\b/.exec(text);
  const month = /\b(\d+)\s*(?:month|mo)s?\b/.exec(text);
  let tenureMonths: number | null = null;
  if (month) {
    tenureMonths = Number(month[1]);
    consume(month[0]);
  } else if (year) {
    tenureMonths = Number(year[1]) * 12;
    consume(year[0]);
  }
  // Only a tenure the providers actually publish can be selected; anything
  // else is left unset rather than rounded into a plan that does not exist.
  if (tenureMonths != null && !TENURES.includes(tenureMonths as (typeof TENURES)[number])) {
    tenureMonths = null;
  }

  // Budgets: "under ₹2000", "below 1.5k a month", "deposit under 5000".
  let monthlyMaxRupees: number | null = null;
  let depositMaxRupees: number | null = null;
  const depositBudget = /\bdeposit\s*(?:under|below|less than|max|upto|up to)?\s*(₹?\s*[\d.,]+k?)\b/.exec(text);
  if (depositBudget) {
    depositMaxRupees = rupees(depositBudget[1]);
    consume(depositBudget[0]);
  }
  const monthlyBudget = /\b(?:under|below|less than|max|upto|up to|within)\s*(₹?\s*[\d.,]+k?)\b/.exec(text);
  if (monthlyBudget) {
    monthlyMaxRupees = rupees(monthlyBudget[1]);
    consume(monthlyBudget[0]);
  }

  // Sort intent.
  let sort: SortMode | null = null;
  if (/\bcheapest\b|\bcheap\b|\blowest price\b/.test(text)) sort = 'cheapest-total';
  if (/\blowest deposit\b|\bno deposit\b|\bleast deposit\b/.test(text)) sort = 'lowest-deposit';
  if (/\bbest value\b|\bbest deal\b/.test(text)) sort = 'best-value';
  if (/\bnewest\b|\brecent(?:ly)? updated\b/.test(text)) sort = 'recently-updated';

  // Whole-home shorthands before individual items, so "2bhk" does not also
  // read as "2 …" for whatever noun follows it.
  for (const { pattern, items: template } of SETUP_PHRASES) {
    const match = pattern.exec(text);
    if (match) {
      for (const [category, qty] of Object.entries(template)) {
        items.set(category, Math.max(items.get(category) ?? 0, qty));
      }
      consume(match[0]);
      break;
    }
  }

  // Items, longest phrase first, with an optional leading count.
  for (const { category, word } of VOCABULARY) {
    if (!CATEGORY_LABELS[category]) continue;
    const pattern = new RegExp(`(?:\\b(\\d+|${Object.keys(NUMBER_WORDS).join('|')})\\s+)?\\b${word}\\b`);
    const match = pattern.exec(text);
    if (!match) continue;
    const countWord = match[1];
    const qty = countWord ? Number(countWord) || NUMBER_WORDS[countWord] || 1 : 1;
    items.set(category, Math.min(Math.max(items.get(category) ?? qty, 1), 9));
    consume(match[0]);
  }

  // Whatever survived is vocabulary we do not have. Saying so is the honest
  // alternative to guessing (and it is how the vocabulary grows).
  const filler = new Set([
    'and', 'or', 'for', 'with', 'the', 'a', 'an', 'in', 'on', 'at', 'to', 'of', 'my', 'me', 'i', 'need',
    'want', 'rent', 'rental', 'renting', 'hire', 'looking', 'per', 'month', 'months', 'bengaluru',
    'bangalore', 'plus', 'some', 'few', 'new', 'good', 'best', 'cheapest', 'cheap', 'value', 'deal',
    'deposit', 'price', 'prices', 'under', 'below', 'max', 'home', 'house', 'flat', 'apartment', 'room',
    'setup', 'setups', 'bhk', 'furniture', 'appliance', 'appliances', 'everything', 'basic', 'basics',
  ]);
  const unrecognized = text
    .split(' ')
    .map((word) => word.trim())
    .filter((word) => word.length > 1 && !filler.has(word) && !/^[\d.,₹+-]+$/.test(word));

  return {
    items: [...items.entries()].map(([category, qty]) => ({ category, qty })),
    tenureMonths,
    monthlyMaxRupees,
    depositMaxRupees,
    sort,
    unrecognized: [...new Set(unrecognized)],
  };
}
