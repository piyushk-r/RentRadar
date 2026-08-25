package in.rentradar.pipeline.provider;

/** What this adapter can actually do, so the engine can degrade honestly (PRD section 14). */
public record ProviderCapabilities(
        boolean hasApi,
        boolean supportsDeposit,
        boolean supportsTenurePricing,
        boolean isManual) {
}
