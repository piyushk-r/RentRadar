package in.rentradar.pipeline.store;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.pricing.PriceRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The shapes of the files in data/ (PRD section 17). These are the public
 * contract of the whole pipeline: the site reads exactly these.
 */
public final class FileModels {

    private FileModels() {
    }

    public record PricesFile(List<PriceRecord> records) {
        public PricesFile {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    public record CatalogueEntry(String id, RentalCategory category, String name, Map<String, String> attributes) {
    }

    public record CatalogueFile(List<CatalogueEntry> products) {
        public CatalogueFile {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    public record MappingEntry(
            String provider,
            String externalId,
            String providerName,
            String providerUrl,
            String canonicalProductId,
            double confidence,
            String matchedBy, // "auto" | "manual"
            Instant matchedAt) {
    }

    public record MappingsFile(List<MappingEntry> mappings) {
        public MappingsFile {
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }
    }

    public record PendingEntry(
            String provider,
            String externalId,
            String name,
            String url,
            String category, // the listing's category, so a review PR can mint the catalogue row
            String proposedCanonicalId,
            double confidence,
            Instant firstSeenAt) {
    }

    /** Listings below the confidence threshold, awaiting review. Never reaches the site. */
    public record PendingFile(List<PendingEntry> pending) {
        public PendingFile {
            pending = pending == null ? List.of() : List.copyOf(pending);
        }
    }

    public record RunInfo(Instant startedAt, Instant finishedAt) {
    }

    public record ProviderRun(
            String status, // OK | DEGRADED | FAILED
            String displayName,
            String integrationType, // API | SCRAPE_HTML | SCRAPE_BROWSER | MANUAL — manual columns are labelled (PRD section 23)
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            int productsFound,
            int previousProductsFound,
            Integer coverageDeltaPercent,
            String error,
            List<String> warnings) {
        public ProviderRun {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record RunsFile(RunInfo lastRun, Map<String, ProviderRun> providers) {
        public RunsFile {
            providers = providers == null ? Map.of() : Map.copyOf(providers);
        }
    }
}
