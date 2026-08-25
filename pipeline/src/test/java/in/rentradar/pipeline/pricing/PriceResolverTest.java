package in.rentradar.pipeline.pricing;

import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.common.model.TenurePrice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one formula (PRD section 10), held by property: deposit is refundable
 * and stays out of estimatedTotal; unpublished tenures produce no record; all
 * arithmetic is integer paise.
 */
class PriceResolverTest {

    private static RentalProduct listing(List<TenurePrice> prices, long deliveryPaise, long installationPaise) {
        return new RentalProduct("rentomojo", "42", "Single Door Fridge (190 Litre)",
                "https://example.test/p/42", null, RentalCategory.REFRIGERATOR, Availability.IN_STOCK,
                deliveryPaise, installationPaise, prices, Map.of(), Instant.parse("2026-08-25T06:00:00Z"));
    }

    @Test
    void resolvesTheFormulaExactly() {
        RentalProduct fridge = listing(List.of(new TenurePrice(12, 470_00, 470_00, 85_00)), 0, 99_00);
        List<PriceRecord> records = PriceResolver.resolve(fridge, "refrigerator-single-door-150-200l", "bangalore");

        assertThat(records).hasSize(1);
        PriceRecord record = records.get(0);
        assertThat(record.tenureMonths()).isEqualTo(12);
        assertThat(record.estimatedTotalPaise()).isEqualTo(470_00L * 12 + 99_00);
        assertThat(record.cashUpfrontPaise()).isEqualTo(470_00L + 99_00 + 470_00);
        assertThat(record.depositPaise()).isEqualTo(470_00);
        assertThat(record.canonicalProductId()).isEqualTo("refrigerator-single-door-150-200l");
        assertThat(record.providerUrl()).isEqualTo("https://example.test/p/42");
        assertThat(record.scrapedAt()).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"));
    }

    @Test
    void unpublishedTenuresProduceNoRecordAndAreNeverDerived() {
        // RentoMojo publishes 3/6/9/11/12/24/36; the display tenures pick out
        // 3/6/9/12/24 — 18 is absent and 11/36 are not display tenures.
        List<TenurePrice> published = List.of(
                new TenurePrice(3, 588_00, 588_00, 106_00),
                new TenurePrice(6, 541_00, 541_00, 97_00),
                new TenurePrice(9, 503_00, 503_00, 91_00),
                new TenurePrice(11, 482_00, 482_00, 87_00),
                new TenurePrice(12, 470_00, 470_00, 85_00),
                new TenurePrice(24, 465_00, 465_00, 84_00),
                new TenurePrice(36, 461_00, 461_00, 83_00));
        List<PriceRecord> records = PriceResolver.resolve(listing(published, 0, 0), "refrigerator-single-door-150-200l", "bangalore");

        assertThat(records).extracting(PriceRecord::tenureMonths).containsExactly(3, 6, 9, 12, 24);
        assertThat(records).extracting(PriceRecord::tenureMonths).doesNotContain(18, 11, 36);
    }

    @Test
    void depositNeverLeaksIntoEstimatedTotal() {
        Random random = new Random(20260825);
        for (int i = 0; i < 500; i++) {
            long monthly = 100L + random.nextInt(2_000_000);
            long deposit = random.nextInt(5_000_000);
            long delivery = random.nextInt(100_000);
            long installation = random.nextInt(100_000);
            int months = List.of(3, 6, 9, 12, 18, 24).get(random.nextInt(6));

            RentalProduct product = listing(List.of(new TenurePrice(months, monthly, deposit, 0)), delivery, installation);
            List<PriceRecord> records = PriceResolver.resolve(product, "x", "bangalore");

            assertThat(records).hasSize(1);
            PriceRecord record = records.get(0);
            assertThat(record.estimatedTotalPaise())
                    .as("estimatedTotal must be rent*months + one-off fees, independent of deposit")
                    .isEqualTo(monthly * months + delivery + installation);
            assertThat(record.cashUpfrontPaise())
                    .as("cashUpfront is deposit + one-off fees + first month")
                    .isEqualTo(deposit + delivery + installation + monthly);
        }
    }
}
