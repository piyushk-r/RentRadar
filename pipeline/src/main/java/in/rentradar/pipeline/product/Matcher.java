package in.rentradar.pipeline.product;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Proposes a canonical product for a raw listing (pipeline step 3, PRD section
 * 15). Rules first — and for these categories the rules are the whole matcher:
 * the identity attributes below define a comparable row. Confidence reflects
 * how much of the identity was actually parsed; below the threshold goes to
 * review, not to the site.
 *
 * Row identity per category:
 *   refrigerator     door type + capacity band
 *   bed              size + storage (a queen with storage and one without are different rows)
 *   mattress         size (construction type is an attribute, so each provider's
 *                    cheapest mattress of a size competes in one row)
 *   washing machine  semi-automatic, or load type + capacity band
 */
public final class Matcher {

    private Matcher() {
    }

    public static Optional<MatchResult> match(RentalProduct listing) {
        Map<String, String> attributes = Normalizer.extractAttributes(listing);
        if (attributes.containsKey(Normalizer.EXCLUDED)) {
            return Optional.empty();
        }
        return switch (listing.category()) {
            case REFRIGERATOR -> refrigerator(attributes);
            case BED -> bed(attributes);
            case MATTRESS -> mattress(attributes);
            case WASHING_MACHINE -> washingMachine(attributes);
            default -> Optional.empty();
        };
    }

    private static Optional<MatchResult> refrigerator(Map<String, String> attributes) {
        String doorType = attributes.get("door_type");
        String band = attributes.get("capacity_band");
        if (doorType == null && band == null) {
            return Optional.empty();
        }
        if ("mini".equals(doorType)) {
            return result("refrigerator-mini", RentalCategory.REFRIGERATOR, "Mini refrigerator", 0.95,
                    Map.of("door_type", "mini"));
        }
        if (doorType != null && band != null) {
            return result("refrigerator-" + doorType.replace('_', '-') + "-" + band, RentalCategory.REFRIGERATOR,
                    displayDoor(doorType) + " refrigerator (" + displayBand(band) + ")", 1.0,
                    Map.of("door_type", doorType, "capacity_band", band));
        }
        if (doorType != null) {
            return result("refrigerator-" + doorType.replace('_', '-'), RentalCategory.REFRIGERATOR,
                    displayDoor(doorType) + " refrigerator", 0.6, Map.of("door_type", doorType));
        }
        return result("refrigerator-" + band, RentalCategory.REFRIGERATOR,
                "Refrigerator (" + displayBand(band) + ")", 0.5, Map.of("capacity_band", band));
    }

    private static Optional<MatchResult> bed(Map<String, String> attributes) {
        String size = attributes.get("size");
        if (size == null) {
            return Optional.empty();
        }
        boolean combo = attributes.containsKey("combo");
        boolean storage = "yes".equals(attributes.get("storage"));
        String id = "bed-" + size.replace('_', '-') + (storage ? "-storage" : "");
        String name = displaySize(size) + " bed" + (storage ? " with storage" : "");
        // A bed-plus-mattress combo is not comparable to a bed alone: propose,
        // but below the auto-link threshold so a human decides.
        double confidence = combo ? 0.4 : 0.9;
        return result(id, RentalCategory.BED, name, confidence,
                Map.of("size", size, "storage", storage ? "yes" : "no"));
    }

    private static Optional<MatchResult> mattress(Map<String, String> attributes) {
        String size = attributes.get("size");
        if (size == null) {
            return Optional.empty();
        }
        Map<String, String> canonical = new TreeMap<>();
        canonical.put("size", size);
        return result("mattress-" + size.replace('_', '-'), RentalCategory.MATTRESS,
                displaySize(size) + " mattress", 0.9, canonical);
    }

    private static Optional<MatchResult> washingMachine(Map<String, String> attributes) {
        String automation = attributes.get("automation");
        String loadType = attributes.get("load_type");
        String band = attributes.get("capacity_band");

        if ("semi".equals(automation)) {
            Map<String, String> canonical = new TreeMap<>();
            canonical.put("automation", "semi");
            if (band != null) {
                canonical.put("capacity_band", band);
            }
            return result("washing-machine-semi-automatic", RentalCategory.WASHING_MACHINE,
                    "Semi-automatic washing machine", 0.9, canonical);
        }
        if (loadType != null && band != null) {
            Map<String, String> canonical = new TreeMap<>();
            canonical.put("load_type", loadType);
            canonical.put("capacity_band", band);
            if (automation != null) {
                canonical.put("automation", automation);
            }
            return result("washing-machine-" + loadType.replace('_', '-') + "-" + band,
                    RentalCategory.WASHING_MACHINE,
                    displayLoad(loadType) + " washing machine (" + displayKgBand(band) + ")", 1.0, canonical);
        }
        if (loadType != null) {
            return result("washing-machine-" + loadType.replace('_', '-'), RentalCategory.WASHING_MACHINE,
                    displayLoad(loadType) + " washing machine", 0.75, Map.of("load_type", loadType));
        }
        return Optional.empty();
    }

    private static Optional<MatchResult> result(String id, RentalCategory category, String name,
                                                double confidence, Map<String, String> attributes) {
        return Optional.of(new MatchResult(new CanonicalProduct(id, category, name, attributes), confidence));
    }

    // ---- display helpers ----

    private static String displayDoor(String doorType) {
        return switch (doorType) {
            case "single_door" -> "Single door";
            case "double_door" -> "Double door";
            case "side_by_side" -> "Side-by-side";
            default -> doorType.replace('_', ' ');
        };
    }

    private static String displayBand(String band) {
        return band.toUpperCase(Locale.ROOT).replace("-", "–");
    }

    private static String displayLoad(String loadType) {
        return "front_load".equals(loadType) ? "Front load" : "Top load";
    }

    private static String displayKgBand(String band) {
        return switch (band) {
            case "under-7kg" -> "under 7 kg";
            case "7-8kg" -> "7–8 kg";
            case "8kg-plus" -> "8 kg+";
            default -> band;
        };
    }

    private static String displaySize(String size) {
        return switch (size) {
            case "single" -> "Single";
            case "single_xl" -> "Single XL";
            case "double" -> "Double";
            case "queen" -> "Queen";
            case "king" -> "King";
            default -> size.replace('_', ' ');
        };
    }
}
