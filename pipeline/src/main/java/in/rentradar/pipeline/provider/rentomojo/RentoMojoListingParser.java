package in.rentradar.pipeline.provider.rentomojo;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a RentoMojo category listing page (server-rendered) into product card
 * summaries: name, product URL, external id, and the advertised monthly rent.
 * Tenure pricing and deposit live on the product page and are fetched per item.
 */
public final class RentoMojoListingParser {

    /** Product URLs look like /bangalore/appliances/rent-single-door-fridge-190-litre/101599 */
    private static final Pattern PRODUCT_HREF = Pattern.compile("^/[a-z-]+/[a-z-]+/rent-[a-z0-9-]+/(\\d+)$");
    private static final Pattern RUPEES = Pattern.compile("([0-9][0-9,]*)");

    public record ListingCard(String externalId, String name, String path, long advertisedMonthlyPaise) {
    }

    private RentoMojoListingParser() {
    }

    public static List<ListingCard> parse(String html) {
        Document document = Jsoup.parse(html);
        Map<String, ListingCard> byId = new LinkedHashMap<>();
        for (Element anchor : document.select("a.rm-product__card[href]")) {
            String href = anchor.attr("href");
            Matcher hrefMatch = PRODUCT_HREF.matcher(href);
            if (!hrefMatch.matches() || href.contains("/packages/")) {
                continue; // packages and category cross-links are not comparable products
            }
            Element heading = anchor.selectFirst(".rm-product__heading");
            if (heading == null) {
                continue;
            }
            String name = heading.text().trim();
            long monthlyPaise = 0;
            Element rent = anchor.selectFirst(".rm-rent__info h3");
            if (rent != null) {
                Matcher amount = RUPEES.matcher(rent.text());
                if (amount.find()) {
                    monthlyPaise = Long.parseLong(amount.group(1).replace(",", "")) * 100;
                }
            }
            String externalId = hrefMatch.group(1);
            byId.putIfAbsent(externalId, new ListingCard(externalId, name, href, monthlyPaise));
        }
        return new ArrayList<>(byId.values());
    }
}
