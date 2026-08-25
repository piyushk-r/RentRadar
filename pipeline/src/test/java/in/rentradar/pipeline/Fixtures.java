package in.rentradar.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String name) {
        try (var in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(Objects.requireNonNull(in, "missing fixture " + name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
