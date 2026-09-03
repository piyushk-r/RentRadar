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

    // ---- sofas (names from live listings, 27 Aug 2026) ----

    @Test
    void sofasMatchOnSeatsWithShapesAsTheirOwnRows() {
        MatchResult jute = Matcher.match(listing("Jute Sofa 3 Seater",
                "https://www.guarented.com/bangalore/rent/furniture/sofas/jute-sofa-3-seater",
                RentalCategory.SOFA)).orElseThrow();
        assertThat(jute.product().id()).isEqualTo("sofa-3-seater");
        assertThat(jute.confidence()).isGreaterThanOrEqualTo(0.8);

        MatchResult lShape = Matcher.match(listing("L Shape Sofa",
                "https://www.guarented.com/bangalore/rent/furniture/sofas/l-shape-sofa-on-rent",
                RentalCategory.SOFA)).orElseThrow();
        assertThat(lShape.product().id()).isEqualTo("sofa-l-shape");

        // "3 and 2 seater" is a set: proposed for review, never auto-linked.
        MatchResult set = Matcher.match(listing("Zoey 3 and 2 Seater Sofa",
                "https://example.test/p/sofa-set", RentalCategory.SOFA)).orElseThrow();
        assertThat(set.confidence()).isLessThan(0.8);

        assertThat(Matcher.match(listing("Sofa Cover Deluxe", "https://example.test/p/cover",
                RentalCategory.SOFA))).isEmpty();
    }

    // ---- wardrobes ----

    @Test
    void wardrobesMatchOnDoorCountAndOrganizersAreExcluded() {
        MatchResult twoDoor = Matcher.match(listing("Zoro 2 Door Wardrobe",
                "https://example.test/p/w1", RentalCategory.WARDROBE)).orElseThrow();
        assertThat(twoDoor.product().id()).isEqualTo("wardrobe-2-door");
        assertThat(twoDoor.confidence()).isGreaterThanOrEqualTo(0.8);

        // Material alone identifies a family, not a row.
        MatchResult vague = Matcher.match(listing("Engineered Wood Wardrobe",
                "https://example.test/p/w2", RentalCategory.WARDROBE)).orElseThrow();
        assertThat(vague.confidence()).isLessThan(0.8);

        assertThat(Matcher.match(listing("Clothes Organizer 6 Shelf",
                "https://example.test/p/w3", RentalCategory.WARDROBE))).isEmpty();
    }

    // ---- study tables and chairs share a provider directory ----

    @Test
    void studyTablesAreOneRowAndChairsAreExcludedFromIt() {
        MatchResult table = Matcher.match(listing("Verona Wooden Study Table",
                "https://www.guarented.com/bangalore/rent/furniture/study/verona-wooden-study-table",
                RentalCategory.STUDY_TABLE)).orElseThrow();
        assertThat(table.product().id()).isEqualTo("study-table");
        assertThat(table.confidence()).isGreaterThanOrEqualTo(0.8);

        assertThat(Matcher.match(listing("Nelson Study Chair",
                "https://www.guarented.com/bangalore/rent/furniture/study/nelson-study-chair-on-rent",
                RentalCategory.STUDY_TABLE))).isEmpty();
    }

    @Test
    void officeChairsAreOneRowAndStoolsAreExcluded() {
        MatchResult revolving = Matcher.match(listing("Revolving Study Chair",
                "https://www.guarented.com/bangalore/rent/furniture/study/revolving-study-chair",
                RentalCategory.OFFICE_CHAIR)).orElseThrow();
        assertThat(revolving.product().id()).isEqualTo("office-chair");
        assertThat(revolving.confidence()).isGreaterThanOrEqualTo(0.8);

        assertThat(Matcher.match(listing("Bar Stool", "https://example.test/p/stool",
                RentalCategory.OFFICE_CHAIR))).isEmpty();
    }

    @Test
    void officeChairsSoldUnderBareModelNamesAreMatchedNotDiscarded() {
        // Seen live on RentoMojo's chairs listing: Featherlite models whose
        // names never say "chair". The back-height wording is the signal.
        RentalProduct comet = listing("Comet Medium Back Powered by Featherlite",
                "https://www.rentomojo.com/bangalore/furniture/rent-comet-medium-back-powered-by-featherlite/156263",
                RentalCategory.OFFICE_CHAIR);
        assertThat(Matcher.isExcluded(comet)).as("a bare model name is not a positive non-chair").isFalse();
        assertThat(Matcher.match(comet).orElseThrow().product().id()).isEqualTo("office-chair");

        // Nothing chair-like at all: proposed nowhere, so it lands in review
        // rather than being silently dropped.
        RentalProduct mystery = listing("Q-Dios Desk Organiser", "https://example.test/p/organiser",
                RentalCategory.OFFICE_CHAIR);
        assertThat(Matcher.isExcluded(mystery)).isFalse();
        assertThat(Matcher.match(mystery)).isEmpty();
    }

    @Test
    void shippingWeightInSpecsIsNotADrumCapacity() {
        // "Weight: 45 kg" on a spec table must not become an 8kg+ machine.
        MatchResult match = Matcher.match(listingWithSpecs("Top Load Washing Machine",
                "https://www.guarented.com/bangalore/rent/appliances/washing-machine/top-load-washing-machine",
                RentalCategory.WASHING_MACHINE, "Weight: 45 kg · Warranty: 1 year")).orElseThrow();
        assertThat(match.product().attributes()).doesNotContainKey("capacity_band");
        assertThat(match.confidence()).as("no capacity parsed — review, not a confident row").isLessThan(0.8);
    }

    @Test
    void airCoolerLitresTakeTheFirstFigureAndRejectImplausibleOnes() {
        // A range later in the name must not beat the capacity stated first.
        MatchResult plainFirst = Matcher.match(listing("45 Litre Air Cooler (24-32 L tank)",
                "https://example.test/p/c2", RentalCategory.AIR_COOLER)).orElseThrow();
        assertThat(plainFirst.product().id()).isEqualTo("air-cooler-30-55l");

        // A model year is not a tank size: nothing to band and nothing else to
        // go on, so no proposal at all — the listing goes to review.
        assertThat(Matcher.match(listing("Air Cooler 2024 Model",
                "https://example.test/p/c3", RentalCategory.AIR_COOLER))).isEmpty();
    }

    // ---- dining tables ----

    @Test
    void diningTablesMatchOnSeaterAndPatioSetsAreExcluded() {
        MatchResult six = Matcher.match(listing("Wooden Top 6 Seater Dining Table",
                "https://www.guarented.com/bangalore/rent/furniture/dining/wooden-top-6-seater-dining-table",
                RentalCategory.DINING_TABLE)).orElseThrow();
        assertThat(six.product().id()).isEqualTo("dining-table-6-seater");
        assertThat(six.confidence()).isGreaterThanOrEqualTo(0.8);

        assertThat(Matcher.match(listing("Patio Table Chair",
                "https://www.guarented.com/bangalore/rent/furniture/dining/patio-table-chair-on-rent",
                RentalCategory.DINING_TABLE))).isEmpty();

        // No seater count: proposed to review, never guessed.
        assertThat(Matcher.match(listing("Classic Dining Table",
                "https://example.test/p/d1", RentalCategory.DINING_TABLE))).isEmpty();
    }

    // ---- TVs ----

    @Test
    void tvsMatchOnScreenSizeBandAndTvUnitsAreExcluded() {
        MatchResult small = Matcher.match(listing("32 Inch Smart LED TV",
                "https://www.guarented.com/bangalore/rent/appliances/tv/32-inch-smart-led-tv",
                RentalCategory.TV)).orElseThrow();
        assertThat(small.product().id()).isEqualTo("tv-32-39-inch");
        assertThat(small.confidence()).isGreaterThanOrEqualTo(0.8);

        MatchResult mid = Matcher.match(listing("Smart TV 43 Inch",
                "https://www.guarented.com/bangalore/rent/appliances/tv/smart-tv-43-inch",
                RentalCategory.TV)).orElseThrow();
        assertThat(mid.product().id()).isEqualTo("tv-40-49-inch");

        assertThat(Matcher.match(listing("Engineered Wood Entertainment TV Unit",
                "https://example.test/p/tvunit", RentalCategory.TV))).isEmpty();
    }

    // ---- air conditioners ----

    @Test
    void acsMatchOnTypeAndTonnage() {
        MatchResult split = Matcher.match(listing("Voltas 1.5 Ton Split AC",
                "https://example.test/p/ac1", RentalCategory.AIR_CONDITIONER)).orElseThrow();
        assertThat(split.product().id()).isEqualTo("ac-split-1-5-ton");
        assertThat(split.confidence()).isEqualTo(1.0);

        MatchResult window = Matcher.match(listing("Window AC 1 Ton",
                "https://example.test/p/ac2", RentalCategory.AIR_CONDITIONER)).orElseThrow();
        assertThat(window.product().id()).isEqualTo("ac-window-1-ton");

        // Tonnage without type: too coarse to auto-link.
        MatchResult vague = Matcher.match(listing("Inverter AC 1.5 Ton",
                "https://example.test/p/ac3", RentalCategory.AIR_CONDITIONER)).orElseThrow();
        assertThat(vague.confidence()).isLessThan(0.8);
    }

    // ---- microwaves ----

    @Test
    void microwavesMatchOnTypeAndInductionCooktopsAreExcluded() {
        MatchResult convection = Matcher.match(listing("Convection Microwave Oven",
                "https://www.guarented.com/bangalore/rent/appliances/microwave/convection-microwave-oven",
                RentalCategory.MICROWAVE)).orElseThrow();
        assertThat(convection.product().id()).isEqualTo("microwave-convection");
        assertThat(convection.confidence()).isGreaterThanOrEqualTo(0.8);

        MatchResult solo = Matcher.match(listing("Solo Microwave Oven",
                "https://example.test/p/m1", RentalCategory.MICROWAVE)).orElseThrow();
        assertThat(solo.product().id()).isEqualTo("microwave-solo");

        assertThat(Matcher.match(listing("Induction Cooktop",
                "https://example.test/p/m2", RentalCategory.MICROWAVE))).isEmpty();
    }

    // ---- air coolers ----

    @Test
    void airCoolersMatchOnCapacityBand() {
        MatchResult mid = Matcher.match(listing("Air Cooler 50 Litres",
                "https://www.guarented.com/bangalore/rent/appliances/cooler/air-cooler-50-litres-on-rent",
                RentalCategory.AIR_COOLER)).orElseThrow();
        assertThat(mid.product().id()).isEqualTo("air-cooler-30-55l");
        assertThat(mid.confidence()).isGreaterThanOrEqualTo(0.8);

        // A published range bands by its lower bound, same rule as fridges.
        MatchResult range = Matcher.match(listing("Air Cooler 24-32 Litres",
                "https://www.guarented.com/bangalore/rent/appliances/cooler/air-cooler-24-32-litres",
                RentalCategory.AIR_COOLER)).orElseThrow();
        assertThat(range.product().id()).isEqualTo("air-cooler-under-30l");

        // Marketing type without litres: review, not a row.
        MatchResult vague = Matcher.match(listing("Personal Air Cooler",
                "https://example.test/p/c1", RentalCategory.AIR_COOLER)).orElseThrow();
        assertThat(vague.confidence()).isLessThan(0.8);
    }

    // ---- water purifiers ----

    @Test
    void waterPurifiersAreOneRowAndControllersGoToReview() {
        MatchResult ro = Matcher.match(listing("Kent RO + UV Water Purifier",
                "https://example.test/p/wp1", RentalCategory.WATER_PURIFIER)).orElseThrow();
        assertThat(ro.product().id()).isEqualTo("water-purifier");
        assertThat(ro.confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(ro.product().attributes()).containsEntry("tech", "ro_uv");

        // Seen live on Guarented: a "controller" meters an existing purifier.
        MatchResult controller = Matcher.match(listing("Water Purifier Controller",
                "https://www.guarented.com/bangalore/rent/appliances/water-purifier/water-purifier-controller-on-rent",
                RentalCategory.WATER_PURIFIER)).orElseThrow();
        assertThat(controller.confidence()).isLessThan(0.8);
    }
}
