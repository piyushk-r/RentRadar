package in.rentradar.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.rentradar.pipeline.common.Json;
import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.common.model.TenurePrice;
import in.rentradar.pipeline.provider.Provider;
import in.rentradar.pipeline.provider.ProviderCapabilities;
import in.rentradar.pipeline.provider.ProviderRefreshResult;
import in.rentradar.pipeline.provider.RentalProvider;
import in.rentradar.pipeline.store.DataStore;
import in.rentradar.pipeline.store.FileModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over the orchestrator with fake adapters: a successful refresh
 * writes the store; a later failed refresh keeps every previous value and
 * fails the run (AC-0.3 / FR-5.4), and runs.json tells the truth about both.
 */
class PipelineRunnerTest {

    @TempDir
    Path tempDir;

    private static final Instant DAY_ONE = Instant.parse("2026-08-24T06:00:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-08-25T06:00:00Z");

    private PipelineConfig config(Path dataDir) {
        return new PipelineConfig("bangalore", dataDir.toString(), List.of(RentalCategory.REFRIGERATOR),
                "test-agent", 0, 60, 0.8, 0.5, 50_00, 100_000_00,
                new PipelineConfig.Providers(new PipelineConfig.Toggle(true), new PipelineConfig.Toggle(false), new PipelineConfig.Toggle(false)));
    }

    private static RentalProduct fridge(String externalId, String name, long monthlyPaise, Instant scrapedAt) {
        return new RentalProduct("fakeprov", externalId, name, "https://example.test/p/" + externalId, null,
                RentalCategory.REFRIGERATOR, Availability.IN_STOCK, 0, 0,
                List.of(new TenurePrice(12, monthlyPaise, monthlyPaise, 0)), Map.of(), scrapedAt);
    }

    private static RentalProvider fakeProvider(ProviderRefreshResult result) {
        return new RentalProvider() {
            @Override
            public Provider getProvider() {
                return new Provider("fakeprov", "Fake Provider", "https://example.test", Provider.IntegrationType.SCRAPE_HTML);
            }

            @Override
            public ProviderCapabilities getCapabilities() {
                return new ProviderCapabilities(false, true, true, false);
            }

            @Override
            public List<RentalProduct> fetchProducts(String city, RentalCategory category) {
                return result.products();
            }

            @Override
            public ProviderRefreshResult refresh(String city) {
                return result;
            }
        };
    }

