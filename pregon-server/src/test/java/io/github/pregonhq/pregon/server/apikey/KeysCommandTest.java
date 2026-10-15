package io.github.pregonhq.pregon.server.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.pregonhq.pregon.core.apikey.ApiKeyAuthenticator;
import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.server.InfrastructureContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KeysCommandTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @Autowired
    private ApiKeys apiKeys;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void createPrintsOnlyTheKeyOnStandardOutputAndTheKeyWorks() {
        int exit = run("create", "--scope", "ingest", "--scope", "admin", "--name", "counter agent");

        assertThat(exit).isZero();
        String key = out().strip();
        assertThat(key).matches("pgn_[0-9A-Za-z]{8}_[0-9A-Za-z]{43}");
        assertThat(err()).contains("cannot be shown again").doesNotContain(key.substring(13));
        var result = new ApiKeyAuthenticator(apiKeys).authenticate(key);
        assertThat(result).isInstanceOfSatisfying(ApiKeyAuthenticator.Result.Authenticated.class, authenticated -> {
            assertThat(authenticated.tenantId()).isEqualTo("default");
            assertThat(authenticated.scopes()).containsExactlyInAnyOrder(ApiKeyScope.INGEST, ApiKeyScope.ADMIN);
        });
    }

    @Test
    void listNeverShowsSecrets() {
        run("create", "--scope", "admin", "--name", "listed");
        String key = out().strip();
        out.reset();

        assertThat(run("list")).isZero();
        assertThat(out()).contains(key.substring(4, 12)).contains("listed").doesNotContain(key.substring(13));
    }

    @Test
    void revokedKeysStopAuthenticating() {
        run("create", "--scope", "admin", "--name", "to revoke");
        String key = out().strip();

        assertThat(run("revoke", key.substring(4, 12))).isZero();
        assertThat(new ApiKeyAuthenticator(apiKeys).authenticate(key))
                .isInstanceOf(ApiKeyAuthenticator.Result.Rejected.class);
    }

    @Test
    void rejectsInvalidUsageWithoutCreatingAnything() {
        assertThat(run()).isEqualTo(2);
        assertThat(run("create", "--name", "no scope")).isEqualTo(2);
        assertThat(run("create", "--scope", "root", "--name", "bad scope")).isEqualTo(2);
        assertThat(run("revoke", "AAAAAAAA")).isEqualTo(1);
        assertThat(out()).isEmpty();
    }

    private int run(String... args) {
        var command = new KeysCommand(apiKeys, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return command.run(List.of(args));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
