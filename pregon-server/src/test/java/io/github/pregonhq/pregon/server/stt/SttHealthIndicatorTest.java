package io.github.pregonhq.pregon.server.stt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class SttHealthIndicatorTest {

    private FakeSidecar sidecar;
    private SttHealthIndicator indicator;

    @BeforeEach
    void start() throws IOException {
        sidecar = new FakeSidecar();
        indicator = new SttHealthIndicator(sidecar.baseUrl(), HttpClient.newHttpClient());
    }

    @AfterEach
    void stop() {
        sidecar.close();
    }

    @Test
    void upWithTheModelWhenTheSidecarIsReady() {
        sidecar.respond(200, "{\"status\":\"UP\",\"model\":\"small\",\"device\":\"cpu\",\"computeType\":\"int8\"}");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("model", "small").containsEntry("device", "cpu").containsEntry("computeType", "int8");
        assertThat(sidecar.lastRequest().path()).isEqualTo("/health");
    }

    @Test
    void downWithTheSidecarsReasonWhileTheModelLoads() {
        sidecar.respond(503, "{\"status\":\"DOWN\",\"model\":\"small\",\"device\":\"cpu\",\"computeType\":\"int8\",\"detail\":\"loading model\"}");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("detail", "loading model").containsEntry("httpStatus", 503);
    }

    @Test
    void downWhenTheSidecarIsUnreachable() {
        sidecar.close();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error").toString()).startsWith("unreachable");
    }

    @Test
    void downWhenSomethingElseAnswersOnThatPort() {
        sidecar.respond(200, "<html>not the sidecar</html>");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