    @Test
    void successThenFailureKeepsEveryPreviousValue() throws Exception {
        DataStore store = new DataStore(tempDir);
        ObjectMapper mapper = Json.mapper();

        // Day one: a clean run.
        List<RentalProduct> dayOne = List.of(
                fridge("1", "Single Door Fridge (190 Litre)", 470_00, DAY_ONE),
                fridge("2", "Double Door Fridge (240 Litre)", 899_00, DAY_ONE));
        int exitOne = new PipelineRunner(config(tempDir), store,
                List.of(fakeProvider(new ProviderRefreshResult("fakeprov", true, dayOne, List.of(), List.of(), 10))))
                .run();

        assertThat(exitOne).isZero();
        // Prices are split per category (FR-8.1), one file per category present.
        assertThat(Files.exists(tempDir.resolve("prices").resolve("refrigerator.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("prices.json"))).isFalse();
        FileModels.PricesFile pricesOne = store.loadPrices();
        assertThat(pricesOne.records()).hasSize(2);
        assertThat(Files.exists(tempDir.resolve("catalogue.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("mappings.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("runs.json"))).isTrue();

        // Day two: the adapter dies. Previous values must survive byte-for-byte.
        int exitTwo = new PipelineRunner(config(tempDir), store,
                List.of(fakeProvider(ProviderRefreshResult.failure("fakeprov", "selector returned no nodes", 5))))
                .run();

        assertThat(exitTwo).as("a failed provider fails the run so the workflow emails (FR-7.2)").isEqualTo(1);
        FileModels.PricesFile pricesTwo = store.loadPrices();
        assertThat(pricesTwo.records()).hasSize(2);
        assertThat(pricesTwo.records()).allSatisfy(record ->
                assertThat(record.scrapedAt()).as("age keeps climbing; nothing was fabricated").isEqualTo(DAY_ONE));

        FileModels.RunsFile runs = mapper.readValue(tempDir.resolve("runs.json").toFile(), FileModels.RunsFile.class);
        FileModels.ProviderRun run = runs.providers().get("fakeprov");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.error()).contains("selector returned no nodes");
    }

    @Test
    void coverageCollapseIsConvertedToFailure() throws Exception {
        DataStore store = new DataStore(tempDir);

        List<RentalProduct> eight = List.of(
                fridge("1", "Single Door Fridge (190 Litre)", 470_00, DAY_ONE),
                fridge("2", "Single Door Fridge (170 Litre)", 420_00, DAY_ONE),
                fridge("3", "Single Door Fridge (210 Litre)", 510_00, DAY_ONE),
                fridge("4", "Double Door Fridge (240 Litre)", 899_00, DAY_ONE));
        new PipelineRunner(config(tempDir), store,
                List.of(fakeProvider(new ProviderRefreshResult("fakeprov", true, eight, List.of(), List.of(), 10)))).run();

        // Sudden collapse to one product: primary signal of a changed page structure (FR-6.4).
        List<RentalProduct> one = List.of(fridge("1", "Single Door Fridge (190 Litre)", 470_00, DAY_TWO));
        int exit = new PipelineRunner(config(tempDir), store,
                List.of(fakeProvider(new ProviderRefreshResult("fakeprov", true, one, List.of(), List.of(), 10)))).run();

        assertThat(exit).isEqualTo(1);
        FileModels.PricesFile prices = store.loadPrices();
        assertThat(prices.records())
                .as("the collapse was not committed")
                .allSatisfy(record -> assertThat(record.scrapedAt()).isEqualTo(DAY_ONE));
    }

    @Test
    void lowConfidenceListingsGoToReviewNotToTheSite() throws Exception {
        DataStore store = new DataStore(tempDir);

        List<RentalProduct> mixed = List.of(
                fridge("1", "Single Door Fridge (190 Litre)", 470_00, DAY_ONE),
                fridge("2", "Kitchen Companion Deluxe", 999_00, DAY_ONE)); // unparseable
        int exit = new PipelineRunner(config(tempDir), store,
                List.of(fakeProvider(new ProviderRefreshResult("fakeprov", true, mixed, List.of(), List.of(), 10)))).run();

        assertThat(exit).isZero();
        FileModels.PricesFile prices = store.loadPrices();
        assertThat(prices.records()).allSatisfy(r -> assertThat(r.externalId()).isEqualTo("1"));

        FileModels.PendingFile pending = Json.mapper().readValue(tempDir.resolve("pending-matches.json").toFile(), FileModels.PendingFile.class);
        assertThat(pending.pending()).hasSize(1);
        assertThat(pending.pending().get(0).name()).isEqualTo("Kitchen Companion Deluxe");

        FileModels.RunsFile runs = Json.mapper().readValue(tempDir.resolve("runs.json").toFile(), FileModels.RunsFile.class);
        assertThat(runs.providers().get("fakeprov").status()).isEqualTo("DEGRADED");
        assertThat(runs.providers().get("fakeprov").warnings()).anySatisfy(w -> assertThat(w).contains("review queue"));
    }

    @Test
    void aProviderLeftOutOfARunKeepsItsPricesAndItsStatusRecord() throws Exception {
        // The CI runner cannot reach every host, so a provider can be disabled
        // for a run. Its prices already survive (FR-5.4); its runs.json entry
        // must too, or the status page forgets the provider exists and can no
        // longer say how old those prices are.
        DataStore store = new DataStore(tempDir);
        List<RentalProduct> dayOne = List.of(fridge("1", "Single Door Fridge (190 Litre)", 470_00, DAY_ONE));
        new PipelineRunner(config(tempDir), store,
                List.of(fakeProvider(new ProviderRefreshResult("fakeprov", true, dayOne, List.of(), List.of(), 10)))).run();

        // Day two: the provider is not configured at all.
        int exit = new PipelineRunner(config(tempDir), store, List.of()).run();

        assertThat(exit).as("nothing failed; nothing ran").isZero();
        assertThat(store.loadPrices().records()).hasSize(1);
        FileModels.RunsFile runs = Json.mapper().readValue(tempDir.resolve("runs.json").toFile(), FileModels.RunsFile.class);
        assertThat(runs.providers()).containsKey("fakeprov");
        assertThat(runs.providers().get("fakeprov").lastSuccessAt())
                .as("the carried-forward record still dates its data")
                .isEqualTo(runs.providers().get("fakeprov").lastAttemptAt());
    }
}
