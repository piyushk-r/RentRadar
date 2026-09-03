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
        return extractAttributes(listing.category(), listing.name(), listing.url(),
                listing.rawAttributes().getOrDefault("specs", ""));
    }

    static Map<String, String> extractAttributes(RentalCategory category,
                                                 String name, String url, String specsText) {
        Map<String, String> attributes = new LinkedHashMap<>();
        // Hyphens become spaces so slug words ("single-door") and hyphenated
        // names ("Fully-Automatic") read as words.
        String haystack = (name.toLowerCase(Locale.ROOT) + " " + urlSlugWords(url))
                .replace('-', ' ');
        // The provider's own spec text ("Capacity: 220 Ltr to 280 Ltr",
        // "Size: Queen") is a second, weaker source: used for sizes and
        // capacities the name dropped, never for keyword flags like storage —
        // a spec line saying "Storage: No" must not read as a storage bed.
        // A published range is banded by its lower bound.
        String specs = specsText.toLowerCase(Locale.ROOT).replace('-', ' ');

        switch (category) {
            case REFRIGERATOR -> refrigerator(haystack, specs, attributes);
            case BED -> bed(haystack, specs, attributes);
            case MATTRESS -> mattress(haystack, specs, attributes);
            case WASHING_MACHINE -> washingMachine(haystack, specs, attributes);
            case SOFA -> sofa(haystack, specs, attributes);
            case WARDROBE -> wardrobe(haystack, specs, attributes);
            case STUDY_TABLE -> studyTable(haystack, attributes);
            case OFFICE_CHAIR -> officeChair(haystack, attributes);
            case DINING_TABLE -> diningTable(haystack, specs, attributes);
            case TV -> tv(haystack, specs, attributes);
            case AIR_CONDITIONER -> airConditioner(haystack, specs, attributes);
            case MICROWAVE -> microwave(haystack, specs, attributes);
            case AIR_COOLER -> airCooler(haystack, specs, attributes);
            case WATER_PURIFIER -> waterPurifier(haystack, attributes);
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
            double value = Double.parseDouble(kg.group(1));
            // Spec tables also carry shipping weight ("Weight: 45 kg"); only a
            // plausible drum capacity may become one, or a courier's number
            // silently becomes a comparison row at full confidence.
            if (value >= 4 && value <= 15) {
                capacity = value;
            }
        }
        if (capacity == null) {
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

    // ---- sofas ----

    private static final Pattern SEATER = Pattern.compile("(\\d)\\s*seater");
    /** "3+1+1", "3 and 2 seater": a sofa set, not one sofa. */
    private static final Pattern SEAT_SET = Pattern.compile("\\d\\s*(?:\\+|and)\\s*\\d");

    private static void sofa(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("cover") || haystack.contains("bean bag")) {
            attributes.put(EXCLUDED, "not-a-sofa");
            return;
        }
        if (haystack.contains("cum bed")) {
            attributes.put("style", "sofa_cum_bed");
        } else if (haystack.contains("recliner")) {
            attributes.put("style", "recliner");
        } else if (haystack.contains("l shape") || haystack.contains("l shaped") || haystack.contains("sectional")) {
            attributes.put("style", "l_shape");
        }
        if (SEAT_SET.matcher(haystack).find() || haystack.contains("sofa set")) {
            attributes.put("set", "yes");
        }
        Matcher seats = firstMatch(SEATER, haystack, specs);
        if (seats != null) {
            attributes.put("seats", seats.group(1));
        }
    }

    // ---- wardrobes ----

    private static final Pattern DOORS = Pattern.compile("(\\d)\\s*door");

    private static void wardrobe(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("organizer") || haystack.contains("organiser") || haystack.contains("shoe")
                || haystack.contains("drying") || haystack.contains("clothes stand")) {
            attributes.put(EXCLUDED, "not-a-wardrobe");
            return;
        }
        Matcher doors = firstMatch(DOORS, haystack, specs);
        if (doors != null) {
            attributes.put("doors", doors.group(1));
        } else if (haystack.contains("single door")) {
            attributes.put("doors", "1");
        } else if (haystack.contains("double door")) {
            attributes.put("doors", "2");
        }
        String material = null;
        if (haystack.contains("engineered")) {
            material = "engineered_wood";
        } else if (haystack.contains("metal") || haystack.contains("steel")) {
            material = "metal";
        } else if (haystack.contains("wood")) {
            material = "wood";
        } else if (haystack.contains("plastic") || haystack.contains("fabric")) {
            material = "fabric_plastic";
        }
        if (material != null) {
            attributes.put("material", material);
        }
    }

    // ---- study tables ----

    private static void studyTable(String haystack, Map<String, String> attributes) {
        if (haystack.contains("chair") || haystack.contains("stool") || haystack.contains("bookshelf")
                || haystack.contains("kids") || haystack.contains("junior")) {
            attributes.put(EXCLUDED, "not-a-study-table");
            return;
        }
        attributes.put("storage", haystack.contains("storage") || haystack.contains("drawer") ? "yes" : "no");
    }

    // ---- office / study chairs ----

    private static void officeChair(String haystack, Map<String, String> attributes) {
        // Exclusion is a positive identification of something else, never
        // "the name did not say chair" — office chairs are sold under bare
        // model names ("Comet Medium Back"), and a silent drop is worse than
        // a review item.
        if (haystack.contains("stool") || haystack.contains("bench") || haystack.contains("dining")
                || haystack.contains("accent") || haystack.contains("lounge") || haystack.contains("cafe")
                || haystack.contains("rocking") || haystack.contains("patio") || haystack.contains("bean bag")
                || haystack.contains("wheel chair") || haystack.contains("wheelchair")) {
            attributes.put(EXCLUDED, "not-an-office-chair");
            return;
        }
        // Desk-chair vocabulary, including the back-height names that stand in
        // for the word "chair". Without one of these the matcher proposes
        // nothing and the listing goes to review.
        if (haystack.contains("chair") || haystack.contains("high back") || haystack.contains("medium back")
                || haystack.contains("mid back") || haystack.contains("low back") || haystack.contains("task")
                || haystack.contains("executive") || haystack.contains("ergonomic") || haystack.contains("mesh")) {
            attributes.put("seating", "desk_chair");
        }
        String type = null;
        if (haystack.contains("ergonomic") || haystack.contains("high back")) {
            type = "ergonomic";
        } else if (haystack.contains("revolving") || haystack.contains("rolling")) {
            type = "revolving";
        }
        if (type != null) {
            attributes.put("type", type);
        }
    }

    // ---- dining tables ----

    private static void diningTable(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("patio") || haystack.contains("bar table") || haystack.contains("coffee")
                || haystack.contains("center") || haystack.contains("centre") || haystack.contains("bedside")
                || haystack.contains("side table") || haystack.contains("console") || haystack.contains("dressing")) {
            attributes.put(EXCLUDED, "not-a-dining-table");
            return;
        }
        Matcher seats = firstMatch(SEATER, haystack, specs);
        if (seats != null) {
            attributes.put("seats", seats.group(1));
        }
    }

    // ---- TVs ----

    private static final Pattern INCHES = Pattern.compile("(\\d{2})\\s*(?:inch(?:es)?\\b|\"|in\\b)", Pattern.CASE_INSENSITIVE);

    private static void tv(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("unit") || haystack.contains("stand") || haystack.contains("trolley")
                || haystack.contains("table")) {
            attributes.put(EXCLUDED, "not-a-tv");
            return;
        }
        if (haystack.contains("smart")) {
            attributes.put("smart", "yes");
        }
        Matcher inches = firstMatch(INCHES, haystack, specs);
        if (inches != null) {
            int size = Integer.parseInt(inches.group(1));
            attributes.put("screen_inches", String.valueOf(size));
            tvBand(size).ifPresent(band -> attributes.put("size_band", band));
        }
    }

    /** Screen-size bands; edges are half-open [min, max). */
    public static Optional<String> tvBand(int inches) {
        if (inches < 20 || inches > 100) {
            return Optional.empty(); // not a plausible TV size — leave for review
        }
        if (inches < 32) {
            return Optional.of("under-32-inch");
        }
        if (inches < 40) {
            return Optional.of("32-39-inch");
        }
        if (inches < 50) {
            return Optional.of("40-49-inch");
        }
        return Optional.of("50-inch-plus");
    }

    // ---- air conditioners ----

    private static final Pattern TONS = Pattern.compile("(\\d(?:\\.\\d)?)\\s*ton", Pattern.CASE_INSENSITIVE);

    private static void airConditioner(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("split")) {
            attributes.put("ac_type", "split");
        } else if (haystack.contains("window")) {
            attributes.put("ac_type", "window");
        } else if (haystack.contains("portable")) {
            attributes.put("ac_type", "portable");
        }
        if (haystack.contains("inverter")) {
            attributes.put("inverter", "yes");
        }
        Matcher tons = firstMatch(TONS, haystack, specs);
        if (tons != null) {
            attributes.put("tonnage", tons.group(1));
        }
    }

    // ---- microwaves ----

    private static void microwave(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("induction") || haystack.contains("otg") || haystack.contains("toaster")
                || haystack.contains("kettle") || haystack.contains("stove") || haystack.contains("cooktop")) {
            attributes.put(EXCLUDED, "not-a-microwave");
            return;
        }
        if (haystack.contains("convection")) {
            attributes.put("type", "convection");
        } else if (haystack.contains("grill")) {
            attributes.put("type", "grill");
        } else if (haystack.contains("solo")) {
            attributes.put("type", "solo");
        }
        Matcher litres = firstMatch(LITRES, haystack, specs);
        if (litres != null) {
            attributes.put("capacity_litres", litres.group(1));
        }
    }

    // ---- air coolers ----

    /**
     * "24-32 litres" reads as "24 32 litres" once hyphens become spaces; a
     * published range bands by its lower bound (same rule as fridges).
     */
    private static final Pattern LITRE_RANGE = Pattern.compile(
            "(\\d{2,4})\\s+\\d{2,4}\\s*(?:l\\b|ltr|litres?|liters?)", Pattern.CASE_INSENSITIVE);

    private static void airCooler(String haystack, String specs, Map<String, String> attributes) {
        if (haystack.contains("conditioner") || haystack.contains("fan")) {
            attributes.put(EXCLUDED, "not-an-air-cooler");
            return;
        }
        if (haystack.contains("personal")) {
            attributes.put("cooler_type", "personal");
        } else if (haystack.contains("desert")) {
            attributes.put("cooler_type", "desert");
        } else if (haystack.contains("tower")) {
            attributes.put("cooler_type", "tower");
        }
        Integer capacity = coolerLitres(haystack);
        if (capacity == null) {
            capacity = coolerLitres(specs);
        }
        if (capacity != null) {
            attributes.put("capacity_litres", String.valueOf(capacity));
            attributes.put("capacity_band", capacity < 30 ? "under-30l" : capacity < 55 ? "30-55l" : "55l-plus");
        }
    }

    /**
     * The first litre figure in the text, whether written plainly or as a
     * range — whichever appears earlier wins, so "45 Litre Cooler (24-32 L
     * tank)" is a 45 L cooler. Implausible tank sizes (a model year, a price)
     * are left for review rather than banded.
     */
    private static Integer coolerLitres(String text) {
        Matcher range = LITRE_RANGE.matcher(text);
        Matcher plain = LITRES.matcher(text);
        boolean hasRange = range.find();
        boolean hasPlain = plain.find();
        Integer value = null;
        if (hasRange && (!hasPlain || range.start() <= plain.start())) {
            value = Integer.parseInt(range.group(1));
        } else if (hasPlain) {
            value = Integer.parseInt(plain.group(1));
        }
        return value != null && value >= 5 && value <= 150 ? value : null;
    }

    // ---- water purifiers ----

    private static final Pattern RO_WORD = Pattern.compile("\\bro\\b");

    private static void waterPurifier(String haystack, Map<String, String> attributes) {
        if (haystack.contains("dispenser") || haystack.contains("cooler")) {
            attributes.put(EXCLUDED, "not-a-water-purifier");
            return;
        }
        // "Controller" listings meter an existing purifier rather than being
        // one; flagged so the matcher sends them to review, not to a row.
        if (haystack.contains("controller")) {
            attributes.put("controller", "yes");
        }
        boolean ro = RO_WORD.matcher(haystack).find();
        boolean uv = haystack.contains("uv");
        if (ro && uv) {
            attributes.put("tech", "ro_uv");
        } else if (ro) {
            attributes.put("tech", "ro");
        } else if (uv) {
            attributes.put("tech", "uv");
        } else if (haystack.contains("gravity")) {
            attributes.put("tech", "gravity");
        }
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
