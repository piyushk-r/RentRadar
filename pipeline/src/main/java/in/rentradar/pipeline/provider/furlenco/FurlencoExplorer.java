package in.rentradar.pipeline.provider.furlenco;

import in.rentradar.pipeline.scraper.BrowserRenderer;
import in.rentradar.pipeline.scraper.PoliteHttpClient;

/**
 * Development harness: renders one permitted Furlenco page, answers the
 * delivery-location gate the way a visitor would, and reports what a parser
 * would have to work with. Run with:
 *   mvn -f pipeline/pom.xml compile exec:java \
 *     -Dexec.mainClass=in.rentradar.pipeline.provider.furlenco.FurlencoExplorer \
 *     -Dexec.args="&lt;url&gt;"
 * Not part of the pipeline run.
 */
public final class FurlencoExplorer {

    private FurlencoExplorer() {
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://www.furlenco.com/bengaluru/refrigerators-on-rent";
        String userAgent = "RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; personal, non-commercial price comparison; contact: bookzee.in@gmail.com)";

        PoliteHttpClient client = new PoliteHttpClient(userAgent, 2500);
        client.requireAllowed(url);
        System.out.println("robots: allowed " + url);

        try (BrowserRenderer renderer = new BrowserRenderer(userAgent, 2500)) {
            renderer.withPage(url, page -> {
                page.waitForTimeout(4000);
                System.out.println("city gate: " + FurlencoCityGate.answer(page, "bangalore"));
                page.waitForTimeout(6000);
                System.out.println("title: " + page.title());
                page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
                        .setPath(java.nio.file.Path.of(System.getProperty("shot", "furlenco.png")))
                        .setFullPage(true));

                System.out.println("\n-- leaves mentioning price, tenure or deposit --");
                Object hits = page.evaluate(LEAVES_JS);
                if (hits instanceof java.util.List<?> list) {
                    list.forEach(h -> System.out.println("  " + collapse(String.valueOf(h))));
                }

                System.out.println("\n-- product-card anchors --");
                Object cards = page.evaluate(CARDS_JS);
                if (cards instanceof java.util.List<?> list) {
                    list.forEach(c -> System.out.println("  " + collapse(String.valueOf(c))));
                }
                return null;
            });
        }
    }

    /**
     * Regex-free on purpose: a Java text block interprets backslash escapes,
     * so a JS regex literal inside one is a portability trap. Whitespace is
     * normalized on the Java side by {@link #collapse}.
     */
    private static final String LEAVES_JS = """
            () => {
              const words = ['month', 'deposit', 'refundable', 'tenure', '/mo'];
              const out = [];
              for (const el of document.querySelectorAll('body *')) {
                if (el.children.length) continue;
                const t = (el.textContent || '').trim();
                if (!t || t.length > 90) continue;
                const low = t.toLowerCase();
                if (!(t.includes('₹') || words.some((w) => low.includes(w)))) continue;
                const cls = (el.className && typeof el.className === 'string')
                  ? '.' + el.className.trim().split(' ').slice(0, 2).join('.') : '';
                out.push(el.tagName.toLowerCase() + cls + '  ::  ' + t);
                if (out.length >= 45) break;
              }
              return out;
            }
            """;

    private static final String CARDS_JS = """
            () => {
              const out = [];
              for (const el of document.querySelectorAll("h1,h2,h3,h4,div,p,span,li,button")) {
                const t = (el.textContent || "").trim();
                if (t.length > 4 && t.length < 220) {
                  const low = t.toLowerCase();
                  if (low.includes("minimum") || low.includes("tenure") || low.includes("lock-in") || low.includes("lock in") || low.includes("months")) out.push(t);
                }
                if (out.length > 25) break;
              }
              return [...new Set(out)];
            }
            """;

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
