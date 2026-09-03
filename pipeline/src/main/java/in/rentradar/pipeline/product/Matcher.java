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
            case SOFA -> sofa(attributes);
            case WARDROBE -> wardrobe(attributes);
            case STUDY_TABLE -> studyTable(attributes);
            case OFFICE_CHAIR -> officeChair(attributes);
            case DINING_TABLE -> diningTable(attributes);
            case TV -> tv(attributes);
            case AIR_CONDITIONER -> airConditioner(attributes);
            case MICROWAVE -> microwave(attributes);
            case AIR_COOLER -> airCooler(attributes);
            case WATER_PURIFIER -> waterPurifier(attributes);
            default -> Optional.empty();
        };
    }

    /**
     * A rule positively identified this listing as out of scope for its
     * category (a freezer in the fridge list, a stool among chairs). Excluded
     * is a confident decision, not an ambiguity — it belongs nowhere, not in
     * the review queue.
     */
    public static boolean isExcluded(RentalProduct listing) {
        return Normalizer.extractAttributes(listing).containsKey(Normalizer.EXCLUDED);
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

    private static Optional<MatchResult> sofa(Map<String, String> attributes) {
        String style = attributes.get("style");
        String seats = attributes.get("seats");
        if ("sofa_cum_bed".equals(style)) {
            return result("sofa-cum-bed", RentalCategory.SOFA, "Sofa-cum-bed", 0.85,
                    Map.of("style", "sofa_cum_bed"));
        }
        if ("recliner".equals(style)) {
            return result("sofa-recliner", RentalCategory.SOFA, "Recliner", 0.85,
                    Map.of("style", "recliner"));
        }
        if ("l_shape".equals(style)) {
            return result("sofa-l-shape", RentalCategory.SOFA, "L-shaped sofa", 0.9,
                    Map.of("style", "l_shape"));
        }
        if (seats == null) {
            return Optional.empty();
        }
        if ("yes".equals(attributes.get("set"))) {
            // A 3+1+1 set is not comparable to a single sofa: propose, but a
            // human decides whether the set deserves its own row.
            return result("sofa-set-" + seats + "-seater", RentalCategory.SOFA,
                    seats + "-seater sofa set", 0.4, Map.of("seats", seats, "set", "yes"));
        }
        return result("sofa-" + seats + "-seater", RentalCategory.SOFA,
                seats + "-seater sofa", 0.9, Map.of("seats", seats));
    }

    private static Optional<MatchResult> wardrobe(Map<String, String> attributes) {
        String doors = attributes.get("doors");
        if (doors != null) {
            return result("wardrobe-" + doors + "-door", RentalCategory.WARDROBE,
                    doors + "-door wardrobe", 0.9, Map.of("doors", doors));
        }
        String material = attributes.get("material");
        if (material != null) {
            // Material without a door count identifies the family, not the row.
            return result("wardrobe-" + material.replace('_', '-'), RentalCategory.WARDROBE,
                    "Wardrobe (" + material.replace('_', ' ') + ")", 0.6, Map.of("material", material));
        }
        return Optional.empty();
    }

    private static Optional<MatchResult> studyTable(Map<String, String> attributes) {
        boolean storage = "yes".equals(attributes.get("storage"));
        return result("study-table", RentalCategory.STUDY_TABLE, "Study table", 0.85,
                Map.of("storage", storage ? "yes" : "no"));
    }

    private static Optional<MatchResult> officeChair(Map<String, String> attributes) {
        if (!attributes.containsKey("seating")) {
            return Optional.empty(); // nothing said "chair" — review, never a guess
        }
        String type = attributes.get("type");
        Map<String, String> canonical = type == null ? Map.of() : Map.of("type", type);
        return result("office-chair", RentalCategory.OFFICE_CHAIR, "Office / study chair", 0.85, canonical);
    }

    private static Optional<MatchResult> diningTable(Map<String, String> attributes) {
        String seats = attributes.get("seats");
        if (seats == null) {
            return Optional.empty();
        }
        return result("dining-table-" + seats + "-seater", RentalCategory.DINING_TABLE,
                seats + "-seater dining set", 0.9, Map.of("seats", seats));
    }

    private static Optional<MatchResult> tv(Map<String, String> attributes) {
        String band = attributes.get("size_band");
        if (band == null) {
            return Optional.empty();
        }
        return result("tv-" + band, RentalCategory.TV,
                "TV (" + displayTvBand(band) + ")", 0.9, Map.of("size_band", band));
    }

    private static Optional<MatchResult> airConditioner(Map<String, String> attributes) {
        String type = attributes.get("ac_type");
        String tonnage = attributes.get("tonnage");
        if (type != null && tonnage != null) {
            return result("ac-" + type + "-" + tonnage.replace('.', '-') + "-ton", RentalCategory.AIR_CONDITIONER,
                    displayAcType(type) + " AC (" + tonnage + " ton)", 1.0,
                    Map.of("ac_type", type, "tonnage", tonnage));
        }
        if (tonnage != null) {
            return result("ac-" + tonnage.replace('.', '-') + "-ton", RentalCategory.AIR_CONDITIONER,
                    "AC (" + tonnage + " ton)", 0.6, Map.of("tonnage", tonnage));
        }
        if (type != null) {
            return result("ac-" + type, RentalCategory.AIR_CONDITIONER,
                    displayAcType(type) + " AC", 0.6, Map.of("ac_type", type));
        }
        return Optional.empty();
    }

    private static Optional<MatchResult> microwave(Map<String, String> attributes) {
        String type = attributes.get("type");
        if (type == null) {
            return Optional.empty();
        }
        return result("microwave-" + type, RentalCategory.MICROWAVE,
                displayMicrowave(type), 0.9, Map.of("type", type));
    }

    private static Optional<MatchResult> airCooler(Map<String, String> attributes) {
        String band = attributes.get("capacity_band");
        if (band != null) {
            return result("air-cooler-" + band, RentalCategory.AIR_COOLER,
                    "Air cooler (" + displayCoolerBand(band) + ")", 0.9, Map.of("capacity_band", band));
        }
        String type = attributes.get("cooler_type");
        if (type != null) {
            return result("air-cooler-" + type, RentalCategory.AIR_COOLER,
                    Character.toUpperCase(type.charAt(0)) + type.substring(1) + " air cooler", 0.6,
                    Map.of("cooler_type", type));
        }
        return Optional.empty();
    }

    private static Optional<MatchResult> waterPurifier(Map<String, String> attributes) {
        // A purifier "controller" meters an existing unit — review, not a row.
        double confidence = "yes".equals(attributes.get("controller")) ? 0.4 : 0.85;
        String tech = attributes.get("tech");
        Map<String, String> canonical = tech == null ? Map.of() : Map.of("tech", tech);
        return result("water-purifier", RentalCategory.WATER_PURIFIER, "Water purifier", confidence, canonical);
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

    private static String displayTvBand(String band) {
        return switch (band) {
            case "under-32-inch" -> "under 32 inch";
            case "32-39-inch" -> "32–39 inch";
            case "40-49-inch" -> "40–49 inch";
            case "50-inch-plus" -> "50 inch+";
            default -> band;
        };
    }

    private static String displayAcType(String type) {
        return switch (type) {
            case "split" -> "Split";
            case "window" -> "Window";
            case "portable" -> "Portable";
            default -> type;
        };
    }

    private static String displayMicrowave(String type) {
        return switch (type) {
            case "convection" -> "Convection microwave";
            case "grill" -> "Grill microwave";
            case "solo" -> "Solo microwave";
            default -> type + " microwave";
        };
    }

    private static String displayCoolerBand(String band) {
        return switch (band) {
            case "under-30l" -> "under 30 L";
            case "30-55l" -> "30–55 L";
            case "55l-plus" -> "55 L+";
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
