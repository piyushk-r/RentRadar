package in.rentradar.pipeline.provider.furlenco;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Furlenco holds product content behind a "Select Delivery Location" gate:
 * until a city is chosen the page renders skeleton placeholders. This answers
 * that gate the way a visitor does — typing a pincode, or picking the city
 * tile — and nothing more. It is the page's own UI, driven as published; no
 * access control is being circumvented (PRD section 14).
 */
public final class FurlencoCityGate {

    private FurlencoCityGate() {
    }

    /** A representative pincode per city, used to answer the gate's own input. */
    static String pincodeFor(String city) {
        return switch (city) {
            case "bangalore", "bengaluru" -> "560001";
            default -> null;
        };
    }

    static String cityLabelFor(String city) {
        return switch (city) {
            case "bangalore", "bengaluru" -> "Bengaluru";
            default -> null;
        };
    }

    /**
     * Answers the gate if it is showing. Returns a short description of what
     * happened, for the explorer and for warnings.
     */
    public static String answer(Page page, String city) {
        String pincode = pincodeFor(city);
        if (pincode != null) {
            Locator input = page.locator("input[placeholder*='pincode' i]");
            if (input.count() > 0) {
                try {
                    input.first().fill(pincode, new Locator.FillOptions().setTimeout(5000));
                    input.first().press("Enter");
                    page.waitForTimeout(2500);
                    return "entered pincode " + pincode;
                } catch (RuntimeException e) {
                    // fall through to the city tile
                }
            }
        }

        String label = cityLabelFor(city);
        if (label != null) {
            // The tile is an image plus a caption; click whatever is clickable
            // around the caption rather than the text node itself.
            Locator tile = page.locator("div,button,a").filter(
                    new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^" + label + "$")));
            if (tile.count() > 0) {
                try {
                    tile.last().click(new Locator.ClickOptions().setTimeout(6000));
                    page.waitForTimeout(2500);
                    return "chose city tile " + label;
                } catch (RuntimeException e) {
                    return "gate present but not answerable: " + e.getMessage().split("\n")[0];
                }
            }
        }
        return "no gate shown";
    }
}
