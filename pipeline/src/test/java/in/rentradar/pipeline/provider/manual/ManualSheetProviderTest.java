package in.rentradar.pipeline.provider.manual;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.provider.ProviderRefreshResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ManualSheetProviderTest {

    @TempDir
    Path tempDir;

    private Path sheet(String content) throws Exception {
        Path file = tempDir.resolve("furlenco.yml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void parsesAValidSheetWithUpdatedAtAsScrapedAt() throws Exception {
        ManualSheetProvider provider = new ManualSheetProvider(sheet("""
                provider: furlenco
                displayName: Furlenco
                homepage: https://www.furlenco.com
                updatedAt: 2026-08-20
                listings:
                  - externalId: queen-bed-1
                    name: "Queen bed"
                    url: "https://www.furlenco.com/some-listing"
                    category: BED
                    tenures:
                      - months: 12
                        monthlyRupees: 599
                        depositRupees: 1999
                """));

        assertThat(provider.getProvider().id()).isEqualTo("furlenco");
        assertThat(provider.getProvider().integrationType()).isEqualTo(
                in.rentradar.pipeline.provider.Provider.IntegrationType.MANUAL);
        assertThat(provider.getCapabilities().isManual()).isTrue();

        ProviderRefreshResult result = provider.refresh("bangalore");
        assertThat(result.success()).isTrue();
        assertThat(result.products()).hasSize(1);
        RentalProduct product = result.products().get(0);
        assertThat(product.tenurePrices()).hasSize(1);
        assertThat(product.tenurePrices().get(0).monthlyPaise()).isEqualTo(599_00L);
        assertThat(product.tenurePrices().get(0).depositPaise()).isEqualTo(1999_00L);
        // updatedAt is the freshness truth: a neglected sheet ages visibly.
        assertThat(product.scrapedAt()).isEqualTo(Instant.parse("2026-08-19T18:30:00Z"));
        assertThat(product.category()).isEqualTo(RentalCategory.BED);
    }

    @Test
    void fractionalRupeesAreRejectedNotRounded() throws Exception {
        ManualSheetProvider provider = new ManualSheetProvider(sheet("""
                provider: furlenco
                displayName: Furlenco
                homepage: https://www.furlenco.com
                updatedAt: 2026-08-20
                listings:
                  - externalId: x
                    name: "Bed"
                    url: "https://www.furlenco.com/x"
                    category: BED
                    tenures:
                      - months: 12
                        monthlyRupees: 599.50
                        depositRupees: 1999
                """));
        ProviderRefreshResult result = provider.refresh("bangalore");
        assertThat(result.success()).isFalse();
        assertThat(result.errors().get(0)).contains("whole number");
    }

    @Test
    void aSheetWithoutUpdatedAtFailsLoudly() throws Exception {
        ManualSheetProvider provider = new ManualSheetProvider(sheet("""
                provider: furlenco
                displayName: Furlenco
                homepage: https://www.furlenco.com
                listings: []
                """));
        ProviderRefreshResult result = provider.refresh("bangalore");
        assertThat(result.success()).isFalse();
        assertThat(result.errors().get(0)).contains("updatedAt");
    }
}
