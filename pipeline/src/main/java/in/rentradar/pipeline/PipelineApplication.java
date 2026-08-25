package in.rentradar.pipeline;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.provider.RentalProvider;
import in.rentradar.pipeline.provider.rentomojo.RentoMojoAdapter;
import in.rentradar.pipeline.scraper.PoliteHttpClient;
import in.rentradar.pipeline.store.DataStore;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot as a CommandLineRunner (PRD section 16): executes one pipeline
 * run, writes files, and exits. The exit code is the alerting system — a
 * failed run fails the workflow, and GitHub sends the email (FR-7.2).
 */
@SpringBootApplication
@EnableConfigurationProperties(PipelineConfig.class)
public class PipelineApplication {

    private int exitCode = 0;

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(PipelineApplication.class, args)));
    }

    @Bean
    PoliteHttpClient politeHttpClient(PipelineConfig config) {
        return new PoliteHttpClient(config.userAgent(), config.requestDelayMillis());
    }

    /** The enabled adapter fleet. Disabling a provider is a config flag plus a commit (FR-7.5). */
    record ProviderSet(List<RentalProvider> providers) {
    }

    @Bean
    ProviderSet rentalProviders(PipelineConfig config, PoliteHttpClient client) {
        List<RentalProvider> providers = new ArrayList<>();
        if (config.providers() == null || config.providers().rentomojo() == null
                || config.providers().rentomojo().enabled()) {
            List<RentalCategory> categories = config.categories() == null || config.categories().isEmpty()
                    ? List.of(RentalCategory.REFRIGERATOR)
                    : config.categories();
            providers.add(new RentoMojoAdapter(client, categories));
        }
        return new ProviderSet(providers);
    }

    @Bean
    DataStore dataStore(PipelineConfig config) {
        return new DataStore(Path.of(config.dataDir()).toAbsolutePath().normalize());
    }

    @Bean
    org.springframework.boot.CommandLineRunner pipeline(PipelineConfig config, DataStore store,
                                                        ProviderSet providerSet) {
        return args -> exitCode = new PipelineRunner(config, store, providerSet.providers()).run();
    }

    @Bean
    ExitCodeGenerator exitCodeGenerator() {
        return () -> exitCode;
    }
}
