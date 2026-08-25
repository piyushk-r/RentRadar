package in.rentradar.pipeline;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The module boundaries from PRD section 16, held in CI (NFR-A1). The point:
 * pricing stays a pure function of the domain model, adapters stay ignorant of
 * the store and the comparison, and swapping a provider touches one package.
 */
@AnalyzeClasses(packages = "in.rentradar.pipeline", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule pricingDependsOnNothingImpure = noClasses()
            .that().resideInAPackage("..pricing..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..provider..", "..scraper..", "..store..", "..product..")
            .because("pricing/ must not import from provider/ or scraper/ (NFR-A1) and stays a pure function (NFR-A2)");

    @ArchTest
    static final ArchRule normalizerNeverTouchesTheNetwork = noClasses()
            .that().resideInAPackage("..product..")
            .should().dependOnClassesThat().resideInAnyPackage("..scraper..", "..provider..")
            .because("normalization consumes raw listings; it has no business fetching them");

    @ArchTest
    static final ArchRule adaptersDoNotWriteTheStore = noClasses()
            .that().resideInAPackage("..provider..")
            .should().dependOnClassesThat().resideInAnyPackage("..store..", "..pricing..", "..product..")
            .because("adapters emit raw payloads to the normalizer and know nothing of pricing or the store (FR-6.3)");

    @ArchTest
    static final ArchRule commonIsTheBottomLayer = noClasses()
            .that().resideInAPackage("..common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..provider..", "..scraper..", "..store..", "..product..", "..pricing..")
            .because("the shared domain model depends on nothing above it");
}
