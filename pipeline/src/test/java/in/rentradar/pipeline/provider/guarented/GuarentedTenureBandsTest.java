package in.rentradar.pipeline.provider.guarented;

import in.rentradar.pipeline.common.model.TenurePrice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuarentedTenureBandsTest {

    @Test
    void parsesBandLabels() {
        assertThat(GuarentedTenureBands.parseBandStart("31+ Months")).isEqualTo(31);
        assertThat(GuarentedTenureBands.parseBandStart("3+ Months")).isEqualTo(3);
        assertThat(GuarentedTenureBands.parseBandStart("1 Month")).isEqualTo(1);
        assertThat(GuarentedTenureBands.parseBandStart(" 12 + months ")).isEqualTo(12);
        assertThat(GuarentedTenureBands.parseBandStart("Choose plan")).isEqualTo(-1);
    }

    @Test
    void displayTenuresFallIntoTheBandTheyBelongTo() {
        // Bands as published: 1+ ₹700, 3+ ₹600, 6+ ₹550, 12+ ₹500, 31+ ₹450.
        Map<Integer, Long> bands = Map.of(1, 700_00L, 3, 600_00L, 6, 550_00L, 12, 500_00L, 31, 450_00L);
        List<TenurePrice> prices = GuarentedTenureBands.toTenurePrices(bands, 499_00L);

        assertThat(prices).extracting(TenurePrice::months).containsExactly(3, 6, 9, 12, 18, 24);
        assertThat(price(prices, 3).monthlyPaise()).isEqualTo(600_00L);
        assertThat(price(prices, 9).monthlyPaise()).as("9 months sits in the 6+ band").isEqualTo(550_00L);
        assertThat(price(prices, 12).monthlyPaise()).isEqualTo(500_00L);
        assertThat(price(prices, 24).monthlyPaise()).as("24 months sits in the 12+ band, not 31+").isEqualTo(500_00L);
        assertThat(prices).allSatisfy(p -> assertThat(p.depositPaise()).isEqualTo(499_00L));
    }

    @Test
    void tenuresShorterThanTheShortestBandAreNotPublished() {
        Map<Integer, Long> bands = Map.of(6, 550_00L, 12, 500_00L);
        List<TenurePrice> prices = GuarentedTenureBands.toTenurePrices(bands, 0);
        assertThat(prices).extracting(TenurePrice::months)
                .as("3 months is below the shortest published commitment")
                .containsExactly(6, 9, 12, 18, 24);
    }

    private static TenurePrice price(List<TenurePrice> prices, int months) {
        return prices.stream().filter(p -> p.months() == months).findFirst().orElseThrow();
    }
}
