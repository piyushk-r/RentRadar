// Build-time data access for the pre-rendered §22 pages. Server-side only:
// runs inside `next build` after prepare-data has validated and copied data/.
// The interactive pages never import this — they fetch at runtime.

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type {
  CatalogueEntry,
  CatalogueFile,
  CitiesFile,
  City,
  PriceRecord,
  PricesFile,
  RunsFile,
} from './types';

const dataDir = () => join(process.cwd(), 'public', 'data');

function readJson<T>(...segments: string[]): T | null {
  const file = join(dataDir(), ...segments);
  if (!existsSync(file)) return null;
  return JSON.parse(readFileSync(file, 'utf8')) as T;
}

export interface BuildData {
  catalogue: CatalogueEntry[];
  records: PriceRecord[];
  runs: RunsFile | null;
  cities: City[];
}

/** Bengaluru is the fallback only when cities.json is absent entirely. */
const DEFAULT_CITY: City = {
  id: 'bangalore',
  label: 'Bengaluru',
  pincodeRanges: [[560001, 560129]],
  note: '',
  providers: {},
};

export function loadBuildData(): BuildData {
  const catalogue = readJson<CatalogueFile>('catalogue.json')?.products ?? [];
  const records: PriceRecord[] = [];
  const pricesDir = join(dataDir(), 'prices');
  if (existsSync(pricesDir)) {
    for (const name of readdirSync(pricesDir).sort()) {
      if (!name.endsWith('.json')) continue;
      records.push(...(readJson<PricesFile>('prices', name)?.records ?? []));
    }
  } else {
    records.push(...(readJson<PricesFile>('prices.json')?.records ?? []));
  }
  const cities = readJson<CitiesFile>('cities.json')?.cities;
  return {
    catalogue,
    records,
    runs: readJson<RunsFile>('runs.json'),
    cities: cities && cities.length > 0 ? cities : [DEFAULT_CITY],
  };
}
