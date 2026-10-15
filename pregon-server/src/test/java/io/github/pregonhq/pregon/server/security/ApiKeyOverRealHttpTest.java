package io.github.pregonhq.pregon.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.server.InfrastructureContainers;
import io.github.pregonhq.pregon.server.apikey.ApiKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Behavior that MockMvc cannot show because it has no servlet container: an error inside an
 * authenticated request is re-dispatched to /error by the container, and must keep its status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyOverRealHttpTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApiKeys apiKeys;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anAuthenticatedRequestToAMissingEndpointIsA404ProblemNotA401() throws Exception {
        String key = apiKeys.create("default", "real http", Set.of(ApiKeyScope.ADMIN)).value();

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/nothing-here"))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    @Test
    void withoutAKeyTheSameRequestIsStillA401() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/nothing-here"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
