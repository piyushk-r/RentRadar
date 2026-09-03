package in.rentradar.pipeline.provider.furlenco;

import com.microsoft.playwright.Locator;
import in.rentradar.pipeline.scraper.BrowserRenderer;
import in.rentradar.pipeline.scraper.PoliteHttpClient;

/**
 * Development harness: renders one permitted Furlenco page and reports what a
 * parser would have to work with — anchors, price-looking text, and the DOM
 * around the first price. Run with:
 *   mvn -f pipeline/pom.xml compile exec:java \
 *     -Dexec.mainClass=in.rentradar.pipeline.provider.furlenco.FurlencoExplorer \
 *     -Dexec.args="https://www.furlenco.com/bengaluru/refrigerators-on-rent"
 * Not part of the pipeline run.
 */
public final class FurlencoExplorer {

    private FurlencoExplorer() {
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://www.furlenco.com/bengaluru/refrigerators-on-rent";
        String userAgent = "RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; personal, non-commercial price comparison; contact: bookzee.in@gmail.com)";

        // The robots gate applies to recon exactly as it does to a run.
        PoliteHttpClient client = new PoliteHttpClient(userAgent, 2500);
        client.requireAllowed(url);
        System.out.println("robots: allowed " + url);

        try (BrowserRenderer renderer = new BrowserRenderer(userAgent, 2500)) {
            renderer.withPage(url, page -> {
                page.waitForTimeout(6000);
                System.out.println("title: " + page.title());

                System.out.println("\n-- anchors by href shape (top 15) --");
                Locator anchors = page.locator("a[href]");
                java.util.Map<String, Integer> shapes = new java.util.TreeMap<>();
                java.util.Map<String, String> examples = new java.util.HashMap<>();
                int total = anchors.count();
                for (int i = 0; i < total; i++) {
                    String href = anchors.nth(i).getAttribute("href");
                    if (href == null || href.startsWith("http") && !href.contains("furlenco.com")) {
                        continue;
                    }
                    String shape = href.replaceAll("[0-9]+", "N").replaceAll("/[a-z0-9-]{12,}", "/<slug>");
                    shapes.merge(shape, 1, Integer::sum);
                    examples.putIfAbsent(shape, href);
                }
                shapes.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(15)
                        .forEach(e -> System.out.println("  " + e.getValue() + "x  " + e.getKey()
                                + "   e.g. " + examples.get(e.getKey())));

                System.out.println("\n-- leaf elements mentioning price, tenure or deposit --");
                Object hits = page.evaluate("""
                        () => {
                          const out = [];
                          const re = /(₹|Rs\\.?\\s?\\d|\\/mo|month|deposit|tenure|refundable)/i;
                          for (const el of document.querySelectorAll('body *')) {
                            if (el.children.length) continue;            // leaves only
                            const t = (el.textContent || '').trim().replace(/\\s+/g, ' ');
                            if (!t || t.length > 90 || !re.test(t)) continue;
                            out.push(el.tagName.toLowerCase()
                                     + (el.className && typeof el.className === 'string'
                                        ? '.' + el.className.trim().split(/\\s+/).slice(0,2).join('.') : '')
                                     + '  ::  ' + t);
                            if (out.length >= 40) break;
                          }
                          return out;
                        }
                        """);
                if (hits instanceof java.util.List<?> list) {
                    list.forEach(h -> System.out.println("  " + h));
                }

                System.out.println("\n-- full body text --");
                System.out.println(oneLine(page.locator("body").innerText()));
                return null;
            });
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
