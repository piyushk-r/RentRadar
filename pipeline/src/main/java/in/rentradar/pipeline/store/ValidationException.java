package in.rentradar.pipeline.store;

/** A data-integrity rule was violated. The offending provider fails; the run never ships the bad data. */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
