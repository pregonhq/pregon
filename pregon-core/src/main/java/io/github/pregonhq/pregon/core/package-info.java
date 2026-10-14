/**
 * Framework-agnostic domain and audio pipeline of Pregón.
 *
 * <p>Hexagonal: the domain talks to the outside world only through the ports in
 * {@code io.github.pregonhq.pregon.spi}. Targets Java 17 (ADR-0021) and never depends on Spring
 * (ADR-0004); the build enforces both.
 */
package io.github.pregonhq.pregon.core;
