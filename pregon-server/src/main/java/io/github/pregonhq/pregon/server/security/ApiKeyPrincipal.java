package io.github.pregonhq.pregon.server.security;

/** Who is calling: the key and, through it, the tenant. Requests never name their tenant (ADR-0018). */
public record ApiKeyPrincipal(String keyId, String tenantId) {
}
