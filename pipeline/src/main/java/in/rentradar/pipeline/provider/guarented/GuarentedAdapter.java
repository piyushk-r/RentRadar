package in.rentradar.pipeline.provider.guarented;

import com.microsoft.playwright.Locator;
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
 * Guarented: crawl permitted (robots disallows only cart/checkout/dashboard),
 * but prices are painted client-side, so permitted pages are rendered in a
 * headless browser (PRD section 14). Discovery comes from the published
 * sitemap; each product page's tenure selector is driven to read every
 * published commitment band.
 */
public class GuarentedAdapter implements RentalProvider {

    private static final Logger log = LoggerFactory.getLogger(GuarentedAdapter.class);

    public static final String PROVIDER_ID = "guarented";
    private static final String BASE_URL = "https://www.guarented.com";

    /** Path under the city prefix — Guarented's URLs are /{city}/rent/… (AC-3.3). */
    private static final Map<RentalCategory, String> CATEGORY_PATH_PREFIXES = new EnumMap<>(Map.ofEntries(
            Map.entry(RentalCategory.BED, "/rent/furniture/beds/"),
            Map.entry(RentalCategory.MATTRESS, "/rent/furniture/mattresses/"),
            Map.entry(RentalCategory.REFRIGERATOR, "/rent/appliances/fridges/"),
            Map.entry(RentalCategory.WASHING_MACHINE, "/rent/appliances/washing-machine/"),
            Map.entry(RentalCategory.SOFA, "/rent/furniture/sofas/"),
            Map.entry(RentalCategory.WARDROBE, "/rent/furniture/wardrobes/"),
            Map.entry(RentalCategory.DINING_TABLE, "/rent/furniture/dining/"),
            // The study directory holds tables and chairs; discovery splits it
            // by slug, so both categories claim the prefix here.
            Map.entry(RentalCategory.STUDY_TABLE, "/rent/furniture/study/"),
            Map.entry(RentalCategory.TV, "/rent/appliances/tv/"),
            Map.entry(RentalCategory.MICROWAVE, "/rent/appliances/microwave/"),
            Map.entry(RentalCategory.AIR_COOLER, "/rent/appliances/cooler/"),
            Map.entry(RentalCategory.WATER_PURIFIER, "/rent/appliances/water-purifier/")
    ));

    private static final String STUDY_PATH = "/rent/furniture/study/";

    private static final Pattern LOC = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");
    private static final Pattern RUPEE_AMOUNT = Pattern.compile("(?:₹|Rs\\.?)\\s*([0-9][0-9,]*)");

    private final PoliteHttpClient client;
    private final List<RentalCategory> categories;
    private final String userAgent;
    private final long requestDelayMillis;

    public GuarentedAdapter(PoliteHttpClient client, List<RentalCategory> categories,
                            String userAgent, long requestDelayMillis) {
        this.client = client;
        this.categories = List.copyOf(categories);
        this.userAgent = userAgent;
        this.requestDelayMillis = requestDelayMillis;
    }

    @Override
    public Provider getProvider() {
        return new Provider(PROVIDER_ID, "Guarented", BASE_URL, Provider.IntegrationType.SCRAPE_BROWSER);
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return new ProviderCapabilities(false, true, true, false);
    }

    @Override
    public List<RentalProduct> fetchProducts(String city, RentalCategory category) throws Exception {
        Map<RentalCategory, Set<String>> discovered = discoverProductUrls(city);
        try (BrowserRenderer renderer = new BrowserRenderer(userAgent, requestDelayMillis)) {
            List<RentalProduct> products = new ArrayList<>();
            for (String url : discovered.getOrDefault(category, Set.of())) {
                client.requireAllowed(url);
                try {
                    products.add(renderProduct(renderer, url, category));
                } catch (ListingUnavailable e) {
                    log.info("guarented: skipping {} — {}", url, e.getMessage());
                }
            }
            return products;
        }
    }

