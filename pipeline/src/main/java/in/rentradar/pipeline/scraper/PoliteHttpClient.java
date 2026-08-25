package in.rentradar.pipeline.scraper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only way any adapter talks to the network. It identifies us honestly,
 * re-fetches and honours robots.txt per host at runtime (the PRD's crawl-posture
 * table is documentation, not enforcement), spaces requests out, and fails
 * loudly on 429/5xx rather than retrying into a block (PRD section 20).
 */
public class PoliteHttpClient {

    private final HttpClient http;
    private final String userAgent;
    private final long requestDelayMillis;
    private final Map<String, RobotsTxt> robotsByHost = new ConcurrentHashMap<>();
    private long lastRequestAt = 0;

    public PoliteHttpClient(String userAgent, long requestDelayMillis) {
        this.userAgent = userAgent;
        this.requestDelayMillis = requestDelayMillis;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** Fetch a page the robots.txt of its host permits. Throws if it does not — a blocked path is never read. */
    public String fetch(String url) throws FetchException, InterruptedException {
        URI uri = URI.create(url);
        RobotsTxt robots = robotsForHost(uri);
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        if (!robots.isAllowed(pathAndQuery)) {
            throw new FetchException("robots.txt disallows " + pathAndQuery + " on " + uri.getHost()
                    + " — refusing to fetch (compliance gate, PRD section 14)");
        }
        return rawFetch(uri);
    }

    private RobotsTxt robotsForHost(URI uri) throws FetchException, InterruptedException {
        String host = uri.getHost();
        RobotsTxt cached = robotsByHost.get(host);
        if (cached != null) {
            return cached;
        }
        URI robotsUri = URI.create(uri.getScheme() + "://" + host + "/robots.txt");
        RobotsTxt robots;
        try {
            robots = RobotsTxt.parse(rawFetch(robotsUri));
        } catch (FetchException e) {
            // No readable robots.txt: treat the host as closed rather than open.
            throw new FetchException("could not read robots.txt for " + host + "; refusing to crawl", e);
        }
        robotsByHost.put(host, robots);
        return robots;
    }

    private synchronized String rawFetch(URI uri) throws FetchException, InterruptedException {
        long sinceLast = System.currentTimeMillis() - lastRequestAt;
        if (sinceLast < requestDelayMillis) {
            Thread.sleep(requestDelayMillis - sinceLast);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.5")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            lastRequestAt = System.currentTimeMillis();
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                // One conservative retry after a long pause, then give up loudly.
                Thread.sleep(Math.max(requestDelayMillis * 5, 10_000));
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                lastRequestAt = System.currentTimeMillis();
                status = response.statusCode();
            }
            if (status != 200) {
                throw new FetchException("HTTP " + status + " for " + uri);
            }
            return response.body();
        } catch (IOException e) {
            throw new FetchException("fetch failed for " + uri + ": " + e.getMessage(), e);
        }
    }
}
