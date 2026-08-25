package in.rentradar.pipeline.product;

import in.rentradar.pipeline.common.model.RentalCategory;

import java.util.Map;
import java.util.TreeMap;

/**
 * One comparable row (PRD section 15). Comparison happens on attributes, not on
 * strings: a 190L and a 240L fridge are different rows, a Samsung 190L and a
 * generic 190L single-door are the same row.
 */
public record CanonicalProduct(String id, RentalCategory category, String name, Map<String, String> attributes) {

    public CanonicalProduct {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("canonical product needs an id");
        }
        attributes = attributes == null ? Map.of() : new TreeMap<>(attributes);
    }
}
