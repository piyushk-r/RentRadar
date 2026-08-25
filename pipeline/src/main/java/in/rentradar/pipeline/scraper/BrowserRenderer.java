package in.rentradar.pipeline.scraper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.function.Function;

/**
 * Headless-browser rendering for providers whose prices are painted by
 * JavaScript (PRD section 14, extraction route 5). This renders the permitted
 * page exactly as a browser would — it never calls a provider's internal JSON
 * endpoints directly. The caller is responsible for the robots gate
 * ({@link PoliteHttpClient#requireAllowed}) before every URL.
 *
 * Identification stays honest: pages are visited with our bot User-Agent, not
 * a spoofed browser string.
 */
public class BrowserRenderer implements AutoCloseable {

    private final Playwright playwright;
    private final Browser browser;
    private final String userAgent;
    private final long requestDelayMillis;
    private long lastNavigationAt = 0;

    public BrowserRenderer(String userAgent, long requestDelayMillis) {
        this.userAgent = userAgent;
        this.requestDelayMillis = requestDelayMillis;
        // Chromium is installed explicitly (locally once, in CI as a cached
        // step); skip the automatic download of every other engine.
        this.playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    /** Navigate to a URL (spaced politely from the previous navigation) and run the interaction. */
    public synchronized <T> T withPage(String url, Function<Page, T> interaction) throws InterruptedException {
        long sinceLast = System.currentTimeMillis() - lastNavigationAt;
        if (sinceLast < requestDelayMillis) {
            Thread.sleep(requestDelayMillis - sinceLast);
        }
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions().setUserAgent(userAgent))) {
            Page page = context.newPage();
            page.setDefaultTimeout(30_000);
            page.navigate(url);
            lastNavigationAt = System.currentTimeMillis();
            return interaction.apply(page);
        }
    }

    @Override
    public void close() {
        try {
            browser.close();
        } finally {
            playwright.close();
        }
    }
}
