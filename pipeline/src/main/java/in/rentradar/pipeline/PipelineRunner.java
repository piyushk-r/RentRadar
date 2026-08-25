package in.rentradar.pipeline;

import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.pricing.PriceRecord;
import in.rentradar.pipeline.pricing.PriceResolver;
import in.rentradar.pipeline.product.CanonicalProduct;
import in.rentradar.pipeline.product.MatchResult;
import in.rentradar.pipeline.product.Matcher;
import in.rentradar.pipeline.provider.ProviderRefreshResult;
import in.rentradar.pipeline.provider.RentalProvider;
import in.rentradar.pipeline.store.DataStore;
import in.rentradar.pipeline.store.FileModels;
import in.rentradar.pipeline.store.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One run of the pipeline: refresh every enabled provider (isolated, with a
 * per-provider timeout, FR-6.1), normalize and price what succeeded, merge
 * against the previous store (FR-5.4), and write the data/ files. Returns a
 * non-zero exit code if any provider failed so the workflow fails loudly and
 * GitHub sends its failure email (FR-7.2) — after the good data is written.
 */
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final PipelineConfig config;
    private final DataStore store;
    private final List<RentalProvider> providers;

    public PipelineRunner(PipelineConfig config, DataStore store, List<RentalProvider> providers) {
        this.config = config;
        this.store = store;
        this.providers = providers;
    }

    public int run() throws Exception {
        Instant runStarted = Instant.now();
        log.info("run started: city={}, categories={}, providers={}", config.city(), config.categories(),
                providers.stream().map(p -> p.getProvider().id()).toList());

        FileModels.PricesFile previousPrices = store.loadPrices();
        FileModels.CatalogueFile previousCatalogue = store.loadCatalogue();
        FileModels.MappingsFile previousMappings = store.loadMappings();
        FileModels.PendingFile previousPending = store.loadPending();

        Map<String, ProviderRefreshResult> results = refreshAllIsolated();

        Map<String, FileModels.MappingEntry> mappingIndex = DataStore.mappingIndex(previousMappings);
        Map<String, CanonicalProduct> catalogue = catalogueIndex(previousCatalogue);
        Map<String, FileModels.PendingEntry> pending = pendingIndex(previousPending);

        List<PriceRecord> freshRecords = new ArrayList<>();
        Set<String> succeededProviders = new HashSet<>();
        Map<String, FileModels.ProviderRun> providerRuns = new TreeMap<>();
        boolean anyFailure = false;

        for (RentalProvider provider : providers) {
            String providerId = provider.getProvider().id();
            ProviderRefreshResult result = results.get(providerId);
            Instant attemptAt = Instant.now();
            int previousCount = DataStore.countProviderListings(previousPrices.records(), providerId);
            List<String> warnings = new ArrayList<>(result.warnings());

            try {
                if (!result.success()) {
                    throw new ValidationException(String.join("; ", result.errors()));
                }
                DataStore.guardCoverage(providerId, previousCount, result.products().size(), config.maxCoverageDropShare());

                List<PriceRecord> providerRecords = new ArrayList<>();
                int unmatched = 0;
                for (RentalProduct listing : result.products()) {
                    String canonicalId = resolveCanonicalId(listing, mappingIndex, catalogue, pending);
                    if (canonicalId == null) {
                        unmatched++;
                        continue; // in the review queue, never guessed into a row
                    }
                    providerRecords.addAll(PriceResolver.resolve(listing, canonicalId, config.city()));
                }
                DataStore.guardPlausibility(providerRecords, config.minPlausibleMonthlyPaise(), config.maxPlausibleMonthlyPaise());

                if (unmatched > 0) {
                    warnings.add(unmatched + " listing(s) below match confidence — sent to review queue");
                }
                freshRecords.addAll(providerRecords);
                succeededProviders.add(providerId);

                int newCount = result.products().size();
                Integer coverageDelta = previousCount > 0
                        ? (int) Math.round((newCount - previousCount) * 100.0 / previousCount)
                        : null;
                String status = warnings.isEmpty() ? "OK" : "DEGRADED";
                providerRuns.put(providerId, new FileModels.ProviderRun(
                        status, attemptAt, attemptAt, newCount, previousCount, coverageDelta, null, warnings));
                log.info("{}: {} with {} products ({} price records)", providerId, status, newCount, providerRecords.size());
            } catch (ValidationException e) {
                anyFailure = true;
                Instant lastSuccess = previousRunSuccess(providerId);
                providerRuns.put(providerId, new FileModels.ProviderRun(
                        "FAILED", attemptAt, lastSuccess, 0, previousCount, null, e.getMessage(), warnings));
                log.error("{}: FAILED — {} (previous data kept, FR-5.4)", providerId, e.getMessage());
            }
        }

        List<PriceRecord> merged = DataStore.mergePrices(previousPrices.records(), freshRecords, succeededProviders);

        store.writeCatalogue(new FileModels.CatalogueFile(catalogue.values().stream()
                .map(p -> new FileModels.CatalogueEntry(p.id(), p.category(), p.name(), p.attributes()))
                .toList()));
        store.writeMappings(new FileModels.MappingsFile(List.copyOf(mappingIndex.values())));
        store.writePending(new FileModels.PendingFile(List.copyOf(pending.values())));
        store.writePrices(new FileModels.PricesFile(merged));
        store.writeRuns(new FileModels.RunsFile(new FileModels.RunInfo(runStarted, Instant.now()), providerRuns));

        log.info("run finished: {} price records ({} fresh), {} canonical products, {} pending matches{}",
                merged.size(), freshRecords.size(), catalogue.size(), pending.size(),
                anyFailure ? " — ONE OR MORE PROVIDERS FAILED" : "");
        return anyFailure ? 1 : 0;
    }

    // ---- provider isolation ----

    private Map<String, ProviderRefreshResult> refreshAllIsolated() throws InterruptedException {
        Map<String, ProviderRefreshResult> results = new LinkedHashMap<>();
        for (RentalProvider provider : providers) {
            String providerId = provider.getProvider().id();
            // One single-thread executor per provider: a hung adapter cannot stall the fleet (FR-6.1).
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "provider-" + providerId);
                t.setDaemon(true);
                return t;
            });
            long started = System.currentTimeMillis();
            try {
                Future<ProviderRefreshResult> future = executor.submit(() -> provider.refresh(config.city()));
                results.put(providerId, future.get(config.providerTimeoutSeconds(), TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                results.put(providerId, ProviderRefreshResult.failure(providerId,
                        "timed out after " + config.providerTimeoutSeconds() + "s", System.currentTimeMillis() - started));
            } catch (Exception e) {
                results.put(providerId, ProviderRefreshResult.failure(providerId,
                        "adapter threw: " + e.getMessage(), System.currentTimeMillis() - started));
            } finally {
                executor.shutdownNow();
            }
        }
        return results;
    }

    // ---- normalization plumbing ----

    /**
     * Existing mapping wins (learned state is applied automatically on every
     * refresh); otherwise the matcher proposes, and only a confident proposal
     * auto-links (PRD section 15).
     */
    private String resolveCanonicalId(RentalProduct listing,
                                      Map<String, FileModels.MappingEntry> mappingIndex,
                                      Map<String, CanonicalProduct> catalogue,
                                      Map<String, FileModels.PendingEntry> pending) {
        String key = listing.providerId() + "|" + listing.externalId();
        FileModels.MappingEntry existing = mappingIndex.get(key);
        if (existing != null) {
            pending.remove(key);
            return existing.canonicalProductId();
        }

        MatchResult match = Matcher.match(listing).orElse(null);
        if (match == null || match.confidence() < config.autoMatchThreshold()) {
            pending.putIfAbsent(key, new FileModels.PendingEntry(
                    listing.providerId(), listing.externalId(), listing.name(), listing.url(),
                    match == null ? null : match.product().id(),
                    match == null ? 0.0 : match.confidence(),
                    listing.scrapedAt()));
            return null;
        }

        CanonicalProduct product = match.product();
        catalogue.putIfAbsent(product.id(), product);
        mappingIndex.put(key, new FileModels.MappingEntry(
                listing.providerId(), listing.externalId(), listing.name(), listing.url(),
                product.id(), match.confidence(), "auto", listing.scrapedAt()));
        pending.remove(key);
        return product.id();
    }

    private Map<String, CanonicalProduct> catalogueIndex(FileModels.CatalogueFile file) {
        Map<String, CanonicalProduct> index = new TreeMap<>();
        for (FileModels.CatalogueEntry entry : file.products()) {
            index.put(entry.id(), new CanonicalProduct(entry.id(), entry.category(), entry.name(), entry.attributes()));
        }
        return index;
    }

    private Map<String, FileModels.PendingEntry> pendingIndex(FileModels.PendingFile file) {
        Map<String, FileModels.PendingEntry> index = new TreeMap<>();
        for (FileModels.PendingEntry entry : file.pending()) {
            index.put(entry.provider() + "|" + entry.externalId(), entry);
        }
        return index;
    }

    private Instant previousRunSuccess(String providerId) {
        // Best effort: the previous runs.json may carry the last success time.
        try {
            java.nio.file.Path runsFile = store.dataDir().resolve("runs.json");
            if (java.nio.file.Files.exists(runsFile)) {
                FileModels.RunsFile previous = in.rentradar.pipeline.common.Json.mapper()
                        .readValue(runsFile.toFile(), FileModels.RunsFile.class);
                FileModels.ProviderRun run = previous.providers().get(providerId);
                return run == null ? null : run.lastSuccessAt();
            }
        } catch (Exception e) {
            log.warn("could not read previous runs.json for lastSuccessAt: {}", e.getMessage());
        }
        return null;
    }
}
