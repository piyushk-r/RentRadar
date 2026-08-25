package in.rentradar.pipeline.provider.rentomojo;

import in.rentradar.pipeline.Fixtures;
import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.TenurePrice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file test against a captured product page (Single Door Fridge 190L,
 * fetched 25 Aug 2026). The expected values were decoded independently from
 * the page's Nuxt payload.
 */
class RentoMojoProductParserTest {

    @Test
    void parsesTenurePricingFromCapturedProductPage() {
        RentoMojoProductParser.ProductDetails details =
                RentoMojoProductParser.parse(Fixtures.read("rentomojo/product-single-door-fridge-190.html"));

        assertThat(details.name()).isEqualTo("Single Door Fridge (190 Litre)");
        assertThat(details.sku()).isEqualTo("XXF190");
        assertThat(details.installationFeePaise()).isZero();
        assertThat(details.availability()).isEqualTo(Availability.IN_STOCK);

        assertThat(details.tenurePrices())
                .extracting(TenurePrice::months)
                .containsExactlyInAnyOrder(3, 6, 9, 11, 12, 24, 36);

        TenurePrice twelve = details.tenurePrices().stream()
                .filter(p -> p.months() == 12).findFirst().orElseThrow();
        assertThat(twelve.monthlyPaise()).isEqualTo(470_00);
        assertThat(twelve.depositPaise()).isEqualTo(470_00);
        assertThat(twelve.monthlyTaxPaise()).isEqualTo(85_00);

        TenurePrice three = details.tenurePrices().stream()
                .filter(p -> p.months() == 3).findFirst().orElseThrow();
        assertThat(three.monthlyPaise()).isEqualTo(588_00);
        assertThat(three.depositPaise()).isEqualTo(588_00);
    }
}
