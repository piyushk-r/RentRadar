package in.rentradar.pipeline.scraper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal robots.txt evaluator for the crawl gate (PRD section 14). We evaluate
 * the rule group for {@code User-agent: *} — our bot token is not a named agent
 * on any provider, and we must not borrow a named AI crawler's allowance.
 * Longest-match wins between Allow and Disallow, per the original Google
 * semantics. {@code $} anchors and {@code *} wildcards are supported.
 */
public final class RobotsTxt {

    private record Rule(String path, boolean allow) {
    }

    private final List<Rule> starRules;

    private RobotsTxt(List<Rule> starRules) {
        this.starRules = starRules;
    }

    public static RobotsTxt parse(String content) {
        List<Rule> starRules = new ArrayList<>();
        boolean groupAppliesToStar = false;
        boolean inAgentLines = false;
        for (String rawLine : content.split("\r?\n")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (field) {
                case "user-agent" -> {
                    if (!inAgentLines) {
                        groupAppliesToStar = false;
                    }
                    inAgentLines = true;
                    if (value.equals("*")) {
                        groupAppliesToStar = true;
                    }
                }
                case "allow", "disallow" -> {
                    inAgentLines = false;
                    if (groupAppliesToStar && !value.isEmpty()) {
                        starRules.add(new Rule(value, field.equals("allow")));
                    }
                    // An empty Disallow means "allow everything" and needs no rule.
                }
                default -> inAgentLines = false;
            }
        }
        return new RobotsTxt(starRules);
    }

    public boolean isAllowed(String path) {
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        Rule winner = null;
        int winnerLength = -1;
        for (Rule rule : starRules) {
            if (matches(rule.path(), path) && rule.path().length() > winnerLength) {
                winner = rule;
                winnerLength = rule.path().length();
            }
        }
        return winner == null || winner.allow();
    }

    private static boolean matches(String pattern, String path) {
        return matchesFrom(pattern, 0, path, 0);
    }

    private static boolean matchesFrom(String pattern, int p, String path, int s) {
        while (p < pattern.length()) {
            char c = pattern.charAt(p);
            if (c == '*') {
                for (int k = s; k <= path.length(); k++) {
                    if (matchesFrom(pattern, p + 1, path, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (c == '$' && p == pattern.length() - 1) {
                return s == path.length();
            }
            if (s >= path.length() || path.charAt(s) != c) {
                return false;
            }
            p++;
            s++;
        }
        return true; // prefix match
    }
}
