package io.github.pregonhq.pregon.server.stt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pregonhq.pregon.spi.speech.SpeechToText;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** ADR-0020 and ADR-0025 §9: the sidecar is a connection with no default, and an optional one. */
class SttConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SttConfiguration.class);

    @Test
    void withoutAUrlThereIsNoSpeechToTextAndNoHealthComponent() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SpeechToText.class);
            assertThat(context).doesNotHaveBean(HealthIndicator.class);
        });
    }

    @Test
    void withAUrlTheAdapterIsWiredWithTheDocumentedDefaults() {
        runner.withPropertyValues("PREGON_STT_URL=http://stt:8000").run(context -> {
            assertThat(context).hasSingleBean(SpeechToText.class);
            assertThat(context.getBean(SpeechToText.class).maxBatchSize()).isEqualTo(16);
            assertThat(context).hasBean("sttHealthIndicator");
        });
    }

    @Test
    void theTunablesAreReadAndValidated() {
        runner.withPropertyValues("PREGON_STT_URL=http://stt:8000", "PREGON_STT_BATCH=64", "PREGON_STT_TIMEOUT=PT90S")
                .run(context -> assertThat(context.getBean(SpeechToText.class).maxBatchSize()).isEqualTo(64));

        runner.withPropertyValues("PREGON_STT_URL=http://stt:8000", "PREGON_STT_BATCH=65")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("PREGON_STT_BATCH").hasMessageContaining("64"));

        runner.withPropertyValues("PREGON_STT_URL=http://stt:8000", "PREGON_STT_TIMEOUT=90")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("PREGON_STT_TIMEOUT").hasMessageContaining("PT5M"));

        runner.withPropertyValues("PREGON_STT_URL=http://stt:8000", "PREGON_STT_TIMEOUT=PT0S")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("positive"));
    }

    @Test
    void aUrlWithoutSchemeOrHostIsRefusedAtStartup() {
        runner.withPropertyValues("PREGON_STT_URL=stt:8000")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("PREGON_STT_URL").hasMessageContaining("http://stt:8000"));
    }
}
