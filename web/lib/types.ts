// Mirrors the data/ file shapes written by the Java pipeline (PRD section 17).
// The pipeline resolves every cost figure; nothing here recomputes business logic.

export type Availability = 'IN_STOCK' | 'OUT_OF_STOCK' | 'UNKNOWN';

export interface PriceRecord {
  provider: string;
  externalId: string;
  canonicalProductId: string;
  providerName: string;
  city: string;
  tenureMonths: number;
  monthlyPaise: number;
  monthlyTaxPaise: number;
  depositPaise: number;
  deliveryFeePaise: number;
  installationFeePaise: number;
  otherFeesPaise: number;
  discountPaise: number;
  estimatedTotalPaise: number;
  cashUpfrontPaise: number;
  availability: Availability;
  imageUrl: string | null;
  providerUrl: string;
  scrapedAt: string;
}

export interface PricesFile {
  records: PriceRecord[];
}

export interface CatalogueEntry {
  id: string;
  category: string;
  name: string;
  attributes: Record<string, string>;
}

export interface CatalogueFile {
  products: CatalogueEntry[];
}

export interface ProviderRun {
  status: 'OK' | 'DEGRADED' | 'FAILED';
  displayName: string | null;
  integrationType: 'API' | 'SCRAPE_HTML' | 'SCRAPE_BROWSER' | 'MANUAL' | null;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  productsFound: number;
  previousProductsFound: number;
  coverageDeltaPercent: number | null;
  error: string | null;
  warnings: string[];
}

export interface RunsFile {
  lastRun: { startedAt: string; finishedAt: string } | null;
  providers: Record<string, ProviderRun>;
}

export const PROVIDER_NAMES: Record<string, string> = {
  rentomojo: 'RentoMojo',
  guarented: 'Guarented',
  cityfurnish: 'Cityfurnish',
  furlenco: 'Furlenco',
  payrentz: 'Payrentz',
};

export function providerLabel(id: string): string {
  return PROVIDER_NAMES[id] ?? id.charAt(0).toUpperCase() + id.slice(1);
}

/** Display order and labels for the categories the site knows how to show. */
export const CATEGORY_LABELS: Record<string, { plural: string; singular: string }> = {
  BED: { plural: 'Beds', singular: 'Bed' },
  MATTRESS: { plural: 'Mattresses', singular: 'Mattress' },
  REFRIGERATOR: { plural: 'Refrigerators', singular: 'Refrigerator' },
  WASHING_MACHINE: { plural: 'Washing machines', singular: 'Washing machine' },
  SOFA: { plural: 'Sofas', singular: 'Sofa' },
  WARDROBE: { plural: 'Wardrobes', singular: 'Wardrobe' },
  STUDY_TABLE: { plural: 'Study tables', singular: 'Study table' },
  OFFICE_CHAIR: { plural: 'Office chairs', singular: 'Office chair' },
  DINING_TABLE: { plural: 'Dining tables', singular: 'Dining table' },
  TV: { plural: 'TVs', singular: 'TV' },
  AIR_CONDITIONER: { plural: 'Air conditioners', singular: 'Air conditioner' },
  MICROWAVE: { plural: 'Microwaves', singular: 'Microwave' },
  AIR_COOLER: { plural: 'Air coolers', singular: 'Air cooler' },
  WATER_PURIFIER: { plural: 'Water purifiers', singular: 'Water purifier' },
  OTHER: { plural: 'Other items', singular: 'Other item' },
};

export function categorySlug(category: string): string {
  return category.toLowerCase().replace(/_/g, '-');
}

export const TENURES = [3, 6, 9, 12, 18, 24] as const;
export type TenureMonths = (typeof TENURES)[number];

// ---- price history (data/history/{productId}.json, AC-2.1) ----

export interface HistorySeries {
  provider: string;
  externalId: string;
  tenureMonths: number;
  /** Last commit that still carried this listing — points record changes only. */
  lastSeen: string | null;
  /** [isoDate, monthlyPaise] — a point exists only where a run observed a change. */
  points: [string, number][];
}

export interface HistoryFile {
  productId: string;
  collectingSince: string;
  series: HistorySeries[];
}

// ---- provider reliability (data/provider-stats.json, best-value input) ----

export interface ProviderStats {
  asOf: string | null;
  providers: Record<string, { successRate30d: number; samples: number }>;
}

// ---- setup templates (data/setups.yml → setups.json, FR-4.5) ----

export interface SetupTemplate {
  apartment: string;
  occupants: number;
  label: string;
  /** category slug → quantity */
  items: Record<string, number>;
}

export interface SetupsFile {
  setups: SetupTemplate[];
}

// ---- cities + serviceability (data/cities.json, hand-checked, AC-3.3) ----

export interface City {
  id: string;
  label: string;
  pincodeRanges: [number, number][];
  note: string;
  providers: Record<string, { claim: string; source: string; checkedAt: string }>;
}

export interface CitiesFile {
  cities: City[];
}
