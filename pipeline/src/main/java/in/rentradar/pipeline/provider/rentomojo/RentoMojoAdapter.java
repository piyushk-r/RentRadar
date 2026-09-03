package in.rentradar.pipeline.provider.rentomojo;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.provider.Provider;
import in.rentradar.pipeline.provider.ProviderCapabilities;
import in.rentradar.pipeline.provider.ProviderRefreshResult;
import in.rentradar.pipeline.provider.RentalProvider;
import in.rentradar.pipeline.scraper.PoliteHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * RentoMojo: server-rendered HTML, crawl permitted for the paths we read
 * (robots.txt re-checked at runtime by the HTTP client on every host).
 * Category listing gives the product set; each product page gives the
 * published tenure pricing and deposit.
 */
public class RentoMojoAdapter implements RentalProvider {

    private static final Logger log = LoggerFactory.getLogger(RentoMojoAdapter.class);

    public static final String PROVIDER_ID = "rentomojo";
    private static final String BASE_URL = "https://www.rentomojo.com";

    /** Category listing paths per city, under the city prefix (from the published sitemap). */
    private static final Map<RentalCategory, String> CATEGORY_PATHS = new EnumMap<>(Map.ofEntries(
            Map.entry(RentalCategory.REFRIGERATOR, "appliances/refrigerators-on-rent"),
            Map.entry(RentalCategory.WASHING_MACHINE, "appliances/washing-machines-on-rent"),
            Map.entry(RentalCategory.BED, "furniture/beds-on-rent"),
            Map.entry(RentalCategory.MATTRESS, "furniture/mattresses-on-rent"),
            Map.entry(RentalCategory.SOFA, "furniture/sofas-on-rent"),
            Map.entry(RentalCategory.WARDROBE, "furniture/wardrobe-and-organizer-on-rent"),
            Map.entry(RentalCategory.STUDY_TABLE, "furniture/study-tables-on-rent"),
            Map.entry(RentalCategory.OFFICE_CHAIR, "furniture/chairs-and-stools-on-rent"),
            Map.entry(RentalCategory.DINING_TABLE, "furniture/dining-tables-on-rent"),
            Map.entry(RentalCategory.TV, "appliances/smart-led-tvs-on-rent"),
            Map.entry(RentalCategory.AIR_CONDITIONER, "appliances/air-conditioners-on-rent"),
            Map.entry(RentalCategory.MICROWAVE, "appliances/microwaves-and-induction-on-rent"),
            Map.entry(RentalCategory.AIR_COOLER, "appliances/air-coolers-on-rent"),
            Map.entry(RentalCategory.WATER_PURIFIER, "appliances/water-purifiers-on-rent")
    ));

    private final PoliteHttpClient client;
    private final List<RentalCategory> categories;

    public RentoMojoAdapter(PoliteHttpClient client, List<RentalCategory> categories) {
        this.client = client;
        this.categories = List.copyOf(categories);
    }

    @Override
    public Provider getProvider() {
        return new Provider(PROVIDER_ID, "RentoMojo", BASE_URL, Provider.IntegrationType.SCRAPE_HTML);
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return new ProviderCapabilities(false, true, true, false);
    }

    @Override
    public List<RentalProduct> fetchProducts(String city, RentalCategory category) throws Exception {
        return fetchCategory(city, category, new ArrayList<>());
    }

    /**
     * One category: the listing page decides whether the category can be read
     * at all, so a failure there propagates. A single product page failing is
     * a warning — across ~300 pages a transient timeout is routine, and losing
     * the provider's entire refresh over one of them would leave every price
     * stale. Mass breakage is still caught, by the coverage guard (FR-6.4).
     */
    private List<RentalProduct> fetchCategory(String city, RentalCategory category, List<String> warnings)
            throws Exception {
        String categoryPath = CATEGORY_PATHS.get(category);
        if (categoryPath == null) {
            return List.of();
        }
        String listingUrl = BASE_URL + "/" + city + "/" + categoryPath;
        String listingHtml = client.fetch(listingUrl);
        List<RentoMojoListingParser.ListingCard> cards = RentoMojoListingParser.parse(listingHtml);
        log.info("rentomojo: {} cards on {}", cards.size(), listingUrl);

        List<RentalProduct> products = new ArrayList<>();
        for (RentoMojoListingParser.ListingCard card : cards) {
            String productUrl = BASE_URL + card.path();
            try {
                RentoMojoProductParser.ProductDetails details =
                        RentoMojoProductParser.parse(client.fetch(productUrl));
                products.add(new RentalProduct(
                        PROVIDER_ID,
                        card.externalId(),
                        details.name(),
                        productUrl,
                        details.imageUrl(),
                        category,
                        details.availability(),
                        0, // delivery: RentoMojo advertises free delivery; no fee is published on the page
                        details.installationFeePaise(),
                        details.tenurePrices(),
                        details.rawAttributes(),
                        Instant.now()));
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                warnings.add(productUrl + ": " + shortMessage(e));
                log.warn("rentomojo: skipping {} — {}", productUrl, shortMessage(e));
            }
        }
        return products;
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @Override
    public ProviderRefreshResult refresh(String city) {
        long started = System.currentTimeMillis();
        List<RentalProduct> all = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (RentalCategory category : categories) {
            try {
                all.addAll(fetchCategory(city, category, warnings));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(category + ": interrupted");
                break;
            } catch (Exception e) {
                // One category failing degrades that category, not the provider's
                // surviving data — the orchestrator keeps previous records (FR-5.4).
                errors.add(category + ": " + e.getMessage());
            }
        }
        long duration = System.currentTimeMillis() - started;
        boolean success = errors.isEmpty();
        return new ProviderRefreshResult(PROVIDER_ID, success, all, warnings, errors, duration);
    }
}
