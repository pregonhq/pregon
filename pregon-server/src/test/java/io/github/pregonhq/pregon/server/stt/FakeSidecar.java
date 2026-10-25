package io.github.pregonhq.pregon.server.stt;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An HTTP server that plays the sidecar: answers what the test scripted and keeps the last request
 * it received, with its multipart parts split by name, so the test can check what went over the
 * wire.
 */
public final class FakeSidecar implements AutoCloseable {

    public record Part(Map<String, String> headers, byte[] data) {

        String contentType() {
            return headers.get("content-type");
        }
    }

    public record Received(String method, String path, Map<String, String> headers, Map<String, Part> parts) {
    }

    private final HttpServer server;
    private final AtomicReference<Received> last = new AtomicReference<>();
    private volatile Function<Received, Scripted> script = received -> new Scripted(200, Map.of(), "{}");

    public record Scripted(int status, Map<String, String> headers, String body) {
    }

    public FakeSidecar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public void respond(int status, String body) {
        respond(status, Map.of(), body);
    }

    void respond(int status, Map<String, String> headers, String body) {
        script = received -> new Scripted(status, headers, body);
    }

    public void respondWith(Function<Received, Scripted> script) {
        this.script = script;
    }

    public Received lastRequest() {
        return last.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(", ", v)));
        byte[] body = exchange.getRequestBody().readAllBytes();
        Received received = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers,
                headers.containsKey("content-type") && headers.get("content-type").startsWith("multipart/")
                        ? parts(headers.get("content-type"), body) : Map.of());
        last.set(received);
        Scripted scripted = script.apply(received);
        scripted.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        if (!scripted.headers().containsKey("Content-Type")) {
            exchange.getResponseHeaders().add("Content-Type", scripted.status() >= 400 ? "application/problem+json" : "application/json");
        }
        byte[] bytes = scripted.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(scripted.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Splits a multipart/form-data body on its boundary; enough for what the adapter sends. */
    static Map<String, Part> parts(String contentType, byte[] body) {
        String boundary = Arrays.stream(contentType.split(";"))
                .map(String::strip).filter(p -> p.startsWith("boundary=")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no boundary in " + contentType))
                .substring("boundary=".length());
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        Map<String, Part> parts = new LinkedHashMap<>();
        int position = indexOf(body, delimiter, 0);
        while (position >= 0) {
            int start = position + delimiter.length;
            if (start + 2 <= body.length && body[start] == '-' && body[start + 1] == '-') {
                break; // closing delimiter
            }
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), start);
            int next = indexOf(body, delimiter, headersEnd);
            String headerText = new String(body, start + 2, headersEnd - start - 2, StandardCharsets.US_ASCII);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line : headerText.split("\r\n")) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).strip().toLowerCase(), line.substring(colon + 1).strip());
            }
            byte[] data = Arrays.copyOfRange(body, headersEnd + 4, next - 2); // the CRLF before the delimiter is not data
            parts.put(name(headers.get("content-disposition")), new Part(headers, data));
            position = next;
        }
        return parts;
    }

    private static String name(String disposition) {
        return Arrays.stream(disposition.split(";")).map(String::strip).filter(p -> p.startsWith("name="))
                .map(p -> p.substring("name=".length()).replace("\"", "")).findFirst().orElseThrow();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static String json(List<String> results) {
        return "{\"model\":\"fake\",\"results\":[" + String.join(",", results) + "]}";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
