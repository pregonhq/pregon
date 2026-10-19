/**
 * Speech-to-text port (ADR-0012, ADR-0015): the server hands batches of audio segments to a
 * provider and gets one result per segment back. The local faster-whisper sidecar is the default
 * provider; a cloud service is another adapter of the same port.
 *
 * <p>Provider-agnostic by construction: no vendor type appears in these signatures, and the
 * confidence figures of {@link io.github.pregonhq.pregon.spi.speech.SpeechSpan} are optional for
 * providers that do not report them.
 */
package io.github.pregonhq.pregon.spi.speech;
