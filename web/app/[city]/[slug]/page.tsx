// Pre-rendered landing pages (PRD §22): each is generated at build from the
// same data the comparison uses, with real prices and a visible last-checked
// date. No page is generated without data behind it — a thin page with an
// empty table is worse than no page. Timestamps here are absolute dates, so a
// cached page can never claim to be fresher than it is (FR-5.6); the live,
// relative freshness lives on the interactive comparison this page links to.
//
// The city segment comes from data/cities.json, so a second city brings its
// own set of these pages with no code change (AC-3.3).

import type { Metadata } from 'next';
import { formatPaise } from '../../../lib/money';
import { loadBuildData } from '../../../lib/seo-data';
import type { City, PriceRecord } from '../../../lib/types';
import { CATEGORY_LABELS, categorySlug, providerLabel } from '../../../lib/types';

const TENURE = 12; // the landing tenure; every page links to the full picker

interface PageDef {
  slug: string;
  title: string;
  description: string;
  intro: string;
  categories: string[];
  itemsQuery: string;
  /** keep only rows priced by every one of these providers */
  requireProviders?: string[];
  /** keep only the cheapest row per category */
  cheapestOnly?: boolean;
}

function categoryPages(present: string[], cityLabel: string): PageDef[] {
  return present
    .filter((c) => c !== 'OTHER')
    .map((category) => {
      const label = CATEGORY_LABELS[category];
      const slug = categorySlug(category);
      return {
        slug: `${slug}-on-rent`,
        title: `${label.singular} on rent in ${cityLabel} — live price comparison`,
        description: `Compare ${label.plural.toLowerCase()} on rent in ${cityLabel} across providers: monthly rent, deposit and total cost, refreshed twice a day.`,
        intro: `Monthly rents for ${label.plural.toLowerCase()} in ${cityLabel}, side by side. Every price links to the provider's own listing.`,
        categories: [category],
        itemsQuery: `${slug}:1`,
      };
    });
}

function specialPages(present: string[], providers: string[], cityLabel: string): PageDef[] {
  const pages: PageDef[] = [
    {
      slug: 'furniture-on-rent',
      title: `Furniture on rent in ${cityLabel} — compare every provider`,
      description: `One table for renting furniture and appliances in ${cityLabel}: beds, sofas, fridges, washing machines and more, compared across providers.`,
      intro: 'Every category we track, cheapest current option per provider.',
      categories: present.filter((c) => c !== 'OTHER'),
      itemsQuery: '',
      cheapestOnly: true,
    },
    {
      slug: '2bhk-furniture-rental',
      title: `2BHK furniture rental in ${cityLabel} — full setup compared`,
      description: `What a full 2BHK furniture setup costs to rent in ${cityLabel}: beds, mattresses, fridge, washing machine, sofa, dining table and wardrobes, across providers.`,
      intro: 'The classic 2BHK basket — two beds and mattresses, fridge, washing machine, sofa, dining table, wardrobes.',
      categories: ['BED', 'MATTRESS', 'REFRIGERATOR', 'WASHING_MACHINE', 'SOFA', 'DINING_TABLE', 'WARDROBE'].filter((c) =>
        present.includes(c),
      ),
      itemsQuery: 'bed:2,mattress:2,refrigerator:1,washing-machine:1,sofa:1,dining-table:1,wardrobe:2',
    },
    {
      slug: 'bachelor-room-setup',
      title: `Bachelor room setup on rent in ${cityLabel}`,
      description: `The essentials for a bachelor room in ${cityLabel} — bed, mattress, fridge and washing machine — compared across rental providers.`,
      intro: 'The essentials: a bed, a mattress, a fridge, a washing machine.',
      categories: ['BED', 'MATTRESS', 'REFRIGERATOR', 'WASHING_MACHINE'].filter((c) => present.includes(c)),
      itemsQuery: 'bed:1,mattress:1,refrigerator:1,washing-machine:1',
    },
    {
      slug: 'cheap-furniture-rental',
      title: `Cheapest furniture rental in ${cityLabel}, item by item`,
      description: `The cheapest current rental for each furniture and appliance category in ${cityLabel}, with the provider offering it.`,
      intro: 'The single cheapest current option in every category we track.',
      categories: present.filter((c) => c !== 'OTHER'),
      itemsQuery: '',
      cheapestOnly: true,
    },
  ];
  if (providers.includes('rentomojo') && providers.includes('guarented')) {
    pages.push({
      slug: 'rentomojo-vs-guarented',
      title: `RentoMojo vs Guarented — ${cityLabel} rental prices compared`,
      description: `RentoMojo and Guarented prices side by side for the products both rent in ${cityLabel}: monthly rent, deposit and estimated total.`,
      intro: 'Only products both providers currently price, so every row is a real head-to-head.',
      categories: present.filter((c) => c !== 'OTHER'),
      itemsQuery: '',
      requireProviders: ['rentomojo', 'guarented'],
    });
  }
  return pages;
}

/** Pages for one city, built from the records that city actually has. */
function pagesForCity(city: City): { def: PageDef; records: PriceRecord[] }[] {
  const { catalogue, records: all } = loadBuildData();
  const records = all.filter((r) => r.city === city.id);
  const pricedIds = new Set(records.map((r) => r.canonicalProductId));
  const present = [...new Set(catalogue.filter((p) => pricedIds.has(p.id)).map((p) => p.category))];
  const orderedPresent = Object.keys(CATEGORY_LABELS).filter((c) => present.includes(c));
  const providers = [...new Set(records.map((r) => r.provider))];
  return [...categoryPages(orderedPresent, city.label), ...specialPages(orderedPresent, providers, city.label)]
    .filter((def) => def.categories.length > 0)
    .map((def) => ({ def, records }));
}

