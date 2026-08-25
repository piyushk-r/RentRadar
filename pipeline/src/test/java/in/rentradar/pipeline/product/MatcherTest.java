package in.rentradar.pipeline.product;

import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.common.model.TenurePrice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The normalization examples from PRD section 15, as executable rules:
 * "Single Door Fridge", "190L Refrigerator" and "Samsung 192L Fridge" must
 * land in comparable rows, and anything unparseable must not be guessed.
 */
class MatcherTest {

    private static RentalProduct listing(String name) {
        return listing(name, "https://example.test/p/1");
    }

    private static RentalProduct listing(String name, String url) {
        return new RentalProduct("rentomojo", "1", name, url, null,
                RentalCategory.REFRIGERATOR, Availability.IN_STOCK, 0, 0,
                List.of(new TenurePrice(12, 50000, 50000, 0)), Map.of(), Instant.parse("2026-08-25T00:00:00Z"));
    }

    @Test
    void fullIdentityMatchesAtFullConfidence() {
        MatchResult match = Matcher.match(listing("Single Door Fridge (190 Litre)")).orElseThrow();
        assertThat(match.product().id()).isEqualTo("refrigerator-single-door-150-200l");
        assertThat(match.confidence()).isEqualTo(1.0);
        assertThat(match.product().attributes())
                .containsEntry("door_type", "single_door")
                .containsEntry("capacity_band", "150-200l");
    }

    @Test
    void brandedAndGenericNamesLandInTheSameRow() {
        MatchResult samsung = Matcher.match(listing("Samsung Single Door Fridge (190 Litre)")).orElseThrow();
        MatchResult generic = Matcher.match(listing("Single Door Fridge (190Ltr) 2S")).orElseThrow();
        assertThat(samsung.product().id()).isEqualTo(generic.product().id());
    }

    @Test
    void doubleDoorIsADifferentRow() {
        MatchResult match = Matcher.match(listing("Double Door Fridge (240 Litre)")).orElseThrow();
        assertThat(match.product().id()).isEqualTo("refrigerator-double-door-200-250l");
        assertThat(match.confidence()).isEqualTo(1.0);
    }

    @Test
    void miniFridgeIsItsOwnRowWithoutCapacity() {
        MatchResult match = Matcher.match(listing("Mini Refrigerator")).orElseThrow();
        assertThat(match.product().id()).isEqualTo("refrigerator-mini");
        assertThat(match.confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void partialIdentityStaysBelowTheAutoLinkThreshold() {
        // Capacity but no door type: proposed, not auto-linked (threshold 0.8).
        MatchResult capacityOnly = Matcher.match(listing("Samsung 192L Fridge")).orElseThrow();
        assertThat(capacityOnly.confidence()).isLessThan(0.8);

        MatchResult doorOnly = Matcher.match(listing("Single Door Fridge")).orElseThrow();
        assertThat(doorOnly.confidence()).isLessThan(0.8);
    }

    @Test
    void unparseableNamesAreNeverGuessed() {
        Optional<MatchResult> match = Matcher.match(listing("Kitchen Companion Deluxe"));
        assertThat(match).isEmpty();
    }

    @Test
    void urlSlugSuppliesIdentityThePageNameDropped() {
        // Seen live: the product page says "Refrigerator 210L" while the URL is
        // /bangalore/appliances/rent-single-door-fridge-210-litre/2874.
        MatchResult match = Matcher.match(listing("Refrigerator 210L",
                "https://www.rentomojo.com/bangalore/appliances/rent-single-door-fridge-210-litre/2874")).orElseThrow();
        assertThat(match.product().id()).isEqualTo("refrigerator-single-door-200-250l");
        assertThat(match.confidence()).isEqualTo(1.0);
    }

    @Test
    void aDeepFreezerNeverLandsInAFridgeRow() {
        Optional<MatchResult> match = Matcher.match(listing("Deep Freezer 100L",
                "https://www.rentomojo.com/bangalore/appliances/rent-deep-freezer/18481"));
        assertThat(match).isEmpty();
    }
}
