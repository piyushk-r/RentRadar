package in.rentradar.pipeline.provider.rentomojo;

import in.rentradar.pipeline.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file test against a captured Bengaluru refrigerators listing page
 * (fetched 25 Aug 2026). A RentoMojo markup change fails here before it can
 * fail on the site (PRD section 20, Testing).
 */
class RentoMojoListingParserTest {

    @Test
    void parsesProductCardsFromCapturedListing() {
        List<RentoMojoListingParser.ListingCard> cards =
                RentoMojoListingParser.parse(Fixtures.read("rentomojo/listing-refrigerators.html"));

        assertThat(cards).isNotEmpty();
        assertThat(cards.size()).isGreaterThanOrEqualTo(5);

        assertThat(cards).anySatisfy(card -> {
            assertThat(card.externalId()).isEqualTo("101599");
            assertThat(card.name()).isEqualTo("Single Door Fridge (190 Litre)");
            assertThat(card.path()).isEqualTo("/bangalore/appliances/rent-single-door-fridge-190-litre/101599");
            assertThat(card.advertisedMonthlyPaise()).isEqualTo(461_00);
        });

        // Every card has the fields the adapter depends on.
        assertThat(cards).allSatisfy(card -> {
            assertThat(card.externalId()).matches("\\d+");
            assertThat(card.name()).isNotBlank();
            assertThat(card.path()).startsWith("/bangalore/");
        });

        // Packages and cross-links must not leak in as products.
        assertThat(cards).noneSatisfy(card -> assertThat(card.path()).contains("/packages/"));
    }

    @Test
    void parsesBedMattressAndWashingMachineListings() {
        List<RentoMojoListingParser.ListingCard> beds =
                RentoMojoListingParser.parse(Fixtures.read("rentomojo/listing-beds.html"));
        assertThat(beds.size()).isGreaterThanOrEqualTo(15);
        assertThat(beds).anySatisfy(card -> assertThat(card.name()).contains("Queen Bed"));

        List<RentoMojoListingParser.ListingCard> mattresses =
                RentoMojoListingParser.parse(Fixtures.read("rentomojo/listing-mattresses.html"));
        assertThat(mattresses.size()).isGreaterThanOrEqualTo(15);
        assertThat(mattresses).anySatisfy(card -> assertThat(card.name()).contains("Mattress"));

        List<RentoMojoListingParser.ListingCard> washingMachines =
                RentoMojoListingParser.parse(Fixtures.read("rentomojo/listing-washing-machines.html"));
        assertThat(washingMachines.size()).isGreaterThanOrEqualTo(8);
        assertThat(washingMachines).anySatisfy(card -> assertThat(card.name()).contains("Washing Machine"));

        for (List<RentoMojoListingParser.ListingCard> cards : List.of(beds, mattresses, washingMachines)) {
            assertThat(cards).allSatisfy(card -> {
                assertThat(card.externalId()).matches("\\d+");
                assertThat(card.name()).isNotBlank();
                assertThat(card.path()).doesNotContain("/packages/");
            });
        }
    }
}