export function generateStaticParams(): { city: string; slug: string }[] {
  const { cities } = loadBuildData();
  return cities.flatMap((city) => pagesForCity(city).map(({ def }) => ({ city: city.id, slug: def.slug })));
}

export const dynamicParams = false;

export async function generateMetadata({
  params,
}: {
  params: Promise<{ city: string; slug: string }>;
}): Promise<Metadata> {
  const { city: cityId, slug } = await params;
  const city = loadBuildData().cities.find((c) => c.id === cityId);
  if (!city) return {};
  const page = pagesForCity(city).find((p) => p.def.slug === slug)?.def;
  return page ? { title: `${page.title} · RentRadar`, description: page.description } : {};
}

interface RowView {
  category: string;
  productName: string;
  attrs: string;
  cells: { provider: string; monthlyPaise: number; totalPaise: number; depositPaise: number; url: string; checked: string }[];
}

function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export default async function LandingPage({ params }: { params: Promise<{ city: string; slug: string }> }) {
  const { city: cityId, slug } = await params;
  const { catalogue, cities } = loadBuildData();
  const city = cities.find((c) => c.id === cityId)!;
  const { def, records } = pagesForCity(city).find((p) => p.def.slug === slug)!;

  const rows: RowView[] = [];
  for (const category of def.categories) {
    const products = catalogue.filter((p) => p.category === category);
    const categoryRows: RowView[] = [];
    for (const product of products) {
      // Cheapest in-stock record per provider at the landing tenure: min only.
      const byProvider = new Map<string, PriceRecord>();
      for (const record of records) {
        if (record.canonicalProductId !== product.id) continue;
        if (record.tenureMonths !== TENURE || record.availability !== 'IN_STOCK') continue;
        const current = byProvider.get(record.provider);
        if (!current || record.estimatedTotalPaise < current.estimatedTotalPaise) byProvider.set(record.provider, record);
      }
      if (def.requireProviders && !def.requireProviders.every((p) => byProvider.has(p))) continue;
      if (byProvider.size === 0) continue;
      categoryRows.push({
        category,
        productName: product.name,
        attrs: Object.entries(product.attributes)
          .map(([k, v]) => `${k.replace(/_/g, ' ')}: ${v.replace(/_/g, ' ')}`)
          .join(' · '),
        cells: [...byProvider.entries()]
          .sort((a, b) => a[1].monthlyPaise - b[1].monthlyPaise)
          .map(([provider, r]) => ({
            provider,
            monthlyPaise: r.monthlyPaise,
            totalPaise: r.estimatedTotalPaise,
            depositPaise: r.depositPaise,
            url: r.providerUrl,
            checked: r.scrapedAt,
          })),
      });
    }
    if (def.cheapestOnly) {
      let cheapest: RowView | null = null;
      for (const row of categoryRows) {
        if (!cheapest || row.cells[0].monthlyPaise < cheapest.cells[0].monthlyPaise) cheapest = row;
      }
      if (cheapest) rows.push(cheapest);
    } else {
      rows.push(...categoryRows);
    }
  }

  const compareHref = def.itemsQuery ? `/?items=${def.itemsQuery}` : '/';

  return (
    <main className="landing">
      <p className="landing-home">
        <a href="/">← RentRadar</a>
      </p>
      <h1>{def.title}</h1>
      <p className="landing-intro">{def.intro}</p>
      <p className="landing-cta">
        <a href={compareHref}>Compare interactively — pick your tenure and basket →</a>
      </p>

      {rows.length === 0 ? (
        <p className="empty">No priced products at the moment — the pipeline may be between runs.</p>
      ) : (
        <table className="compare landing-table">
          <thead>
            <tr>
              <th scope="col">Product</th>
              <th scope="col">Provider</th>
              <th scope="col">Monthly ({TENURE}-month plan)</th>
              <th scope="col">Est. total</th>
              <th scope="col">Deposit</th>
              <th scope="col">Checked</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) =>
              row.cells.map((cell, i) => (
                <tr key={`${row.productName}|${cell.provider}`}>
                  {i === 0 && (
                    <th scope="row" rowSpan={row.cells.length}>
                      <div className="product-name">{row.productName}</div>
                      <div className="product-attrs">{row.attrs}</div>
                    </th>
                  )}
                  <td>{providerLabel(cell.provider)}</td>
                  <td>
                    <a href={cell.url} rel="noopener noreferrer" target="_blank">
                      {formatPaise(cell.monthlyPaise)}
                    </a>
                    /mo
                  </td>
                  <td>{formatPaise(cell.totalPaise)}</td>
                  <td>{formatPaise(cell.depositPaise)}</td>
                  <td>{shortDate(cell.checked)}</td>
                </tr>
              )),
            )}
          </tbody>
        </table>
      )}

      <p className="landing-note">
        Prices belong to their providers and are re-checked twice a day; the checked date above is when each price
        was last read. Always confirm on the provider&rsquo;s linked page. <a href="/status/">Pipeline status</a>.
      </p>
    </main>
  );
}
