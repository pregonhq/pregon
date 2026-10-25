package io.github.pregonhq.pregon.server.transcription;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import io.github.pregonhq.pregon.server.stt.SttProperties;
import io.github.pregonhq.pregon.spi.speech.SpeechToText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires transcripts and the worker (ADR-0027). The store and the daily purge exist in every
 * server: a transcript written while retention was on must still expire after it is turned off.
 * The worker exists only in a web server with a sidecar configured: {@code PREGON_STT_URL} is
 * the switch (ADR-0025 §9), and the {@code pregon keys} command must not drain anything.
 */
@Configuration(proxyBeanMethods = false)
class TranscriptionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionConfiguration.class);

    @Bean
    TranscriptStore transcriptStore(JdbcClient jdbc, Clock clock, JsonMapper json) {
        return new TranscriptStore(jdbc, clock, json);
    }

    @Bean
    TranscriptRetention transcriptRetention(TranscriptStore store, Clock clock, Environment environment) {
        return new TranscriptRetention(store, clock, TranscriptionProperties.transcriptRetention(environment));
    }

    /** Once a day, transcripts older than the retention go; with retention {@code none} there is nothing to purge. */
    static final class TranscriptRetention {

        private final TranscriptStore store;
        private final Clock clock;
        private final Optional<Duration> retention;

        TranscriptRetention(TranscriptStore store, Clock clock, Optional<Duration> retention) {
            this.store = store;
            this.clock = clock;
            this.retention = retention;
        }

        @Scheduled(fixedRateString = "P1D", initialDelayString = "PT1H")
        void daily() {
            purge();
        }

        int purge() {
            if (retention.isEmpty()) {
                return 0;
            }
            int purged = store.purgeOlderThan(clock.instant().minus(retention.get()));
            if (purged > 0) {
                log.info("purged {} transcripts older than {}", purged, retention.get());
            }
            return purged;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    @ConditionalOnProperty("PREGON_STT_URL")
    static class Worker {

        @Bean
        TranscriptionProperties transcriptionProperties(Environment environment, SttProperties stt) {
            return TranscriptionProperties.from(environment, stt.timeout());
        }

        @Bean
        TranscriptionWorker transcriptionWorker(SegmentQueue queue, SpeechToText speechToText, TranscriptStore store,
                @Qualifier("sttHealthIndicator") HealthIndicator sttHealth, TranscriptionProperties properties, Clock clock) {
            TranscriptionWorker.TranscriptSink sink = properties.transcriptRetention().isPresent()
                    ? store : TranscriptionWorker.TranscriptSink.NONE;
            return new TranscriptionWorker(queue, speechToText, sink, () -> Status.UP.equals(sttHealth.health().getStatus()),
                    properties, clock);
        }
    }
}
