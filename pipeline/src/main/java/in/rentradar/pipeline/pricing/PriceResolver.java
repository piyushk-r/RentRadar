package in.rentradar.pipeline.pricing;

import in.rentradar.pipeline.common.Tenure;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.common.model.TenurePrice;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves every cost figure ahead of time (PRD section 16, "the one rule that
 * makes the split safe"). Pure function: no I/O, no clock, no randomness
 * (NFR-A2) — scrapedAt comes in on the listing, written by the run that
 * produced it.
 *
 * A display tenure the provider does not publish yields no record. Deriving a
 * 9-month price from a 6-month plan would fabricate a number (PRD section 25);
 * absence is the honest output.
 */
public final class PriceResolver {

    private PriceResolver() {
    }

    public static List<PriceRecord> resolve(RentalProduct listing, String canonicalProductId, String city) {
        List<PriceRecord> records = new ArrayList<>();
        for (Tenure tenure : Tenure.all()) {
            TenurePrice published = null;
            for (TenurePrice price : listing.tenurePrices()) {
                if (price.months() == tenure.months()) {
                    published = price;
                    break;
                }
            }
            if (published == null) {
                continue;
            }
            records.add(resolveOne(listing, canonicalProductId, city, tenure, published));
        }
        return records;
    }

    static PriceRecord resolveOne(RentalProduct listing, String canonicalProductId, String city,
                                  Tenure tenure, TenurePrice published) {
        long monthly = published.monthlyPaise();
        long delivery = listing.deliveryFeePaise();
        long installation = listing.installationFeePaise();
        long otherFees = 0;
        long discount = 0;

        long estimatedTotal = monthly * tenure.months() + delivery + installation + otherFees - discount;
        long cashUpfront = published.depositPaise() + delivery + installation + monthly;

        return new PriceRecord(
                listing.providerId(),
                listing.externalId(),
                canonicalProductId,
                listing.name(),
                city,
                tenure.months(),
                monthly,
                published.monthlyTaxPaise(),
                published.depositPaise(),
                delivery,
                installation,
                otherFees,
                discount,
                estimatedTotal,
                cashUpfront,
                listing.availability(),
                listing.imageUrl(),
                listing.url(),
                listing.scrapedAt());
    }
}
