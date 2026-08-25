package in.rentradar.pipeline.provider;

/** Identity of a provider. Ids are stable, lowercase, and appear in every data file. */
public record Provider(String id, String displayName, String homepageUrl, IntegrationType integrationType) {

    /**
     * How the data is obtained. An undocumented JSON endpoint spotted in devtools
     * is scraping, not an API, and must never be recorded as API (PRD section 14).
     */
    public enum IntegrationType {
        API,
        SCRAPE_HTML,
        SCRAPE_BROWSER,
        MANUAL
    }
}
