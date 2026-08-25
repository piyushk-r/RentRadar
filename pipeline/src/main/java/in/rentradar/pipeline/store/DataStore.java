package in.rentradar.pipeline.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.rentradar.pipeline.common.Json;
import in.rentradar.pipeline.pricing.PriceRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads and writes the data/ directory — the database (PRD section 17).
 * All writes are sorted, pretty-printed, and atomic; all writes happen at the
 * end of a run so a killed run leaves the previous commit untouched (AC-0.3).
 */
public class DataStore {

    private final Path dataDir;
    private final ObjectMapper mapper = Json.mapper();

    public DataStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    public Path dataDir() {
        return dataDir;
    }

    // ---- reads ----

    public FileModels.PricesFile loadPrices() {
        return load("prices.json", FileModels.PricesFile.class, new FileModels.PricesFile(List.of()));
    }

    public FileModels.CatalogueFile loadCatalogue() {
        return load("catalogue.json", FileModels.CatalogueFile.class, new FileModels.CatalogueFile(List.of()));
    }

    public FileModels.MappingsFile loadMappings() {
        return load("mappings.json", FileModels.MappingsFile.class, new FileModels.MappingsFile(List.of()));
    }

    public FileModels.PendingFile loadPending() {
        return load("pending-matches.json", FileModels.PendingFile.class, new FileModels.PendingFile(List.of()));
    }

    private <T> T load(String fileName, Class<T> type, T empty) {
        Path file = dataDir.resolve(fileName);
        if (!Files.exists(file)) {
            return empty;
        }
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            // A corrupt store must stop the run, not be silently replaced.
            throw new UncheckedIOException("could not read " + file + " — refusing to overwrite a store I cannot parse", e);
        }
    }

    // ---- merge (FR-5.4: a failed provider must not commit an empty result) ----

    /**
     * Replace the records of providers that succeeded this run; keep every
     * previous record of providers that did not. Their age keeps climbing,
     * which is what surfaces failure honestly.
     */
    public static List<PriceRecord> mergePrices(List<PriceRecord> previous,
                                                List<PriceRecord> fresh,
                                                Set<String> refreshedProviders) {
        List<PriceRecord> merged = new ArrayList<>();
        for (PriceRecord record : previous) {
            if (!refreshedProviders.contains(record.provider())) {
                merged.add(record);
            }
        }
        merged.addAll(fresh);
        merged.sort(Comparator.comparing(PriceRecord::canonicalProductId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PriceRecord::provider)
                .thenComparing(PriceRecord::externalId)
                .thenComparing(PriceRecord::tenureMonths));
        return merged;
    }

    // ---- guards ----

    /**
     * Coverage-collapse guard (FR-6.4 and PRD section 17): zero products where
     * there previously were many, or a drop past the threshold, is a broken
     * adapter, not an empty catalogue.
     */
    public static void guardCoverage(String providerId, int previousCount, int newCount, double maxDropShare) {
        if (newCount == 0) {
            throw new ValidationException(providerId + ": returned zero products — treated as failure, previous data kept (FR-6.4)");
        }
        if (previousCount > 0) {
            int floor = (int) Math.ceil(previousCount * (1.0 - maxDropShare));
            if (newCount < floor) {
                throw new ValidationException(providerId + ": coverage collapsed from " + previousCount + " to " + newCount
                        + " products (allowed floor " + floor + ") — refusing to commit a catastrophic diff");
            }
        }
    }

    /** Plausibility bounds (FR-7.3): a ₹599 fridge at ₹59 is a parse bug, not a sale. */
    public static void guardPlausibility(List<PriceRecord> records, long minMonthlyPaise, long maxMonthlyPaise) {
        for (PriceRecord record : records) {
            if (record.monthlyPaise() < minMonthlyPaise || record.monthlyPaise() > maxMonthlyPaise) {
                throw new ValidationException(record.provider() + "/" + record.externalId() + " at " + record.tenureMonths()
                        + "m: monthly " + record.monthlyPaise() + " paise is outside the plausibility bounds ["
                        + minMonthlyPaise + ", " + maxMonthlyPaise + "] — parse bug until proven otherwise");
            }
        }
    }

    // ---- writes ----

    public void writePrices(FileModels.PricesFile prices) throws IOException {
        Json.writeAtomically(mapper, dataDir.resolve("prices.json"), prices);
    }

    public void writeCatalogue(FileModels.CatalogueFile catalogue) throws IOException {
        List<FileModels.CatalogueEntry> sorted = new ArrayList<>(catalogue.products());
        sorted.sort(Comparator.comparing(FileModels.CatalogueEntry::id));
        Json.writeAtomically(mapper, dataDir.resolve("catalogue.json"), new FileModels.CatalogueFile(sorted));
    }

    public void writeMappings(FileModels.MappingsFile mappings) throws IOException {
        List<FileModels.MappingEntry> sorted = new ArrayList<>(mappings.mappings());
        sorted.sort(Comparator.comparing(FileModels.MappingEntry::provider)
                .thenComparing(FileModels.MappingEntry::externalId));
        Json.writeAtomically(mapper, dataDir.resolve("mappings.json"), new FileModels.MappingsFile(sorted));
    }

    public void writePending(FileModels.PendingFile pending) throws IOException {
        List<FileModels.PendingEntry> sorted = new ArrayList<>(pending.pending());
        sorted.sort(Comparator.comparing(FileModels.PendingEntry::provider)
                .thenComparing(FileModels.PendingEntry::externalId));
        Json.writeAtomically(mapper, dataDir.resolve("pending-matches.json"), new FileModels.PendingFile(sorted));
    }

    public void writeRuns(FileModels.RunsFile runs) throws IOException {
        Json.writeAtomically(mapper, dataDir.resolve("runs.json"), new FileModels.RunsFile(runs.lastRun(), new TreeMap<>(runs.providers())));
    }

    /** Count of distinct listings a provider currently has in the store. */
    public static int countProviderListings(List<PriceRecord> records, String providerId) {
        return (int) records.stream()
                .filter(r -> r.provider().equals(providerId))
                .map(PriceRecord::externalId)
                .distinct()
                .count();
    }

    public static Map<String, FileModels.MappingEntry> mappingIndex(FileModels.MappingsFile mappings) {
        Map<String, FileModels.MappingEntry> index = new TreeMap<>();
        for (FileModels.MappingEntry entry : mappings.mappings()) {
            index.put(entry.provider() + "|" + entry.externalId(), entry);
        }
        return index;
    }
}
