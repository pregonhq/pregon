package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.server.InfrastructureContainers;
import io.github.pregonhq.pregon.server.apikey.ApiKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ingestion is tested over real HTTP: agents talk to a servlet container, and some failures
 * (a connection dropped before the response is read) do not exist without one.
 *
 * <p>Each test gets its own tenant with real keys and real sources created through the Admin API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IngestTestSupport {

    static final String BOUNDARY = "pregon-test-boundary";

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    ApiKeys apiKeys;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    /** A tenant with an admin key and an ingest key, and one active source. */
    record Tenant(String adminKey, String ingestKey, String sourceId) {
    }

    Tenant newTenant() throws Exception {
        String tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO tenant (id) VALUES (:id)").param("id", tenant).update();
        String admin = apiKeys.create(tenant, "admin", Set.of(ApiKeyScope.ADMIN)).value();
        String ingest = apiKeys.create(tenant, "agent", Set.of(ApiKeyScope.INGEST)).value();
        return new Tenant(admin, ingest, newSource(admin));
    }

    String newSource(String adminKey) throws Exception {
        HttpResponse<String> location = postJson(adminKey, "/v1/locations", """
                {"name":"Centro","timezone":"America/Mexico_City","shifts":
                 [{"name":"Morning","windows":[{"days":["MONDAY"],"start":"06:00","end":"14:00"}]}]}""");
        assertThat(location.statusCode()).isEqualTo(201);
        HttpResponse<String> source = postJson(adminKey, "/v1/sources", """
                {"name":"Main counter","locationId":"%s","kind":"MICROPHONE",
                 "operatingHours":[{"days":["MONDAY"],"start":"08:00","end":"20:00"}]}"""
                .formatted(JsonPath.read(location.body(), "$.id").toString()));
        assertThat(source.statusCode()).isEqualTo(201);
        return JsonPath.read(source.body(), "$.id");
    }

    void archiveSource(String adminKey, String sourceId) throws Exception {
        assertThat(postJson(adminKey, "/v1/sources/" + sourceId + "/archive", null).statusCode()).isEqualTo(200);
    }

    /** Metadata of a 10-second segment that ended a moment ago, by a well-synchronized clock. */
    static String metadata(String sourceId) {
        Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return metadata(sourceId, end.minusSeconds(10), end);
    }

    static String metadata(String sourceId, Instant start, Instant end) {
        return """
                {"sourceId":"%s","captureStart":"%s","captureEnd":"%s","voicedDuration":"PT6.4S","agentVersion":"test"}"""
                .formatted(sourceId, start, end);
    }

    /** A UUIDv7, as agents generate them (ADR-0022): 48-bit Unix milliseconds, version 7, variant 2. */
    static String segmentId() {
        UUID random = UUID.randomUUID();
        long msb = (System.currentTimeMillis() << 16) | 0x7000L | (random.getMostSignificantBits() & 0x0FFFL);
        return new UUID(msb, random.getLeastSignificantBits()).toString();
    }

    static byte[] multipart(String metadata, byte[] audio) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (metadata != null) {
            body.writeBytes(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"metadata\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + metadata + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (audio != null) {
            body.writeBytes(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"audio\"; filename=\"segment.ogg\"\r\n"
                    + "Content-Type: audio/ogg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(audio);
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    HttpResponse<String> putSegment(String key, String segmentId, String metadata, byte[] audio) throws Exception {
        return putSegment(key, segmentId, multipart(metadata, audio));
    }

    HttpResponse<String> putSegment(String key, String segmentId, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/v1/segments/" + segmentId))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Opens an upload and sends only the first {@code bytesToSend} of its body, then leaves the
     * socket open: a slow or stalled agent from the server's point of view.
     */
    Socket openPartialUpload(String key, String segmentId, byte[] body, int bytesToSend) throws Exception {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write(uploadHeaders(key, segmentId, body.length));
        out.write(body, 0, bytesToSend);
        out.flush();
        return socket;
    }

    byte[] uploadHeaders(String key, String segmentId, long contentLength) {
        return ("PUT /v1/segments/" + segmentId + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + (key == null ? "" : "Authorization: Bearer " + key + "\r\n")
                + "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** The status code of the first response line on a raw socket. */
    static int readStatus(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            line.append((char) c);
        }
        assertThat(line.toString()).as("status line").startsWith("HTTP/1.1 ");
        return Integer.parseInt(line.substring(9, 12));
    }

    HttpResponse<String> postJson(String key, String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    static String json(HttpResponse<String> response, String path) {
        return JsonPath.read(response.body(), path).toString();
    }

    /**
     * A missing endpoint is also a 404: tests that expect one first prove the endpoint answers,
     * so they cannot pass before ingestion exists.
     */
    void assertUploadsAreServed(Tenant t) throws Exception {
        HttpResponse<String> response = putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), OggOpus.silence(500, 0));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    }

    static void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
    }
}
