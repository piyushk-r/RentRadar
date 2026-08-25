package in.rentradar.pipeline.product;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic attribute extraction (pipeline step 2, PRD section 15): pull
 * capacity, door type and brand out of the provider's name. No fuzzing here —
 * anything this cannot parse is a low-confidence match for the review queue,
 * never a guess.
 */
public final class Normalizer {

    private static final Pattern CAPACITY = Pattern.compile("(\\d{2,4})\\s*(?:l\\b|ltr|litres?|liters?)", Pattern.CASE_INSENSITIVE);
    private static final List<String> KNOWN_BRANDS = List.of(
            "samsung", "lg", "whirlpool", "godrej", "haier", "bosch", "ifb", "voltas", "panasonic", "croma");

    /** Capacity bands for refrigerators; band edges are half-open [min, max). */
    private static final int[][] CAPACITY_BANDS = {{0, 100}, {100, 150}, {150, 200}, {200, 250}, {250, 350}, {350, 10_000}};

    private Normalizer() {
    }

    public static Map<String, String> extractAttributes(RentalProduct listing) {
        Map<String, String> attributes = new LinkedHashMap<>();
        String name = listing.name().toLowerCase(Locale.ROOT);
        // The provider's URL slug is provider data too, and often keeps identity
        // the display name drops ("Refrigerator 210L" at /rent-single-door-
        // fridge-210-litre/). Hyphens become spaces so slug words read as words.
        String haystack = name + " " + urlSlugWords(listing.url());

        if (listing.category() == RentalCategory.REFRIGERATOR) {
            if (haystack.contains("freezer") && !haystack.contains("fridge") && !haystack.contains("refrigerator")) {
                // A deep freezer is not a refrigerator; it must never land in a fridge row.
                attributes.put("excluded", "freezer");
                return attributes;
            }
            String doorType = null;
            if (haystack.contains("mini")) {
                doorType = "mini";
            } else if (haystack.contains("single door")) {
                doorType = "single_door";
            } else if (haystack.contains("double door")) {
                doorType = "double_door";
            } else if (haystack.contains("side by side")) {
                doorType = "side_by_side";
            }
            if (doorType != null) {
                attributes.put("door_type", doorType);
            }
            Matcher capacity = CAPACITY.matcher(name);
            if (!capacity.find()) {
                capacity = CAPACITY.matcher(haystack);
                if (!capacity.find()) {
                    capacity = null;
                }
            }
            if (capacity != null) {
                int litres = Integer.parseInt(capacity.group(1));
                attributes.put("capacity_litres", String.valueOf(litres));
                capacityBand(litres).ifPresent(band -> attributes.put("capacity_band", band));
            }
        }

        for (String brand : KNOWN_BRANDS) {
            if (haystack.contains(brand)) {
                attributes.put("brand", brand);
                break;
            }
        }
        return attributes;
    }

    private static String urlSlugWords(String url) {
        int lastSlash = url.lastIndexOf('/');
        String withoutId = lastSlash > 0 && url.substring(lastSlash + 1).matches("\\d+")
                ? url.substring(0, lastSlash)
                : url;
        int slugStart = withoutId.lastIndexOf('/');
        String slug = slugStart >= 0 ? withoutId.substring(slugStart + 1) : withoutId;
        return slug.toLowerCase(Locale.ROOT).replace('-', ' ');
    }

    public static Optional<String> capacityBand(int litres) {
        for (int[] band : CAPACITY_BANDS) {
            if (litres >= band[0] && litres < band[1]) {
                return Optional.of(band[0] + "-" + band[1] + "l");
            }
        }
        return Optional.empty();
    }
}
