package in.rentradar.pipeline.store;

import in.rentradar.pipeline.common.Json;
import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.pricing.PriceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void pricesAreSplitPerCategoryAndReadBackWhole() throws Exception {
        DataStore store = new DataStore(tempDir);
        List<PriceRecord> records = List.of(
                record("rentomojo", "1", 470_00, "2026-08-27T06:00:00Z"),
                categorized("guarented", "sofa-3-seater", 900_00));
        Map<String, RentalCategory> categories = Map.of(
                "refrigerator-single-door-150-200l", RentalCategory.REFRIGERATOR,
                "sofa-3-seater", RentalCategory.SOFA);

        store.writePrices(new FileModels.PricesFile(records), categories);

        assertThat(tempDir.resolve("prices/refrigerator.json")).exists();
        assertThat(tempDir.resolve("prices/sofa.json")).exists();
        assertThat(store.loadPrices().records()).hasSize(2);

        // A category that empties out must not leave its file behind.
        store.writePrices(new FileModels.PricesFile(List.of(records.get(0))), categories);
        assertThat(tempDir.resolve("prices/sofa.json")).doesNotExist();
        assertThat(store.loadPrices().records()).hasSize(1);
    }

    @Test
    void anInterruptedSplitFallsBackToTheIntactLegacyFile() throws Exception {
        // writePrices deletes prices.json only after every category file is
        // written, so a legacy file sitting beside a prices/ directory means
        // the split died midway — the legacy file is the whole store (AC-0.3).
        Files.createDirectories(tempDir.resolve("prices"));
        Json.writeAtomically(Json.mapper(), tempDir.resolve("prices/refrigerator.json"),
                new FileModels.PricesFile(List.of(record("rentomojo", "1", 470_00, "2026-08-27T06:00:00Z"))));
        Json.writeAtomically(Json.mapper(), tempDir.resolve("prices.json"),
                new FileModels.PricesFile(List.of(
                        record("rentomojo", "1", 470_00, "2026-08-27T06:00:00Z"),
                        record("guarented", "9", 350_00, "2026-08-27T06:00:00Z"))));

        assertThat(new DataStore(tempDir).loadPrices().records())
                .as("the partial directory must not be mistaken for the store")
                .hasSize(2);
    }

    private static PriceRecord categorized(String provider, String canonicalId, long monthlyPaise) {
        return new PriceRecord(provider, "x", canonicalId, "Some product", "bangalore", 12,
                monthlyPaise, 0, monthlyPaise, 0, 0, 0, 0, monthlyPaise * 12, monthlyPaise * 2,
                Availability.IN_STOCK, null, "https://example.test/x", Instant.parse("2026-08-27T06:00:00Z"));
    }

    private static PriceRecord record(String provider, String externalId, long monthlyPaise, String scrapedAt) {
        return new PriceRecord(provider, externalId, "refrigerator-single-door-150-200l",
                "Single Door Fridge", "bangalore", 12, monthlyPaise, 0, monthlyPaise, 0, 0, 0, 0,
                monthlyPaise * 12, monthlyPaise * 2, Availability.IN_STOCK, null,
                "https://example.test/" + provider + "/" + externalId, Instant.parse(scrapedAt));
    }

    @Test
    void failedProviderKeepsItsPreviousRecordsAndTheirAge() {
        // FR-5.4: the single most important rule in the pipeline.
        List<PriceRecord> previous = List.of(
                record("rentomojo", "1", 470_00, "2026-08-24T06:00:00Z"),
                record("guarented", "9", 350_00, "2026-08-24T06:00:00Z"));
        List<PriceRecord> fresh = List.of(record("rentomojo", "1", 480_00, "2026-08-25T06:00:00Z"));

        List<PriceRecord> merged = DataStore.mergePrices(previous, fresh, Set.of("rentomojo"));

        assertThat(merged).hasSize(2);
        PriceRecord rentomojo = merged.stream().filter(r -> r.provider().equals("rentomojo")).findFirst().orElseThrow();
        assertThat(rentomojo.monthlyPaise()).isEqualTo(480_00);
        assertThat(rentomojo.scrapedAt()).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"));

        PriceRecord guarented = merged.stream().filter(r -> r.provider().equals("guarented")).findFirst().orElseThrow();
        assertThat(guarented.monthlyPaise()).isEqualTo(350_00);
        assertThat(guarented.scrapedAt())
                .as("a failed provider's records survive untouched, age climbing")
                .isEqualTo(Instant.parse("2026-08-24T06:00:00Z"));
    }

    @Test
    void refreshedProviderWithFewerProductsReplacesFully() {
        List<PriceRecord> previous = List.of(
                record("rentomojo", "1", 470_00, "2026-08-24T06:00:00Z"),
                record("rentomojo", "2", 520_00, "2026-08-24T06:00:00Z"));
        List<PriceRecord> fresh = List.of(record("rentomojo", "1", 470_00, "2026-08-25T06:00:00Z"));

        List<PriceRecord> merged = DataStore.mergePrices(previous, fresh, Set.of("rentomojo"));

        assertThat(merged).hasSize(1); // delisted product is gone, not zombie-kept
    }

    @Test
    void zeroProductsIsAFailureNotAnEmptyCatalogue() {
        assertThatThrownBy(() -> DataStore.guardCoverage("rentomojo", 8, 0, 0.5))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("zero products");
    }

    @Test
    void coverageCollapsePastThresholdRefusesToCommit() {
        assertThatThrownBy(() -> DataStore.guardCoverage("rentomojo", 10, 3, 0.5))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("coverage collapsed");
        // A drop within the threshold is allowed.
        DataStore.guardCoverage("rentomojo", 10, 6, 0.5);
        // First run has no baseline.
        DataStore.guardCoverage("rentomojo", 0, 8, 0.5);
    }

    @Test
    void implausibleMonthlyRentIsAParseBug() {
        List<PriceRecord> tooCheap = List.of(record("rentomojo", "1", 59_00, "2026-08-25T06:00:00Z"));
        DataStore.guardPlausibility(tooCheap, 50_00, 100_000_00); // ₹59 is fine against a ₹50 floor

        assertThatThrownBy(() -> DataStore.guardPlausibility(
                List.of(record("rentomojo", "1", 49_00, "2026-08-25T06:00:00Z")), 50_00, 100_000_00))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("plausibility");

        assertThatThrownBy(() -> DataStore.guardPlausibility(
                List.of(record("rentomojo", "1", 200_000_00, "2026-08-25T06:00:00Z")), 50_00, 100_000_00))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void recordsWithoutProvenanceCannotBeConstructed() {
        assertThatThrownBy(() -> new PriceRecord("rentomojo", "1", "x", "n", "bangalore", 12,
                1, 0, 0, 0, 0, 0, 0, 12, 2, Availability.IN_STOCK, null, "", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerUrl");

        assertThatThrownBy(() -> new PriceRecord("rentomojo", "1", "x", "n", "bangalore", 12,
                1, 0, 0, 0, 0, 0, 0, 12, 2, Availability.IN_STOCK, null, "https://example.test", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scrapedAt");
    }
}
