package in.rentradar.pipeline.scraper;

import in.rentradar.pipeline.Fixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtTest {

    @Test
    void rentoMojoSnapshotPermitsProductPathsAndBlocksTheRest() {
        RobotsTxt robots = RobotsTxt.parse(Fixtures.read("rentomojo/robots.txt"));

        assertThat(robots.isAllowed("/bangalore/appliances/refrigerators-on-rent")).isTrue();
        assertThat(robots.isAllowed("/bangalore/appliances/rent-single-door-fridge-190-litre/101599")).isTrue();
        assertThat(robots.isAllowed("/sitemap/bangalore")).isTrue();

        assertThat(robots.isAllowed("/checkout/")).isFalse();
        assertThat(robots.isAllowed("/user/profile")).isFalse();
        assertThat(robots.isAllowed("/admins/anything")).isFalse();
        assertThat(robots.isAllowed("/blog/tag/fridges")).isFalse();
    }

    @Test
    void namedAgentAllowancesAreNotBorrowed() {
        // The fixture grants ClaudeBot and GPTBot full access; our crawler is
        // not one of them and must evaluate only the * group.
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /private/

                User-agent: SomeNamedBot
                Allow: /private/
                """);
        assertThat(robots.isAllowed("/private/page")).isFalse();
        assertThat(robots.isAllowed("/public")).isTrue();
    }

    @Test
    void longestMatchWinsBetweenAllowAndDisallow() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /shop/
                Allow: /shop/public/
                """);
        assertThat(robots.isAllowed("/shop/private")).isFalse();
        assertThat(robots.isAllowed("/shop/public/item")).isTrue();
    }

    @Test
    void wildcardsAndAnchors() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /*?
                Disallow: /catalog$
                """);
        assertThat(robots.isAllowed("/things?page=2")).isFalse();
        assertThat(robots.isAllowed("/catalog")).isFalse();
        assertThat(robots.isAllowed("/catalog/page")).isTrue();
        assertThat(robots.isAllowed("/things")).isTrue();
    }

    @Test
    void missingRulesMeanAllowed() {
        RobotsTxt robots = RobotsTxt.parse("User-agent: *\nDisallow:\n");
        assertThat(robots.isAllowed("/anything")).isTrue();
    }
}