    @Override
    public ProviderRefreshResult refresh(String city) {
        long started = System.currentTimeMillis();
        List<RentalProduct> all = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Map<RentalCategory, Set<String>> discovered;
        try {
            discovered = discoverProductUrls(city);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderRefreshResult.failure(PROVIDER_ID, "interrupted during discovery",
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            return ProviderRefreshResult.failure(PROVIDER_ID, "sitemap discovery failed: " + e.getMessage(),
                    System.currentTimeMillis() - started);
        }

        try (BrowserRenderer renderer = new BrowserRenderer(userAgent, requestDelayMillis)) {
            for (RentalCategory category : categories) {
                for (String url : discovered.getOrDefault(category, Set.of())) {
                    try {
                        client.requireAllowed(url);
                        all.add(renderProduct(renderer, url, category));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errors.add(url + ": interrupted");
                        return new ProviderRefreshResult(PROVIDER_ID, false, all, warnings, errors,
                                System.currentTimeMillis() - started);
                    } catch (ListingUnavailable e) {
                        // Sold out with no published price: normal, not a defect.
                        log.info("guarented: skipping {} — {}", url, e.getMessage());
                    } catch (Exception e) {
                        // One broken page degrades one listing, not the provider —
                        // unless everything breaks, which the zero-product guard catches.
                        warnings.add(url + ": " + shortMessage(e));
                    }
                }
            }
        } catch (Exception e) {
            errors.add("browser session failed: " + shortMessage(e));
        }

        boolean success = errors.isEmpty();
        return new ProviderRefreshResult(PROVIDER_ID, success, all, warnings, errors,
                System.currentTimeMillis() - started);
    }

    // ---- discovery ----

    private Map<RentalCategory, Set<String>> discoverProductUrls(String city) throws Exception {
        String sitemap = client.fetch(BASE_URL + "/sitemap.xml");
        String cityPrefix = "/" + city;
        Map<RentalCategory, Set<String>> byCategory = new EnumMap<>(RentalCategory.class);
        Matcher matcher = LOC.matcher(sitemap);
        while (matcher.find()) {
            String url = matcher.group(1);
            String slug = url.substring(url.lastIndexOf('/') + 1);
            if (slug.contains("combo")) {
                continue; // bundles are not comparable single products
            }
            if (url.contains(cityPrefix + STUDY_PATH)) {
                // One directory, two categories: chairs by slug, tables otherwise.
                RentalCategory category = slug.contains("chair")
                        ? RentalCategory.OFFICE_CHAIR
                        : RentalCategory.STUDY_TABLE;
                if (categories.contains(category)) {
                    byCategory.computeIfAbsent(category, k -> new LinkedHashSet<>()).add(url);
                }
                continue;
            }
            for (Map.Entry<RentalCategory, String> entry : CATEGORY_PATH_PREFIXES.entrySet()) {
                if (categories.contains(entry.getKey()) && url.contains(cityPrefix + entry.getValue())) {
                    byCategory.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).add(url);
                }
            }
        }
        return byCategory;
    }

    // ---- rendering one product ----

    private RentalProduct renderProduct(BrowserRenderer renderer, String url, RentalCategory category)
            throws InterruptedException {
        ParsedProduct parsed = renderer.withPage(url, GuarentedAdapter::driveProductPage);
        String slug = url.substring(url.lastIndexOf('/') + 1);

        List<TenurePrice> tenurePrices = GuarentedTenureBands.toTenurePrices(parsed.bandMonthlyPaise(), parsed.depositPaise());
        if (tenurePrices.isEmpty()) {
            throw new IllegalStateException("no tenure prices parsed on " + url);
        }

        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("tenure_bands", parsed.bandsAsText());
        if (!parsed.specs().isBlank()) {
            raw.put("specs", parsed.specs());
        }

        return new RentalProduct(
                PROVIDER_ID,
                slug,
                parsed.name(),
                url,
                parsed.imageUrl(),
                category,
                parsed.availability(),
                0, // delivery advertised free; conditional floor charges are not modellable
                0,
                tenurePrices,
                raw,
                Instant.now());
    }

    private record ParsedProduct(String name, long depositPaise, Map<Integer, Long> bandMonthlyPaise,
                                 Availability availability, String imageUrl, String specs) {
        String bandsAsText() {
            StringBuilder text = new StringBuilder();
            bandMonthlyPaise.forEach((months, paise) -> {
                if (!text.isEmpty()) {
                    text.append(", ");
                }
                text.append(months).append("+mo=₹").append(paise / 100);
            });
            return text.toString();
        }
    }

