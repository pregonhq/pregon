package io.github.pregonhq.pregon.server;

import java.util.Arrays;

import io.github.pregonhq.pregon.server.apikey.ApiKeys;
import io.github.pregonhq.pregon.server.apikey.KeysCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Excludes {@link UserDetailsServiceAutoConfiguration}: otherwise Spring Boot creates a user with a
 * generated password, a credential nobody asked for. Authentication is API keys only (ADR-0018).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class PregonServerApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("keys")) {
            System.exit(runKeysCommand(args));
        }
        SpringApplication.run(PregonServerApplication.class, args);
    }

    /** Runs {@code keys ...} against the same database as the server, without starting the web server. */
    static int runKeysCommand(String[] args) {
        try (var context = new SpringApplicationBuilder(PregonServerApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties("spring.main.banner-mode=off", "logging.level.root=WARN")
                .run()) {
            var command = new KeysCommand(context.getBean(ApiKeys.class), System.out, System.err);
            return command.run(Arrays.asList(args).subList(1, args.length));
        }
    }
}
