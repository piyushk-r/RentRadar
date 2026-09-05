package in.rentradar.pipeline.provider.furlenco;

import in.rentradar.pipeline.scraper.BrowserRenderer;
import in.rentradar.pipeline.scraper.PoliteHttpClient;

/**
 * Dev harness: renders several candidate category paths and reports how many
 * product cards each yields, so a dead sitemap entry can be told apart from a
 * slow one without guessing a path at a time.
 */
public final class FurlencoPathProbe {

    private static final String[] CANDIDATES = {
            "/bengaluru/bedroom-furniture-on-rent",
            "/bengaluru/living-room-furniture-on-rent",
            "/bengaluru/living-room-sofa-sets-furniture-on-rent",
            "/bengaluru/appliances-on-rent",
            "/bengaluru/entertainment-units-on-rent",
            "/bengaluru/bedroom-storage-furniture-on-rent",
    };

    public static void main(String[] args) throws Exception {
        String ua = "RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; personal, non-commercial price comparison; contact: bookzee.in@gmail.com)";
        PoliteHttpClient client = new PoliteHttpClient(ua, 2500);

        try (BrowserRenderer renderer = new BrowserRenderer(ua, 2500)) {
            for (String path : CANDIDATES) {
                String url = "https://www.furlenco.com" + path;
                client.requireAllowed(url);
                String summary = renderer.withPage(url, page -> {
                    FurlencoCityGate.answer(page, "bangalore");
                    try {
                        page.waitForSelector("a[href*='/rent/products/']",
                                new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(20000));
                    } catch (RuntimeException ignored) {
                        // reported as zero below
                    }
                    Object count = page.evaluate(
                            "() => document.querySelectorAll(\"a[href*='/rent/products/']\").length");
                    Object title = page.evaluate("() => document.title");
                    return count + " cards | " + title;
                });
                System.out.println(String.format("%-52s %s", path, summary));
            }
        }
    }
}