    /** A page that renders but has nothing priceable right now (sold out): skip, don't fail the provider. */
    static class ListingUnavailable extends RuntimeException {
        ListingUnavailable(String message) {
            super(message);
        }
    }

    /**
     * Drives one rendered product page: reads name and deposit, then walks the
     * Angular Material tenure selector, clicking every option and reading the
     * price it produces. A sold-out page keeps its price hidden and empty, so
     * that state is detected first rather than waited on.
     */
    private static ParsedProduct driveProductPage(Page page) {
        // Settle: either a visible price with digits, or the sold-out button.
        Locator priceEl = page.locator(".item_rent_p:visible")
                .filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("[0-9]")))
                .first();
        Locator soldOut = page.locator(".sold_out_btn").first();
        Locator settled = priceEl.or(soldOut).first();
        settled.waitFor(new Locator.WaitForOptions().setTimeout(20_000));

        if (soldOut.count() > 0 && soldOut.isVisible()) {
            throw new ListingUnavailable("sold out — no published price to record");
        }

        String name = firstNonBlank(
                textOrNull(page, "h1"),
                textOrNull(page, ".product_price_name"));
        if (name == null) {
            throw new IllegalStateException("no product name found — page structure changed");
        }

        long depositPaise = parseRupeesToPaise(textOrNull(page, ".ref_security"), "deposit");

        String imageUrl = null;
        Locator ogImage = page.locator("meta[property='og:image']");
        if (ogImage.count() > 0) {
            imageUrl = ogImage.first().getAttribute("content");
        }

        String specs = textOrNull(page, "ul.fea_list");
        if (specs == null) {
            specs = "";
        }

        // Walk the tenure bands. The selector is an Angular Material combobox:
        // options exist only while the overlay is open, and it closes on pick.
        Map<Integer, Long> bands = new LinkedHashMap<>();
        Locator select = page.locator("mat-select:visible").first();
        select.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        select.click();
        Locator options = page.locator("mat-option");
        options.first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        int optionCount = options.count();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < optionCount; i++) {
            labels.add(options.nth(i).innerText().trim());
        }
        // Fast path: many option rows carry the price in their own label
        // ("12+ Months ₹233/mo"). If every band's label does, one overlay read
        // is enough; otherwise fall back to clicking through the bands.
        boolean allLabelsPriced = true;
        for (String label : labels) {
            int bandStart = GuarentedTenureBands.parseBandStart(label);
            if (bandStart < 0) {
                continue;
            }
            try {
                bands.put(bandStart, parseRupeesToPaise(label, "band label"));
            } catch (IllegalStateException e) {
                allLabelsPriced = false;
                break;
            }
        }
        page.keyboard().press("Escape");
        page.waitForTimeout(300);

        if (!allLabelsPriced) {
            bands.clear();
            for (String label : labels) {
                int bandStart = GuarentedTenureBands.parseBandStart(label);
                if (bandStart < 0) {
                    continue;
                }
                select.click();
                Locator option = page.locator("mat-option", new Page.LocatorOptions().setHasText(label)).first();
                option.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
                option.click();
                page.waitForTimeout(400); // let Angular repaint the price
                long monthlyPaise = parseRupeesToPaise(priceEl.innerText(), "monthly for " + label);
                bands.put(bandStart, monthlyPaise);
            }
        }
        if (bands.isEmpty()) {
            throw new IllegalStateException("tenure selector yielded no bands — page structure changed");
        }

        return new ParsedProduct(name.trim(), depositPaise, bands, Availability.IN_STOCK, imageUrl, specs);
    }

    private static String textOrNull(Page page, String selector) {
        Locator locator = page.locator(selector);
        if (locator.count() == 0) {
            return null;
        }
        String text = locator.first().innerText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static long parseRupeesToPaise(String text, String what) {
        if (text == null) {
            throw new IllegalStateException("no text to parse for " + what);
        }
        Matcher matcher = RUPEE_AMOUNT.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("no rupee amount in \"" + text.strip() + "\" for " + what);
        }
        return Long.parseLong(matcher.group(1).replace(",", "")) * 100;
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }
}
