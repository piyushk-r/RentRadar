package in.rentradar.pipeline.common;

import java.util.List;

/**
 * The six display tenures (FR-1.2). Providers may publish prices for other
 * commitment lengths (RentoMojo has 11 and 36 months); those are not display
 * tenures and are ignored. A display tenure a provider does not publish is
 * "not published", never derived from a neighbouring tenure (PRD section 25).
 */
public enum Tenure {
    M3(3), M6(6), M9(9), M12(12), M18(18), M24(24);

    private final int months;

    Tenure(int months) {
        this.months = months;
    }

    public int months() {
        return months;
    }

    public static List<Tenure> all() {
        return List.of(values());
    }
}
