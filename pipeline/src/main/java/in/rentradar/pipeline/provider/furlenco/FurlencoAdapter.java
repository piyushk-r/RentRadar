package in.rentradar.pipeline.provider.furlenco;

import com.microsoft.playwright.Page;
import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.common.model.TenurePrice;
import in.rentradar.pipeline.provider.Provider;
import in.rentradar.pipeline.provider.ProviderCapabilities;
import in.rentradar.pipeline.provider.ProviderRefreshResult;
import in.rentradar.pipeline.provider.RentalProvider;
import in.rentradar.pipeline.scraper.BrowserRenderer;
import in.rentradar.pipeline.scraper.PoliteHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Furlenco: crawl permitted for the paths this reads. Their robots.txt closes
 * 21 legacy collection paths, none of which appear in the sitemap they
 * advertise; the per-city category pages used here are all permitted (checked
 * 4 Sep 2026, see docs/compliance/furlenco.md).
 *
 * Prices are client-rendered and product content sits behind a delivery
 * location gate, so pages are rendered and the gate is answered exactly as a
 * visitor answers it — by entering a pincode into the page's own field.
 *
 * Pricing shape, which differs from the other providers: Furlenco publishes a
 * single monthly rate per product with no tenure selector on the page. That
 * rate is recorded as published, for each display tenure, and the raw
 * attributes record that it is not tenure-differentiated. Nothing is derived.
 */
public class FurlencoAdapter implements RentalProvider {

    private static final Logger log = LoggerFactory.getLogger(FurlencoAdapter.class);

    public static final String PROVIDER_ID = "furlenco";
    private static final String BASE_URL = "https://www.furlenco.com";

    /** Category listing paths under the city prefix, from the published sitemap. */
    private static final Map<RentalCategory, String> CATEGORY_PATHS = new EnumMap<>(Map.ofEntries(
            Map.entry(RentalCategory.BED, "/bedroom-beds-furniture-on-rent"),
            Map.entry(RentalCategory.WARDROBE, "/wardrobes-on-rent"),
            Map.entry(RentalCategory.REFRIGERATOR, "/refrigerators-on-rent"),
            Map.entry(RentalCategory.WASHING_MACHINE, "/washing-machine-on-rent"),
            Map.entry(RentalCategory.SOFA, "/living-room-sofa-furniture-on-rent"),
            Map.entry(RentalCategory.DINING_TABLE, "/dining-room-furniture-on-rent"),
            Map.entry(RentalCategory.STUDY_TABLE, "/study-table-on-rent"),
            Map.entry(RentalCategory.OFFICE_CHAIR, "/office-chairs-on-rent"),
            Map.entry(RentalCategory.TV, "/television-on-rent"),
            Map.entry(RentalCategory.MICROWAVE, "/microwave-on-rent")
    ));

    /** /rent/products/<slug>-<id>-rent — the trailing number is the listing id. */
    private static final Pattern PRODUCT_ID = Pattern.compile("/rent/products/.*?-(\\d+)-rent/?$");
    private static final Pattern RUPEES = Pattern.compile("₹\\s*([0-9][0-9,]*)");

    /** Card badges Furlenco prints above the name; not part of the name. */
    private static final List<String> CARD_BADGES = List.of(
            "Price-Drop", "Best-Seller", "Limited Stocks", "New Arrivals", "Trending");

    /** Bundles are not comparable single products (same rule as Guarented). */
    private static final List<String> BUNDLE_WORDS =
            List.of("combo", "trio", "set of", " & ", ", ", " + ");

    /** Display tenures a flat monthly rate is recorded against. */
    private static final int[] DISPLAY_TENURES = {3, 6, 9, 12, 18, 24};

    private final PoliteHttpClient client;
    private final List<RentalCategory> categories;
    private final String userAgent;
    private final long requestDelayMillis;

    public FurlencoAdapter(PoliteHttpClient client, List<RentalCategory> categories,
                           String userAgent, long requestDelayMillis) {
        this.client = client;
        this.categories = List.copyOf(categories);
        this.userAgent = userAgent;
        this.requestDelayMillis = requestDelayMillis;
    }

    @Override
    public Provider getProvider() {
        return new Provider(PROVIDER_ID, "Furlenco", BASE_URL, Provider.IntegrationType.SCRAPE_BROWSER);
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        // supportsTenurePricing is false: one published rate, not a plan per tenure.
        return new ProviderCapabilities(false, true, false, false);
    }

    @Override
    public List<RentalProduct> fetchProducts(String city, RentalCategory category) throws Exception {
        return fetchCategory(city, category, new ArrayList<>());
    }

