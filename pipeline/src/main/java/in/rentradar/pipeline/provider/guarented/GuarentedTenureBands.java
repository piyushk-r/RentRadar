package in.rentradar.pipeline.provider.guarented;

import in.rentradar.pipeline.common.Tenure;
import in.rentradar.pipeline.common.model.TenurePrice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guarented prices by commitment band, not by exact month: the tenure selector
 * offers options like "3+ Months", "6+ Months", "31+ Months", each with its
 * own monthly rate. A display tenure falls into the band with the largest
 * lower bound not exceeding it — that band's rate is the provider's actual
 * published price for that commitment, so this mapping is a lookup, not a
 * derivation (PRD section 25).
 */
public final class GuarentedTenureBands {

    private static final Pattern LEADING_MONTHS = Pattern.compile("(\\d{1,3})\\s*\\+?\\s*month", Pattern.CASE_INSENSITIVE);

    private GuarentedTenureBands() {
    }

    /** Parse "12+ Months" → 12. Returns -1 when the label is not a tenure band. */
    public static int parseBandStart(String label) {
        Matcher matcher = LEADING_MONTHS.matcher(label.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * Map band prices to the display tenures they cover.
     *
     * @param bandMonthlyPaise band lower bound (months) → monthly price in paise
     * @param depositPaise     flat refundable deposit, same for every band
     */
    public static List<TenurePrice> toTenurePrices(Map<Integer, Long> bandMonthlyPaise, long depositPaise) {
        TreeMap<Integer, Long> bands = new TreeMap<>(bandMonthlyPaise);
        List<TenurePrice> prices = new ArrayList<>();
        for (Tenure tenure : Tenure.all()) {
            Map.Entry<Integer, Long> band = bands.floorEntry(tenure.months());
            if (band == null) {
                continue; // shorter than the shortest published commitment: not published
            }
            prices.add(new TenurePrice(tenure.months(), band.getValue(), depositPaise, 0));
        }
        return prices;
    }
}
