package in.rentradar.pipeline.provider.manual;

import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.common.model.TenurePrice;
import in.rentradar.pipeline.provider.Provider;
import in.rentradar.pipeline.provider.ProviderCapabilities;
import in.rentradar.pipeline.provider.ProviderRefreshResult;
import in.rentradar.pipeline.provider.RentalProvider;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A hand-maintained price sheet for providers whose robots.txt forbids
 * crawling their catalogue (Cityfurnish, Furlenco, Payrentz — PRD section 14).
 * Same freshness metadata as scraped records: the sheet's mandatory
 * {@code updatedAt} becomes every record's scrapedAt, so a neglected sheet
 * ages visibly and drops out of rankings past 72h like any other stale price.
 *
 * Prices are entered in whole rupees by a human reading the provider's site;
 * this adapter never fetches anything.
 */
public class ManualSheetProvider implements RentalProvider {

    /** Bengaluru offset: a sheet updated "2026-08-26" is fresh as of that local midnight. */
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private final Path sheetFile;
    private final String providerId;
    private final String displayName;
    private final String homepage;

    public ManualSheetProvider(Path sheetFile) {
        this.sheetFile = sheetFile;
        Map<String, Object> root = loadYaml(sheetFile);
        this.providerId = requireString(root, "provider");
        this.displayName = requireString(root, "displayName");
        this.homepage = requireString(root, "homepage");
    }

    @Override
    public Provider getProvider() {
        return new Provider(providerId, displayName, homepage, Provider.IntegrationType.MANUAL);
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return new ProviderCapabilities(false, true, true, true);
    }

    @Override
    public List<RentalProduct> fetchProducts(String city, RentalCategory category) {
        return parseListings().stream().filter(p -> p.category() == category).toList();
    }

    @Override
    public ProviderRefreshResult refresh(String city) {
        long started = System.currentTimeMillis();
        try {
            List<RentalProduct> products = parseListings();
            return new ProviderRefreshResult(providerId, true, products, List.of(), List.of(),
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            return ProviderRefreshResult.failure(providerId, "manual sheet invalid: " + e.getMessage(),
                    System.currentTimeMillis() - started);
        }
    }

    private List<RentalProduct> parseListings() {
        Map<String, Object> root = loadYaml(sheetFile);
        Instant scrapedAt = parseUpdatedAt(root);

        Object listingsNode = root.get("listings");
        if (!(listingsNode instanceof List<?> listings)) {
            throw new IllegalArgumentException("no listings array");
        }
        List<RentalProduct> products = new ArrayList<>();
        for (Object entry : listings) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("listing entries must be maps");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> listing = (Map<String, Object>) raw;

            RentalCategory category = RentalCategory.valueOf(requireString(listing, "category"));
            Availability availability = listing.containsKey("availability")
                    ? Availability.valueOf(requireString(listing, "availability"))
                    : Availability.IN_STOCK;

            List<TenurePrice> tenures = new ArrayList<>();
            Object tenuresNode = listing.get("tenures");
            if (!(tenuresNode instanceof List<?> tenureList) || tenureList.isEmpty()) {
                throw new IllegalArgumentException(requireString(listing, "externalId") + ": no tenures");
            }
            for (Object t : tenureList) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tenure = (Map<String, Object>) t;
                tenures.add(new TenurePrice(
                        requireWholeNumber(tenure, "months").intValue(),
                        rupees(tenure, "monthlyRupees"),
                        rupees(tenure, "depositRupees"),
                        0));
            }

            products.add(new RentalProduct(
                    providerId,
                    requireString(listing, "externalId"),
                    requireString(listing, "name"),
                    requireString(listing, "url"),
                    listing.get("imageUrl") instanceof String s ? s : null,
                    category,
                    availability,
                    optionalRupees(listing, "deliveryRupees"),
                    optionalRupees(listing, "installationRupees"),
                    tenures,
                    Map.of("source", "manual-sheet"),
                    scrapedAt));
        }
        return products;
    }

    /**
     * The sheet's own date is its freshness (PRD section 17). YAML parses an
     * unquoted {@code 2026-08-20} into a Date; a quoted one stays a string.
     * Either way the sheet means that calendar day in Bengaluru.
     */
    private static Instant parseUpdatedAt(Map<String, Object> root) {
        Object value = root.get("updatedAt");
        if (value == null) {
            throw new IllegalArgumentException("missing required field: updatedAt");
        }
        LocalDate date;
        if (value instanceof java.util.Date parsed) {
            date = parsed.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
        } else {
            try {
                date = LocalDate.parse(value.toString().trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("updatedAt must be a date like 2026-08-26, got: " + value);
            }
        }
        return date.atStartOfDay().toInstant(IST);
    }

    // ---- YAML plumbing ----

    private static Map<String, Object> loadYaml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("sheet root must be a map");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value.toString();
    }

    private static Number requireWholeNumber(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            // Money stays integer (PRD section 17); 599.50 in a sheet is a typo, not a price.
            throw new IllegalArgumentException(key + " must be a whole number, got: " + value);
        }
        return (Number) value;
    }

    private static long rupees(Map<String, Object> map, String key) {
        return requireWholeNumber(map, key).longValue() * 100;
    }

    private static long optionalRupees(Map<String, Object> map, String key) {
        return map.containsKey(key) ? rupees(map, key) : 0;
    }
}
