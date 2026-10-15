package io.github.pregonhq.pregon.core.apikey;

/** What an API key may do (ADR-0003, ADR-0018). */
public enum ApiKeyScope {
    /** Capture agents: the Ingest API. */
    INGEST,
    /** Configuration and KPIs: the Admin API. */
    ADMIN
}
