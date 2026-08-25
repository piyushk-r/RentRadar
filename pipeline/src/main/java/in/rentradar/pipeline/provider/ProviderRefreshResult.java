package in.rentradar.pipeline.provider;

import in.rentradar.pipeline.common.model.RentalProduct;

import java.util.List;

/**
 * The outcome of one provider refresh, as data. Adapters never throw to the
 * caller (FR-6.2); every failure mode is a field here. A refresh that found
 * zero products where the previous run found many is converted to a failure
 * by the orchestrator, not here (FR-6.4) — the adapter reports what it saw.
 */
public record ProviderRefreshResult(
        String providerId,
        boolean success,
        List<RentalProduct> products,
        List<String> warnings,
        List<String> errors,
        long durationMillis) {

    public ProviderRefreshResult {
        products = products == null ? List.of() : List.copyOf(products);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ProviderRefreshResult failure(String providerId, String error, long durationMillis) {
        return new ProviderRefreshResult(providerId, false, List.of(), List.of(), List.of(error), durationMillis);
    }
}
