/**
 * Stable contracts that plugins implement: audio sources, detectors, sinks and model providers.
 *
 * <p>This module follows strict semantic versioning (ADR-0004) and targets Java 17 so that any
 * Java 17+ or Android host can embed it (ADR-0021). It must never depend on a framework.
 */
package io.github.pregonhq.pregon.spi;
