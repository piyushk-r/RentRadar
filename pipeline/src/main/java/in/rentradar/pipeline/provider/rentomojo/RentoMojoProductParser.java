package in.rentradar.pipeline.provider.rentomojo;

import com.fasterxml.jackson.databind.JsonNode;
import in.rentradar.pipeline.common.model.Availability;
import in.rentradar.pipeline.common.model.TenurePrice;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses one RentoMojo product page. The variant object inside the Nuxt SSR
 * state carries the published pricing models — one per commitment length, each
 * with monthly rent, the deposit actually charged, and the quoted tax.
 */
public final class RentoMojoProductParser {

    public record ProductDetails(
            String name,
            String sku,
            long installationFeePaise,
            Availability availability,
            String imageUrl,
            List<TenurePrice> tenurePrices,
            Map<String, String> rawAttributes) {
    }

    private RentoMojoProductParser() {
    }

    public static ProductDetails parse(String html) {
        Document document = Jsoup.parse(html);
        NuxtDataParser nuxt = NuxtDataParser.fromDocument(document);
        JsonNode variant = nuxt.findObjectWithKeys("pricingModels", "sku", "marketingName");
        if (variant == null) {
            throw new IllegalStateException("no product variant in __NUXT_DATA__ — page structure changed");
        }

        String name = text(variant, "marketingName", text(variant, "name", null));
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("variant has no name");
        }
        String sku = text(variant, "sku", "");

        long installationPaise = wholeRupeesToPaise(variant.path("installationCharge"), "installationCharge");

        Availability availability = variant.path("isOutOfStock").asBoolean(false) || variant.path("isPhasedout").asBoolean(false)
                ? Availability.OUT_OF_STOCK
                : Availability.IN_STOCK;

        List<TenurePrice> tenures = new ArrayList<>();
        JsonNode models = variant.path("pricingModels");
        if (!models.isArray() || models.isEmpty()) {
            throw new IllegalStateException("variant has no pricing models");
        }
        for (JsonNode model : models) {
            int months = model.path("numberOfMonths").asInt(0);
            long monthlyPaise = wholeRupeesToPaise(model.path("price"), "price");
            long depositPaise = wholeRupeesToPaise(model.path("depositAmount"), "depositAmount");
            long taxPaise = model.path("taxPrice").isNumber() ? wholeRupeesToPaise(model.path("taxPrice"), "taxPrice") : 0;
            tenures.add(new TenurePrice(months, monthlyPaise, depositPaise, taxPaise));
        }

        String imageUrl = null;
        Element ogImage = document.selectFirst("meta[property=og:image]");
        if (ogImage != null) {
            imageUrl = ogImage.attr("content");
        }

        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("sku", sku);
        if (variant.path("deliveryDays").isNumber()) {
            raw.put("deliveryDays", variant.path("deliveryDays").asText());
        }
        if (variant.path("variantCode").isTextual()) {
            raw.put("variantCode", variant.path("variantCode").asText());
        }

        return new ProductDetails(name, sku, installationPaise, availability, imageUrl, tenures, raw);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    /**
     * RentoMojo publishes whole-rupee amounts. A fractional value in a money
     * field is a parse bug or a format change, and either way not a number we
     * can stand behind — fail rather than round (PRD section 13).
     */
    private static long wholeRupeesToPaise(JsonNode node, String field) {
        if (!node.isNumber()) {
            throw new IllegalStateException(field + " is not a number: " + node);
        }
        double value = node.asDouble();
        if (value < 0 || Math.floor(value) != value) {
            throw new IllegalStateException(field + " is not a whole non-negative rupee amount: " + value);
        }
        return (long) value * 100;
    }
}
