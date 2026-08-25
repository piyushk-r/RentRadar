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
};

export const TENURES = [3, 6, 9, 12, 18, 24] as const;
export type TenureMonths = (typeof TENURES)[number];
