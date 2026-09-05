package in.rentradar.pipeline;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Dev harness: scrolls the local site and reports, at each step, whether the
 * header is collapsed and where the search button actually sits on screen.
 * Written to check a reported "the search button fluctuates while scrolling" —
 * a collapsing header that shortens the document can oscillate, and the only
 * honest way to know it is fixed is to watch the geometry.
 *
 *   mvn -f pipeline/pom.xml test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=in.rentradar.pipeline.UiScrollProbe -Dexec.args="http://localhost:4182/"
 */
public final class UiScrollProbe {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:4182/";
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1440, 900));
            page.navigate(url);
            page.waitForTimeout(4000);

            System.out.printf("%6s  %-9s  %-9s  %s%n", "scrollY", "collapsed", "btn.y", "note");
            double previousY = Double.NaN;
            int flips = 0;
            boolean previousCollapsed = false;

            for (int y = 0; y <= 1400; y += 40) {
                page.evaluate("(y) => window.scrollTo(0, y)", y);
                page.waitForTimeout(120);

                boolean collapsed = (Boolean) page.evaluate(
                        "() => !!document.querySelector('.home')?.classList.contains('is-collapsed')");
                Object box = page.evaluate(
                        "() => { const b = document.querySelector('.searchbox button');"
                                + " return b ? Math.round(b.getBoundingClientRect().top) : null; }");
                double actualY = ((Number) page.evaluate("() => window.scrollY")).doubleValue();

                String note = "";
                if (collapsed != previousCollapsed) {
                    flips++;
                    note = "<-- toggled";
                    previousCollapsed = collapsed;
                }
                if (!Double.isNaN(previousY) && Math.abs(actualY - y) > 4) {
                    note += " (scroll clamped to " + (int) actualY + ")";
                }
                previousY = actualY;
                System.out.printf("%6d  %-9s  %-9s  %s%n", y, collapsed, box, note);
            }

            System.out.println();
            System.out.println(flips <= 2
                    ? "OK: " + flips + " toggle(s) across the sweep — no oscillation"
                    : "PROBLEM: " + flips + " toggles — the header is still fluctuating");
            browser.close();
        }
    }
}