    private List<RentalProduct> fetchCategory(String city, RentalCategory category, List<String> warnings)
            throws Exception {
        String path = CATEGORY_PATHS.get(category);
        if (path == null) {
            return List.of();
        }
        String listingUrl = BASE_URL + "/" + cityPath(city) + path;
        client.requireAllowed(listingUrl);

        try (BrowserRenderer renderer = new BrowserRenderer(userAgent, requestDelayMillis)) {
            List<Card> cards = renderer.withPage(listingUrl, page -> {
                FurlencoCityGate.answer(page, city);
                // Wait for the cards themselves rather than a fixed pause: the
                // busier category pages (beds, sofas) paint well after the
                // quick ones, and a fixed wait silently returned zero for them.
                try {
                    page.waitForSelector("a[href*='/rent/products/']",
                            new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(25000));
                } catch (RuntimeException e) {
                    // Genuinely empty, or slower than we are willing to wait —
                    // either way the zero-product guard decides what it means.
                    log.warn("furlenco: no product cards appeared on {}", listingUrl);
                }
                page.waitForTimeout(1500);
                return readCards(page);
            });
            log.info("furlenco: {} cards on {}", cards.size(), listingUrl);

            List<RentalProduct> products = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Card card : cards) {
                if (!seen.add(card.externalId())) {
                    continue; // the same product can appear in more than one rail
                }
                if (card.monthlyPaise() <= 0) {
                    warnings.add(card.url() + ": no published price on the card");
                    continue;
                }
                products.add(toProduct(card, category));
            }
            return products;
        }
    }

    /** Bengaluru is "bengaluru" in Furlenco's own paths; our city id is "bangalore". */
    private static String cityPath(String city) {
        return "bangalore".equals(city) ? "bengaluru" : city;
    }

    private RentalProduct toProduct(Card card, RentalCategory category) {
        // Same published rate at every display tenure — recorded, not derived.
        List<TenurePrice> prices = new ArrayList<>();
        for (int months : DISPLAY_TENURES) {
            prices.add(new TenurePrice(months, card.monthlyPaise(), card.monthlyPaise(), 0));
        }
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("pricing_model", "flat monthly rate; provider publishes no tenure-differentiated price");
        if (card.originalMonthlyPaise() > 0 && card.originalMonthlyPaise() != card.monthlyPaise()) {
            raw.put("list_price_monthly", "₹" + card.originalMonthlyPaise() / 100);
        }
        return new RentalProduct(
                PROVIDER_ID,
                card.externalId(),
                card.name(),
                card.url(),
                card.imageUrl(),
                category,
                Availability.IN_STOCK,
                0, // delivery advertised free
                0, // no installation fee published
                prices,
                raw,
                Instant.now());
    }

    @Override
    public ProviderRefreshResult refresh(String city) {
        long started = System.currentTimeMillis();
        List<RentalProduct> all = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (RentalCategory category : categories) {
            if (!CATEGORY_PATHS.containsKey(category)) {
                continue;
            }
            try {
                all.addAll(fetchCategory(city, category, warnings));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(category + ": interrupted");
                break;
            } catch (Exception e) {
                // One category failing degrades that category, not the
                // provider's surviving data (FR-5.4).
                errors.add(category + ": " + shortMessage(e));
            }
        }
        boolean success = errors.isEmpty();
        return new ProviderRefreshResult(PROVIDER_ID, success, all, warnings, errors,
                System.currentTimeMillis() - started);
    }

    // ---- card reading ----

    record Card(String externalId, String name, String url, String imageUrl,
                long monthlyPaise, long originalMonthlyPaise) {
    }

    /**
     * Reads the product cards a category page renders. Deliberately regex-free
     * inside the browser: a Java text block interprets backslash escapes, so a
     * JS regex literal here is a portability trap.
     */
    private static List<Card> readCards(Page page) {
        Object raw = page.evaluate("""
                () => [...document.querySelectorAll('a[href*="/rent/products/"]')].map((a) => {
                  const img = a.querySelector('img');
                  return {
                    href: a.getAttribute('href') || '',
                    text: (a.textContent || '').trim(),
                    img: img ? (img.getAttribute('src') || '') : ''
                  };
                })
                """);
        List<Card> cards = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return cards;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String href = String.valueOf(map.get("href"));
            Matcher id = PRODUCT_ID.matcher(href);
            if (!id.find()) {
                continue;
            }
            String text = String.valueOf(map.get("text")).replaceAll("\\s+", " ").trim();
            // "Name ₹1,349/mo ₹1,551/mo 13% OFF" — first amount is what you pay.
            Matcher amounts = RUPEES.matcher(text);
            long current = 0;
            long original = 0;
            if (amounts.find()) {
                current = Long.parseLong(amounts.group(1).replace(",", "")) * 100;
                if (amounts.find()) {
                    original = Long.parseLong(amounts.group(1).replace(",", "")) * 100;
                }
            }
            String name = text.split("₹")[0].trim();
            for (String badge : CARD_BADGES) {
                if (name.startsWith(badge)) {
                    name = name.substring(badge.length()).trim();
                }
            }
            if (name.isEmpty()) {
                continue;
            }
            // A bundle priced as one line is not a comparable single product.
            if (isBundle(name, href)) {
                continue;
            }
            String image = String.valueOf(map.get("img"));
            cards.add(new Card(id.group(1), name,
                    href.startsWith("http") ? href : BASE_URL + href,
                    image.isBlank() || "null".equals(image) ? null : image,
                    current, original));
        }
        return cards;
    }

    private static boolean isBundle(String name, String href) {
        String low = name.toLowerCase(java.util.Locale.ROOT);
        for (String word : BUNDLE_WORDS) {
            if (low.contains(word)) {
                return true;
            }
        }
        return href.toLowerCase(java.util.Locale.ROOT).contains("combo");
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String flat = message.replaceAll("\\s+", " ").trim();
        String reason = flat.split("\\s(?:stack=|name=|at [\\w.$]+\\()")[0]
                .replaceAll("^Error \\{ message='?", "")
                .replaceAll("[\\s'\"{,.]+$", "")
                .trim();
        return reason.isEmpty() ? e.getClass().getSimpleName()
                : reason.length() > 160 ? reason.substring(0, 159) + "…" : reason;
    }
}
