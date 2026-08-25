package in.rentradar.pipeline.scraper;

public class FetchException extends Exception {

    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
