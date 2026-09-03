package in.rentradar.pipeline.provider.guarented;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warnings reach a public status page (FR-7.6), so they must not carry a
 * library's stack or the absolute paths of the machine that ran the pipeline.
 */
class GuarentedMessageTest {

    @Test
    void aPlaywrightTimeoutBecomesOneReadableSentence() {
        // Shape taken from a real run: the stack, and a local path, are inside
        // the message itself.
        Exception e = new RuntimeException("""
                Error { message='Timeout 20000ms exceeded.
                  name='TimeoutError stack='TimeoutError: Timeout 20000ms exceeded.
                    at ProgressController.run (C:\\Users\\piyus\\AppData\\Local\\Temp\\playwright-java-1652\\lib.js:79:33)""");

        String message = GuarentedAdapter.shortMessage(e);

        assertThat(message).isEqualTo("Timeout 20000ms exceeded");
        assertThat(message).doesNotContain("C:\\").doesNotContain("stack=").doesNotContain("at ");
        assertThat(message.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void anOrdinaryMessageSurvivesIntact() {
        assertThat(GuarentedAdapter.shortMessage(new IllegalStateException("no product name found")))
                .isEqualTo("no product name found");
    }

    @Test
    void aMessagelessExceptionStillSaysSomething() {
        assertThat(GuarentedAdapter.shortMessage(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }
}
