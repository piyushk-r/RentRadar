package in.rentradar.pipeline.common.model;

/**
 * One published price point for one commitment length, exactly as the provider
 * publishes it. Money is integer paise everywhere (PRD section 17); a fractional
 * rupee amount from a provider is a parse bug, not a rounding task.
 *
 * @param months           commitment length as published; may be a value outside the six display tenures
 * @param monthlyPaise     the provider's advertised monthly rent
 * @param depositPaise     refundable security deposit charged for this plan
 * @param monthlyTaxPaise  tax the provider quotes on top of the advertised rent, 0 if unknown or included
 */
public record TenurePrice(int months, long monthlyPaise, long depositPaise, long monthlyTaxPaise) {

    public TenurePrice {
        if (months <= 0) {
            throw new IllegalArgumentException("months must be positive: " + months);
        }
        if (monthlyPaise < 0 || depositPaise < 0 || monthlyTaxPaise < 0) {
            throw new IllegalArgumentException("negative money in tenure price for " + months + " months");
        }
    }
}
