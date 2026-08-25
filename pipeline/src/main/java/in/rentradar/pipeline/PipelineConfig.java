package in.rentradar.pipeline;

import in.rentradar.pipeline.common.model.RentalCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * All tunables in one place. Values live in application.yml; the workflow and
 * run scripts override data-dir on the command line.
 */
@ConfigurationProperties(prefix = "pipeline")
public record PipelineConfig(
        String city,
        String dataDir,
        List<RentalCategory> categories,
        String userAgent,
        long requestDelayMillis,
        long providerTimeoutSeconds,
        double autoMatchThreshold,
        double maxCoverageDropShare,
        long minPlausibleMonthlyPaise,
        long maxPlausibleMonthlyPaise,
        Providers providers) {

    public record Providers(Toggle rentomojo, Toggle guarented) {
    }

    public record Toggle(boolean enabled) {
    }
}
