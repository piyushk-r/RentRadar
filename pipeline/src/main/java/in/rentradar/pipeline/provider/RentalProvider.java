package in.rentradar.pipeline.provider;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;

import java.util.List;

/**
 * The adapter interface (PRD section 14). Each provider is an independent,
 * replaceable adapter behind this interface; swapping a scraper for a real API
 * must change one implementation and its config, nothing else (FR-6.5).
 */
public interface RentalProvider {

    Provider getProvider();

    ProviderCapabilities getCapabilities();

    /**
     * Fetch listings for one city and category. May perform network I/O and may
     * throw; {@link #refresh} is the boundary that converts failures to data.
     */
    List<RentalProduct> fetchProducts(String city, RentalCategory category) throws Exception;

    /**
     * Refresh every configured category for one city. Never throws to the
     * caller; failures are returned as data (FR-6.2).
     */
    ProviderRefreshResult refresh(String city);
}
