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

    /** Category listing paths per city, under the city prefix. Phase 0: refrigerators. */
    private static final Map<RentalCategory, String> CATEGORY_PATHS = new EnumMap<>(Map.of(
            RentalCategory.REFRIGERATOR, "appliances/refrigerators-on-rent"
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
            String productHtml = client.fetch(productUrl);
            RentoMojoProductParser.ProductDetails details = RentoMojoProductParser.parse(productHtml);
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
        }
        return products;
    }

    @Override
    public ProviderRefreshResult refresh(String city) {
        long started = System.currentTimeMillis();
        List<RentalProduct> all = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (RentalCategory category : categories) {
            try {
                all.addAll(fetchProducts(city, category));
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
