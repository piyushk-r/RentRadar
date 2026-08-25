package in.rentradar.pipeline.product;

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
 * size, capacity, door and load type out of the provider's name and URL slug.
 * No fuzzing here — anything this cannot parse is a low-confidence match for
 * the review queue, never a guess.
 *
 * The URL slug is provider data too, and often keeps identity the display
 * name drops ("Refrigerator 210L" at /rent-single-door-fridge-210-litre/).
 */
public final class Normalizer {

    private static final Pattern LITRES = Pattern.compile("(\\d{2,4})\\s*(?:l\\b|ltr|litres?|liters?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KILOGRAMS = Pattern.compile("(\\d{1,2}(?:\\.\\d)?)\\s*kgs?\\b", Pattern.CASE_INSENSITIVE);
    /** Bare decimals like the "7.0" in "Samsung 7.0 Fully Automatic": kg by convention in washing-machine names. */
    private static final Pattern BARE_DECIMAL = Pattern.compile("\\b(\\d{1,2}\\.\\d)\\b");

    private static final List<String> KNOWN_BRANDS = List.of(
            "samsung", "lg", "whirlpool", "godrej", "haier", "bosch", "ifb", "voltas", "panasonic", "croma",
            "onida", "bpl", "kelvinator", "sleepwell", "duroflex", "wakefit", "kurlon");

    /** Capacity bands for refrigerators; edges are half-open [min, max). */
    private static final int[][] FRIDGE_BANDS = {{0, 100}, {100, 150}, {150, 200}, {200, 250}, {250, 350}, {350, 10_000}};

    /** Marker attribute: this listing must never be matched into this category's rows. */
    public static final String EXCLUDED = "excluded";

    private Normalizer() {
    }

    public static Map<String, String> extractAttributes(RentalProduct listing) {
        Map<String, String> attributes = new LinkedHashMap<>();
        // Hyphens become spaces so slug words ("single-door") and hyphenated
        // names ("Fully-Automatic") read as words.
        String haystack = (listing.name().toLowerCase(Locale.ROOT) + " " + urlSlugWords(listing.url()))
                .replace('-', ' ');
        // The provider's own spec text ("Capacity: 220 Ltr to 280 Ltr",
        // "Size: Queen") is a second, weaker source: used for sizes and
        // capacities the name dropped, never for keyword flags like storage —
        // a spec line saying "Storage: No" must not read as a storage bed.
        // A published range is banded by its lower bound.
        String specs = listing.rawAttributes().getOrDefault("specs", "").toLowerCase(Locale.ROOT).replace('-', ' ');

        switch (listing.category()) {
            case REFRIGERATOR -> refrigerator(haystack, specs, attributes);
            case BED -> bed(haystack, specs, attributes);
            case MATTRESS -> mattress(haystack, specs, attributes);
            case WASHING_MACHINE -> washingMachine(haystack, specs, attributes);
            default -> {
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

    // ---- refrigerators ----

    private static void refrigerator(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("freezer") && !haystack.contains("fridge") && !haystack.contains("refrigerator")) {
            attributes.put(EXCLUDED, "freezer");
            return;
        }
        if (haystack.contains("mini")) {
            attributes.put("door_type", "mini");
        } else if (haystack.contains("single door")) {
            attributes.put("door_type", "single_door");
        } else if (haystack.contains("double door")) {
            attributes.put("door_type", "double_door");
        } else if (haystack.contains("side by side")) {
            attributes.put("door_type", "side_by_side");
        }
        Matcher capacity = firstMatch(LITRES, haystack, specs);
        if (capacity != null) {
            int litres = Integer.parseInt(capacity.group(1));
            attributes.put("capacity_litres", String.valueOf(litres));
            fridgeBand(litres).ifPresent(band -> attributes.put("capacity_band", band));
        }
    }

    /** Try the pattern on the name+slug haystack first, then on the spec text. */
    private static Matcher firstMatch(Pattern pattern, String haystack, String specs) {
        Matcher inName = pattern.matcher(haystack);
        if (inName.find()) {
            return inName;
        }
        Matcher inSpecs = pattern.matcher(specs);
        return inSpecs.find() ? inSpecs : null;
    }

    public static Optional<String> fridgeBand(int litres) {
        for (int[] band : FRIDGE_BANDS) {
            if (litres >= band[0] && litres < band[1]) {
                return Optional.of(band[0] + "-" + band[1] + "l");
            }
        }
        return Optional.empty();
    }

    // ---- beds ----

    private static final Pattern NOT_A_BED = Pattern.compile("\\b(?:cots?|cribs?)\\b");

    private static void bed(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("bedside") || haystack.contains("hospital") || NOT_A_BED.matcher(haystack).find()) {
            attributes.put(EXCLUDED, "not-a-bed");
            return;
        }
        if (haystack.contains("mattress")) {
            // "Bed with mattress" is a combo, not comparable to a bed alone.
            attributes.put("combo", "with_mattress");
        }
        String size = sizeWord(haystack);
        if (size == null) {
            size = sizeWord(specs);
        }
        if (size != null) {
            attributes.put("size", size);
        }
        // Storage stays a name/slug decision only.
        attributes.put("storage", haystack.contains("storage") ? "yes" : "no");
    }

    // ---- mattresses ----

    private static void mattress(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("protector") || haystack.contains("topper")) {
            attributes.put(EXCLUDED, "not-a-mattress");
            return;
        }
        String size = sizeWord(haystack);
        if (size == null) {
            size = sizeWord(specs);
        }
        if (size != null) {
            attributes.put("size", size);
        }
        String type = null;
        if (haystack.contains("latex")) {
            type = "latex";
        } else if (haystack.contains("memory foam")) {
            type = "memory_foam";
        } else if (haystack.contains("pocket spring")) {
            type = "pocket_spring";
        } else if (haystack.contains("spring")) {
            type = "spring";
        } else if (haystack.contains("coir") && haystack.contains("foam")) {
            type = "coir_foam";
        } else if (haystack.contains("coir")) {
            type = "coir";
        } else if (haystack.contains("foam")) {
            type = "foam";
        }
        if (type != null) {
            attributes.put("type", type);
        }
    }

    /** Shared bed/mattress size vocabulary. "single xl" must win over "single". */
    private static String sizeWord(String haystack) {
        if (haystack.contains("single xl")) {
            return "single_xl";
        }
        if (haystack.contains("king")) {
            return "king";
        }
        if (haystack.contains("queen")) {
            return "queen";
        }
        if (haystack.contains("double")) {
            return "double";
        }
        if (haystack.contains("single")) {
            return "single";
        }
        return null;
    }

    // ---- washing machines ----

    private static void washingMachine(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("semi automatic")) {
            attributes.put("automation", "semi");
        } else if (haystack.contains("fully automatic") || haystack.contains("fully  automatic")) {
            attributes.put("automation", "fully");
        }
        if (haystack.contains("front load")) {
            attributes.put("load_type", "front_load");
        } else if (haystack.contains("top load")) {
            attributes.put("load_type", "top_load");
        }
        Matcher kg = firstMatch(KILOGRAMS, haystack, specs);
        Double capacity = null;
        if (kg != null) {
            capacity = Double.parseDouble(kg.group(1));
        } else {
            Matcher bare = BARE_DECIMAL.matcher(haystack);
            if (bare.find()) {
                double value = Double.parseDouble(bare.group(1));
                if (value >= 5 && value <= 12) { // plausible drum capacity
                    capacity = value;
                }
            }
        }
        if (capacity != null) {
            attributes.put("capacity_kg", trimZero(capacity));
            attributes.put("capacity_band", capacity < 7 ? "under-7kg" : capacity < 8 ? "7-8kg" : "8kg-plus");
        }
    }

    private static String trimZero(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static String urlSlugWords(String url) {
        int lastSlash = url.lastIndexOf('/');
        String withoutId = lastSlash > 0 && url.substring(lastSlash + 1).matches("\\d+")
                ? url.substring(0, lastSlash)
                : url;
        int slugStart = withoutId.lastIndexOf('/');
        String slug = slugStart >= 0 ? withoutId.substring(slugStart + 1) : withoutId;
        return slug.toLowerCase(Locale.ROOT);
    }
}
