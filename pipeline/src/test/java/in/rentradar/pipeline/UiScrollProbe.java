package in.rentradar.pipeline;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Dev harness for the reported "search button fluctuates while scrolling".
 *
 * Scrolls with real wheel events rather than window.scrollTo — a programmatic
 * jump lands once and settles, which is exactly the case that hid the bug the
 * first time — and samples the button's on-screen position repeatedly after
 * each notch, so a position that moves and comes back is visible as a range
 * rather than a single reading.
 *
 *   mvn -f pipeline/pom.xml test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=in.rentradar.pipeline.UiScrollProbe -Dexec.args="<url> [width] [height]"
 */
public final class UiScrollProbe {

    private static final String BUTTON_TOP =
            "() => { const b = document.querySelector('.searchbox button');"
                    + " return b ? Math.round(b.getBoundingClientRect().top) : -9999; }";

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:4183/";
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 1080;

        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(width, height));
            page.navigate(url);
            page.waitForTimeout(5000);
            page.mouse().move(width / 2.0, height / 2.0);
            // Record what the page does to itself while we scroll.
            page.evaluate("""
                () => {
                  window.__log = [];
                  const push = (what) => window.__log.push(what + ' @y=' + Math.round(window.scrollY)
                        + ' h=' + document.documentElement.scrollHeight);
                  const st = history.scrollRestoration;
                  push('start scrollRestoration=' + st);
                  const origTo = window.scrollTo.bind(window);
                  window.scrollTo = (...a) => { push('scrollTo(' + a.join(',') + ')'); return origTo(...a); };
                  const rs = history.replaceState.bind(history);
                  history.replaceState = (...a) => { push('replaceState'); return rs(...a); };
                  const ps = history.pushState.bind(history);
                  history.pushState = (...a) => { push('pushState'); return ps(...a); };
                  let lastH = document.documentElement.scrollHeight;
                  new ResizeObserver(() => {
                    const h = document.documentElement.scrollHeight;
                    if (Math.abs(h - lastH) > 40) { push('height ' + lastH + '->' + h); lastH = h; }
                  }).observe(document.body);
                }
                """);

            System.out.printf("viewport %dx%d%n", width, height);
            System.out.printf("%8s  %-9s  %-16s  %s%n", "scrollY", "collapsed", "btn.top min..max", "note");

            int toggles = 0;
            int unstable = 0;
            boolean previousCollapsed = collapsed(page);

            for (int notch = 0; notch < 26; notch++) {
                page.mouse().wheel(0, 120);

                // Sample across the settle window: a value that moves and
                // returns shows up as a spread here, where one reading would
                // have missed it entirely.
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (int sample = 0; sample < 12; sample++) {
                    page.waitForTimeout(25);
                    int top = ((Number) page.evaluate(BUTTON_TOP)).intValue();
                    min = Math.min(min, top);
                    max = Math.max(max, top);
                }

                boolean isCollapsed = collapsed(page);
                int scrollY = ((Number) page.evaluate("() => Math.round(window.scrollY)")).intValue();

                String note = "";
                if (isCollapsed != previousCollapsed) {
                    toggles++;
                    previousCollapsed = isCollapsed;
                    note = "<-- toggled";
                }
                // Anything beyond a couple of pixels of settle is movement a
                // reader would see.
                if (max - min > 4) {
                    unstable++;
                    note += "  UNSTABLE spread=" + (max - min) + "px";
                }
                System.out.printf("%8d  %-9s  %6d..%-8d  %s%n", scrollY, isCollapsed, min, max, note);
            }

            System.out.println();
            System.out.println(toggles <= 2 && unstable == 0
                    ? "OK: " + toggles + " toggle(s), no unstable frames"
                    : "PROBLEM: " + toggles + " toggles, " + unstable + " unstable step(s)");
            System.out.println();
            System.out.println("-- what the page did --");
            Object log = page.evaluate("() => window.__log.slice(0, 40)");
            if (log instanceof java.util.List<?> entries) {
                entries.forEach(e -> System.out.println("   " + e));
            }
            browser.close();
        }
    }

    private static boolean collapsed(Page page) {
        return (Boolean) page.evaluate(
                "() => !!document.querySelector('.home')?.classList.contains('is-collapsed')");
    }
}
