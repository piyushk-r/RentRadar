package in.rentradar.pipeline.common.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One provider listing, raw. Adapters emit these verbatim (FR-6.3): the name is
 * the provider's name, the URL is the provider's page, and nothing here is
 * normalized or compared. Provenance is mandatory — a listing without a URL or
 * scrape timestamp must never be constructed (PRD section 13, non-negotiable).
 */
public record RentalProduct(
        String providerId,
        String externalId,
        String name,
        String url,
        String imageUrl,
        RentalCategory category,
        Availability availability,
        long deliveryFeePaise,
        long installationFeePaise,
        List<TenurePrice> tenurePrices,
        Map<String, String> rawAttributes,
        Instant scrapedAt) {

    public RentalProduct {
        require(providerId, "providerId");
        require(externalId, "externalId");
        require(name, "name");
        require(url, "url");
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (availability == null) {
            availability = Availability.UNKNOWN;
        }
        if (scrapedAt == null) {
            throw new IllegalArgumentException("scrapedAt is required");
        }
        if (deliveryFeePaise < 0 || installationFeePaise < 0) {
            throw new IllegalArgumentException("negative fee on " + url);
        }
        tenurePrices = tenurePrices == null ? List.of() : List.copyOf(tenurePrices);
        rawAttributes = rawAttributes == null ? Map.of() : Map.copyOf(rawAttributes);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required on every listing");
        }
    }
}
