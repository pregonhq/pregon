package io.github.pregonhq.pregon.server.stt;

import java.net.URI;
import java.time.Duration;

/**
 * The sidecar connection and its two tunables, read once at startup ({@code docs/configuration.md}).
 * Exists as a bean so that the transcription worker can check its own settings against the
 * timeout (ADR-0027: a lease must outlive the longest call made under it).
 */
public record SttProperties(URI baseUrl, Duration timeout, int batch) {
}
