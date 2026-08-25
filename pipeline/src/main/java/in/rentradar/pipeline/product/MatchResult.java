package in.rentradar.pipeline.product;

/**
 * A proposed link from one provider listing to one canonical product.
 * Confidence below the auto-link threshold lands in the review queue; an
 * unmatched listing is never guessed into a comparison row (PRD section 15).
 */
public record MatchResult(CanonicalProduct product, double confidence) {
}
