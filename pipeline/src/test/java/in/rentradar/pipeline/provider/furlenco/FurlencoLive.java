package in.rentradar.pipeline.provider.furlenco;

import in.rentradar.pipeline.common.model.RentalCategory;
import in.rentradar.pipeline.common.model.RentalProduct;
import in.rentradar.pipeline.scraper.PoliteHttpClient;

import java.util.List;

/** Dev harness: one live category through the real adapter path. */
public final class FurlencoLive {
    public static void main(String[] args) throws Exception {
        RentalCategory category = RentalCategory.valueOf(args.length > 0 ? args[0] : "REFRIGERATOR");
        String ua = "RentRadarBot/0.1 (+https://github.com/piyushk-r/RentRadar; personal, non-commercial price comparison; contact: bookzee.in@gmail.com)";
        PoliteHttpClient client = new PoliteHttpClient(ua, 2500);
        FurlencoAdapter adapter = new FurlencoAdapter(client, List.of(category), ua, 2500);
        List<RentalProduct> products = adapter.fetchProducts("bangalore", category);
        System.out.println("products: " + products.size());
        products.stream().limit(8).forEach(p -> System.out.println(
                "  " + p.externalId() + " | " + p.name() + " | Rs"
                        + p.tenurePrices().get(0).monthlyPaise() / 100 + "/mo | " + p.url()));
    }
}
