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
        return listing(name, url, RentalCategory.REFRIGERATOR);
    }

    private static RentalProduct listing(String name, String url, RentalCategory category) {
        return new RentalProduct("rentomojo", "1", name, url, null,
                category, Availability.IN_STOCK, 0, 0,
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

    // ---- beds (names captured from live listings, 26 Aug 2026) ----

    @Test
    void bedsMatchOnSizeAndStorage() {
        MatchResult plain = Matcher.match(listing("Napster Queen Bed", "https://example.test/p/1", RentalCategory.BED)).orElseThrow();
        assertThat(plain.product().id()).isEqualTo("bed-queen");
        assertThat(plain.confidence()).isGreaterThanOrEqualTo(0.8);

        MatchResult storage = Matcher.match(listing("Aroura Solid Wood King Bed with Back Storage and Cushion",
                "https://example.test/p/2", RentalCategory.BED)).orElseThrow();
        assertThat(storage.product().id()).isEqualTo("bed-king-storage");

        MatchResult singleXl = Matcher.match(listing("Poise Wooden Single XL Bed",
                "https://example.test/p/3", RentalCategory.BED)).orElseThrow();
        assertThat(singleXl.product().id()).isEqualTo("bed-single-xl");

        // Guarented names sizes in the slug.
        MatchResult guarented = Matcher.match(listing("Rubberwood Double Bed",
                "https://www.guarented.com/bangalore/rent/furniture/beds/rubberwood-double-bed", RentalCategory.BED)).orElseThrow();
        assertThat(guarented.product().id()).isEqualTo("bed-double");
    }

    @Test
    void bedMattressCombosGoToReviewAndBedsideTablesAreExcluded() {
        MatchResult combo = Matcher.match(listing("Queen Bed with Mattress",
                "https://example.test/p/4", RentalCategory.BED)).orElseThrow();
        assertThat(combo.confidence()).isLessThan(0.8);

        assertThat(Matcher.match(listing("Bedside Table", "https://example.test/p/5", RentalCategory.BED))).isEmpty();
    }

    // ---- mattresses ----

    @Test
    void mattressesMatchOnSizeWithTypeAsAttribute() {
        MatchResult foam = Matcher.match(listing("Queen Foam Mattress",
                "https://example.test/p/6", RentalCategory.MATTRESS)).orElseThrow();
        MatchResult latex = Matcher.match(listing("Premium Queen 7-Zone Latex Mattress (With Memory Foam)",
                "https://example.test/p/7", RentalCategory.MATTRESS)).orElseThrow();
        assertThat(foam.product().id()).isEqualTo("mattress-queen");
        assertThat(latex.product().id()).isEqualTo("mattress-queen");

        MatchResult singleXl = Matcher.match(listing("Single XL Coir & Foam Mattress",
                "https://example.test/p/8", RentalCategory.MATTRESS)).orElseThrow();
        assertThat(singleXl.product().id()).isEqualTo("mattress-single-xl");
    }

    // ---- spec text as a secondary source (seen live on Guarented) ----

    private static RentalProduct listingWithSpecs(String name, String url, RentalCategory category, String specs) {
        return new RentalProduct("guarented", "1", name, url, null,
                category, Availability.IN_STOCK, 0, 0,
                List.of(new TenurePrice(12, 50000, 50000, 0)), Map.of("specs", specs),
                Instant.parse("2026-08-26T00:00:00Z"));
    }

    @Test
    void capacityInProviderSpecsRescuesAGenericName() {
        // "Single Door Fridge" + specs "160-190 Litres (depends on the availability)"
        // → the published lower bound bands it.
        MatchResult fridge = Matcher.match(listingWithSpecs("Single Door Fridge",
                "https://www.guarented.com/bangalore/rent/appliances/fridges/single-door-fridge",
                RentalCategory.REFRIGERATOR, "160-190 Litres (depends on the availability)")).orElseThrow();
        assertThat(fridge.product().id()).isEqualTo("refrigerator-single-door-150-200l");
        assertThat(fridge.confidence()).isEqualTo(1.0);

        MatchResult doubleDoor = Matcher.match(listingWithSpecs("Double Door Fridge",
                "https://www.guarented.com/bangalore/rent/appliances/fridges/double-door-fridge",
                RentalCategory.REFRIGERATOR, "Capacity: 220 Ltr to 280 Ltr")).orElseThrow();
        assertThat(doubleDoor.product().id()).isEqualTo("refrigerator-double-door-200-250l");
    }

    @Test
    void sizeInSpecsRescuesABedButStorageInSpecsDoesNot() {
        MatchResult bed = Matcher.match(listingWithSpecs("Eden Upholstered Bed (Beige)",
                "https://www.guarented.com/bangalore/rent/furniture/beds/eden-upholstered-bed-beige",
                RentalCategory.BED, "Size: Queen · Under-bed storage: No")).orElseThrow();
        // Size comes from specs; the word "storage" in a spec line must not flip the row.
        assertThat(bed.product().id()).isEqualTo("bed-queen");
    }

    @Test
    void aBabyCotIsNotABedButCottonIsFine() {
        assertThat(Matcher.match(listing("Emma Baby Cot with Mattress",
                "https://example.test/p/cot", RentalCategory.BED))).isEmpty();

        MatchResult cotton = Matcher.match(listing("Cotton Upholstered Queen Bed",
                "https://example.test/p/cotton", RentalCategory.BED)).orElseThrow();
        assertThat(cotton.product().id()).isEqualTo("bed-queen");
    }

    // ---- washing machines ----

    @Test
    void washingMachinesMatchOnLoadAndCapacityBand() {
        MatchResult topLoad = Matcher.match(listing("Fully Automatic Top Load Washing Machine 6.5 kg",
                "https://example.test/p/9", RentalCategory.WASHING_MACHINE)).orElseThrow();
        assertThat(topLoad.product().id()).isEqualTo("washing-machine-top-load-under-7kg");
        assertThat(topLoad.confidence()).isEqualTo(1.0);

        // "7.0" with no kg suffix, and hyphenated "Fully-Automatic Front Loading".
        MatchResult samsung = Matcher.match(listing("Samsung 7.0 Fully Automatic Top Load Washing Machine",
                "https://example.test/p/10", RentalCategory.WASHING_MACHINE)).orElseThrow();
        assertThat(samsung.product().id()).isEqualTo("washing-machine-top-load-7-8kg");

        MatchResult frontLoad = Matcher.match(listing("Samsung 6.0 Kg Inverter Fully-Automatic Front Loading Washing Machine",
                "https://example.test/p/11", RentalCategory.WASHING_MACHINE)).orElseThrow();
        assertThat(frontLoad.product().id()).isEqualTo("washing-machine-front-load-under-7kg");

        MatchResult semi = Matcher.match(listing("Whirlpool Semi Automatic Washing Machine 7 Kg",
                "https://example.test/p/12", RentalCategory.WASHING_MACHINE)).orElseThrow();
        assertThat(semi.product().id()).isEqualTo("washing-machine-semi-automatic");

        // Load type without capacity: too coarse to auto-link.
        MatchResult vague = Matcher.match(listing("Front Load Washing Machine",
                "https://example.test/p/13", RentalCategory.WASHING_MACHINE)).orElseThrow();
        assertThat(vague.confidence()).isLessThan(0.8);
    }
}
