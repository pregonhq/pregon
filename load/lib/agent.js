// What a capture agent sends and how it behaves (api/openapi.yaml `putSegment`, ADR-0024):
// a UUIDv7 per segment, a multipart body with the metadata and the Ogg/Opus audio, and a retry
// policy that honors Retry-After, backs off with full jitter and never retries a 4xx.

import http from 'k6/http';
import { sleep } from 'k6';

export const BOUNDARY = 'pregon-load-boundary';

const UTF8 = new TextEncoder();

/** UUIDv7 as agents generate them (ADR-0022): 48-bit Unix milliseconds, version 7, variant 2. */
export function segmentId(millis = Date.now()) {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  for (let i = 0; i < 6; i++) {
    bytes[i] = Math.floor(millis / 2 ** (8 * (5 - i))) & 0xff;
  }
  bytes[6] = 0x70 | (bytes[6] & 0x0f);
  bytes[8] = 0x80 | (bytes[8] & 0x3f);
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Metadata of a segment of `seconds` that ended at `end`, by a clock synchronized with NTP. */
export function metadata(sourceId, { end = new Date(), seconds = 10, voiced = 'PT6.4S' } = {}) {
  const start = new Date(end.getTime() - seconds * 1000);
  return JSON.stringify({
    sourceId,
    captureStart: start.toISOString(),
    captureEnd: end.toISOString(),
    voicedDuration: voiced,
    agentVersion: 'k6',
  });
}

/** The multipart body the contract describes: a `metadata` JSON part and an `audio` Ogg/Opus part. */
export function multipart(metadataJson, audio) {
  const head = UTF8.encode(
    `--${BOUNDARY}\r\n` +
      'Content-Disposition: form-data; name="metadata"\r\n' +
      'Content-Type: application/json\r\n\r\n' +
      `${metadataJson}\r\n` +
      `--${BOUNDARY}\r\n` +
      'Content-Disposition: form-data; name="audio"; filename="segment.ogg"\r\n' +
      'Content-Type: audio/ogg\r\n\r\n',
  );
  const tail = UTF8.encode(`\r\n--${BOUNDARY}--\r\n`);
  const body = new Uint8Array(head.length + audio.length + tail.length);
  body.set(head, 0);
  body.set(audio, head.length);
  body.set(tail, head.length + audio.length);
  return body.buffer;
}

/** One `PUT /v1/segments/{id}`; `tags` reach the metrics so first uploads and retries are told apart. */
export function put(baseUrl, ingestKey, id, body, tags = {}) {
  return http.put(`${baseUrl}/v1/segments/${id}`, body, {
    headers: {
      Authorization: `Bearer ${ingestKey}`,
      'Content-Type': `multipart/form-data; boundary=${BOUNDARY}`,
    },
    tags,
    timeout: '60s',
  });
}

/**
 * Uploads one segment the way the agent does: any 2xx is done; a 503 waits for `Retry-After`;
 * other 5xx and network errors back off exponentially with full jitter; a 4xx is never retried.
 * `onAttempt(response, attempt)` sees every response, so the caller can count them.
 */
export function uploadLikeAnAgent(baseUrl, ingestKey, id, body, { maxAttempts = 10, baseBackoff = 0.5, maxBackoff = 30, onAttempt } = {}) {
  for (let attempt = 1; ; attempt++) {
    const response = put(baseUrl, ingestKey, id, body, { kind: attempt === 1 ? 'first' : 'retry' });
    if (onAttempt) {
      onAttempt(response, attempt);
    }
    if (response.status >= 200 && response.status < 300) {
      return { response, attempts: attempt, gaveUp: false };
    }
    if ((response.status >= 400 && response.status < 500) || attempt >= maxAttempts) {
      return { response, attempts: attempt, gaveUp: true };
    }
    let wait = response.status === 503 ? Number(response.headers['Retry-After']) : NaN;
    if (!(wait > 0)) {
      wait = Math.random() * Math.min(maxBackoff, baseBackoff * 2 ** (attempt - 1));
    }
    sleep(wait);
  }
}
