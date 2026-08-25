package in.rentradar.pipeline.product;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Proposes a canonical product for a raw listing (pipeline step 3, PRD section
 * 15). Rules first, and the rules are the whole matcher for refrigerators:
 * door type + capacity band identifies the row. Confidence reflects how much
 * of the identity was actually parsed, and anything below the threshold goes
 * to review, not to the site.
 */
public final class Matcher {

    private Matcher() {
    }

    public static Optional<MatchResult> match(RentalProduct listing) {
        Map<String, String> attributes = Normalizer.extractAttributes(listing);
        if (listing.category() == RentalCategory.REFRIGERATOR) {
            return matchRefrigerator(listing, attributes);
        }
        return Optional.empty();
    }

    private static Optional<MatchResult> matchRefrigerator(RentalProduct listing, Map<String, String> attributes) {
        if (attributes.containsKey("excluded")) {
            return Optional.empty(); // e.g. a deep freezer in the fridge listing
        }
        String doorType = attributes.get("door_type");
        String capacityBand = attributes.get("capacity_band");

        if (doorType == null && capacityBand == null) {
            return Optional.empty();
        }

        double confidence;
        String id;
        String name;
        Map<String, String> canonicalAttributes = new TreeMap<>();

        if ("mini".equals(doorType)) {
            // Mini fridges are one row; capacity rarely appears in their names.
            id = "refrigerator-mini";
            name = "Mini refrigerator";
            canonicalAttributes.put("door_type", "mini");
            confidence = 0.95;
        } else if (doorType != null && capacityBand != null) {
            id = "refrigerator-" + doorType.replace('_', '-') + "-" + capacityBand;
            name = displayDoor(doorType) + " refrigerator (" + displayBand(capacityBand) + ")";
            canonicalAttributes.put("door_type", doorType);
            canonicalAttributes.put("capacity_band", capacityBand);
            confidence = 1.0;
        } else if (doorType != null) {
            // Door type without capacity: too coarse to auto-link.
            id = "refrigerator-" + doorType.replace('_', '-');
            name = displayDoor(doorType) + " refrigerator";
            canonicalAttributes.put("door_type", doorType);
            confidence = 0.6;
        } else {
            // Capacity without door type: propose a band-only row at low confidence.
            id = "refrigerator-" + capacityBand;
            name = "Refrigerator (" + displayBand(capacityBand) + ")";
            canonicalAttributes.put("capacity_band", capacityBand);
            confidence = 0.5;
        }

        return Optional.of(new MatchResult(
                new CanonicalProduct(id, RentalCategory.REFRIGERATOR, name, canonicalAttributes),
                confidence));
    }

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
}
