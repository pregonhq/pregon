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
 * Guards the ERROR dispatcher rule of {@link SecurityConfiguration} on its own. Problem Details
 * are turned off here so the container must re-dispatch the 404 to /error, the path every error
 * outside Spring MVC takes. Without the rule that dispatch is anonymous and becomes a 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.mvc.problemdetails.enabled=false")
class ErrorDispatchTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApiKeys apiKeys;

    @Test
    void anErrorReDispatchedToTheErrorPageKeepsItsStatus() throws Exception {
        String key = apiKeys.create("default", "error dispatch", Set.of(ApiKeyScope.ADMIN)).value();

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/nothing-here"))
                        .header("Authorization", "Bearer " + key).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
