package io.github.pregonhq.pregon.server.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The worker's tunables: documented defaults, refused with their name when they make no sense (ADR-0020). */
class TranscriptionPropertiesTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    @Test
    void appliesTheDocumentedDefaults() {
        TranscriptionProperties properties = TranscriptionProperties.from(new MockEnvironment(), TIMEOUT);

        assertThat(properties.language()).isEqualTo("es");
        assertThat(properties.lease()).isEqualTo(Duration.ofMinutes(6));
        assertThat(properties.poll()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.transcriptRetention()).isEmpty();
    }

    @Test
    void readsEveryVariable() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("PREGON_LANGUAGE", "pt")
                .withProperty("PREGON_STT_LEASE", "PT10M")
                .withProperty("PREGON_STT_POLL", "PT0.5S")
                .withProperty("PREGON_RETENTION_TRANSCRIPTS", "P30D")
                .withProperty("PREGON_RETENTION_AUDIO", "delete-after-transcription");

        TranscriptionProperties properties = TranscriptionProperties.from(env, TIMEOUT);

        assertThat(properties.language()).isEqualTo("pt");
        assertThat(properties.lease()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.poll()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.transcriptRetention()).contains(Duration.ofDays(30));
    }

    @Test
    void aLeaseNotLongerThanTheSidecarTimeoutIsRefused() {
        MockEnvironment env = new MockEnvironment().withProperty("PREGON_STT_LEASE", "PT5M");

        assertThatThrownBy(() -> TranscriptionProperties.from(env, TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PREGON_STT_LEASE").hasMessageContaining("PREGON_STT_TIMEOUT");
    }

    @Test
    void refusesALanguageThatIsNotACode() {
        MockEnvironment env = new MockEnvironment().withProperty("PREGON_LANGUAGE", "Spanish");

        assertThatThrownBy(() -> TranscriptionProperties.from(env, TIMEOUT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PREGON_LANGUAGE");
    }

    @Test
    void retentionNoneMeansTranscriptsAreNotStored() {
        MockEnvironment env = new MockEnvironment().withProperty("PREGON_RETENTION_TRANSCRIPTS", "none");

        assertThat(TranscriptionProperties.transcriptRetention(env)).isEmpty();
    }

    @Test
    void refusesARetentionThatIsNotNoneNorADuration() {
        MockEnvironment env = new MockEnvironment().withProperty("PREGON_RETENTION_TRANSCRIPTS", "30 days");

        assertThatThrownBy(() -> TranscriptionProperties.transcriptRetention(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PREGON_RETENTION_TRANSCRIPTS");
    }

    @Test
    void refusesAnAudioRetentionOtherThanDeleteAfterTranscription() {
        MockEnvironment env = new MockEnvironment().withProperty("PREGON_RETENTION_AUDIO", "PT2H");

        assertThatThrownBy(() -> TranscriptionProperties.from(env, TIMEOUT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PREGON_RETENTION_AUDIO");
    }

    @Test
    void refusesAPollThatIsNotPositive() {
        MockEnvironment env = new MockEnvironment().withProperty("PREGON_STT_POLL", "PT0S");

        assertThatThrownBy(() -> TranscriptionProperties.from(env, TIMEOUT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PREGON_STT_POLL");
    }
}
