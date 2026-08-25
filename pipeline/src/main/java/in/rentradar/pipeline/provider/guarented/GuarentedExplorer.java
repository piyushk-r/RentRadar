package in.rentradar.pipeline.provider.guarented;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.scraper.PoliteHttpClient;

import java.util.List;

/**
 * Development harness: renders one Guarented product page through the real
 * adapter path and prints what was parsed. Run with:
 *   mvn -f pipeline/pom.xml compile exec:java \
 *     -Dexec.mainClass=in.rentradar.pipeline.provider.guarented.GuarentedExplorer
 * Not part of the pipeline run.
 */
public final class GuarentedExplorer {

    private GuarentedExplorer() {
    }

    public static void main(String[] args) throws Exception {
        RentalCategory category = args.length > 0 ? RentalCategory.valueOf(args[0]) : RentalCategory.MATTRESS;
        String userAgent = "RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; personal, non-commercial price comparison; contact: bookzee.in@gmail.com)";
        PoliteHttpClient client = new PoliteHttpClient(userAgent, 2500);
        GuarentedAdapter adapter = new GuarentedAdapter(client, List.of(category), userAgent, 2500);

        List<RentalProduct> products = adapter.fetchProducts("bangalore", category);
        for (RentalProduct product : products) {
            System.out.println("== " + product.name() + " (" + product.externalId() + ")");
            System.out.println("   url: " + product.url());
            System.out.println("   availability: " + product.availability());
            System.out.println("   raw: " + product.rawAttributes());
            product.tenurePrices().forEach(price -> System.out.println(
                    "   " + price.months() + "mo → ₹" + price.monthlyPaise() / 100
                            + " (deposit ₹" + price.depositPaise() / 100 + ")"));
        }
    }
}
