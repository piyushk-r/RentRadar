package in.rentradar.pipeline;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.provider.RentalProvider;
import in.rentradar.pipeline.provider.guarented.GuarentedAdapter;
import in.rentradar.pipeline.provider.manual.ManualSheetProvider;
import in.rentradar.pipeline.provider.rentomojo.RentoMojoAdapter;
import in.rentradar.pipeline.scraper.PoliteHttpClient;
import in.rentradar.pipeline.store.DataStore;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
        List<RentalCategory> categories = config.categories() == null || config.categories().isEmpty()
                ? List.of(RentalCategory.REFRIGERATOR)
                : config.categories();
        if (config.providers() == null || config.providers().rentomojo() == null
                || config.providers().rentomojo().enabled()) {
            providers.add(new RentoMojoAdapter(client, categories));
        }
        if (config.providers() == null || config.providers().guarented() == null
                || config.providers().guarented().enabled()) {
            providers.add(new GuarentedAdapter(client, categories, config.userAgent(), config.requestDelayMillis()));
        }
        // Hand-maintained sheets for the providers we may not crawl: every
        // data/manual/*.yml becomes a provider column (PRD section 14).
        Path manualDir = Path.of(config.dataDir()).toAbsolutePath().normalize().resolve("manual");
        if (Files.isDirectory(manualDir)) {
            try (var sheets = Files.list(manualDir)) {
                sheets.filter(f -> f.getFileName().toString().endsWith(".yml"))
                        .sorted()
                        .forEach(sheet -> providers.add(new ManualSheetProvider(sheet)));
            } catch (IOException e) {
                throw new UncheckedIOException("could not scan manual sheets in " + manualDir, e);
            }
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
