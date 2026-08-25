package in.rentradar.pipeline.pricing;

import com.fasterxml.jackson.annotation.JsonProperty;
import in.rentradar.pipeline.common.model.Availability;

import java.time.Instant;

/**
 * One fully resolved price for one (listing, tenure) — the shape stored in
 * data/prices.json. Every cost figure the site will ever show is on this
 * record; the browser only picks minima and adds (PRD section 16).
 *
 * The formula (PRD section 10):
 *   estimatedTotal = monthly * months + delivery + installation + other - discount
 *   cashUpfront    = deposit + delivery + installation + first month's rent
 * Deposit is refundable and deliberately outside estimatedTotal.
 */
public record PriceRecord(
        @JsonProperty("provider") String provider,
        @JsonProperty("externalId") String externalId,
        @JsonProperty("canonicalProductId") String canonicalProductId,
        @JsonProperty("providerName") String providerName,
        @JsonProperty("city") String city,
        @JsonProperty("tenureMonths") int tenureMonths,
        @JsonProperty("monthlyPaise") long monthlyPaise,
        @JsonProperty("monthlyTaxPaise") long monthlyTaxPaise,
        @JsonProperty("depositPaise") long depositPaise,
        @JsonProperty("deliveryFeePaise") long deliveryFeePaise,
        @JsonProperty("installationFeePaise") long installationFeePaise,
        @JsonProperty("otherFeesPaise") long otherFeesPaise,
        @JsonProperty("discountPaise") long discountPaise,
        @JsonProperty("estimatedTotalPaise") long estimatedTotalPaise,
        @JsonProperty("cashUpfrontPaise") long cashUpfrontPaise,
        @JsonProperty("availability") Availability availability,
        @JsonProperty("imageUrl") String imageUrl,
        @JsonProperty("providerUrl") String providerUrl,
        @JsonProperty("scrapedAt") Instant scrapedAt) {

    public PriceRecord {
        // Provenance is a NOT NULL that survived the database (PRD section 17).
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("price record without provider");
        }
        if (providerUrl == null || providerUrl.isBlank()) {
            throw new IllegalArgumentException("price record without providerUrl");
        }
        if (scrapedAt == null) {
            throw new IllegalArgumentException("price record without scrapedAt");
        }
    }
}
