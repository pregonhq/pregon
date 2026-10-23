// Load test of segment ingestion (ADR-0024, ADR-0025): the evidence behind every performance
// change. Two scenarios, picked with SCENARIO:
//
//   steady  The load model of docs/capacity.md: SOURCES active sources, each uploading a 10 s
//           segment every ~14 s (0.07 uploads/s), for DURATION. A share of uploads (RETRY_RATIO)
//           is sent twice, as an agent does when the connection drops before it reads the
//           response, and must get the original receipt back (200). Latency thresholds apply.
//   storm   Every agent flushes its RAM buffer at once after an outage (docs/capacity.md,
//           "Bursts"): SOURCES agents, BUFFER segments each, as fast as the server lets them,
//           honoring Retry-After. Measures how long the storm lasts, how many 503 it takes and
//           that no segment is lost or rejected.
//
// Connections and keys have no defaults (ADR-0020): PREGON_URL, PREGON_ADMIN_KEY and
// PREGON_INGEST_KEY are required. load/run.sh sets them for the local Compose stack.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { segment } from './lib/oggopus.js';
import { metadata, multipart, put, segmentId, uploadLikeAnAgent } from './lib/agent.js';

const BASE_URL = required('PREGON_URL').replace(/\/$/, '');
const ADMIN_KEY = required('PREGON_ADMIN_KEY');
const INGEST_KEY = required('PREGON_INGEST_KEY');

const SCENARIO = __ENV.SCENARIO || 'steady';
const SOURCES = integer('SOURCES', 100);
const DURATION = __ENV.DURATION || (SCENARIO === 'storm' ? '30m' : '5m');
const RETRY_RATIO = number('RETRY_RATIO', 0.05);
const BUFFER = integer('BUFFER', 300);
const SEGMENT_SECONDS = number('SEGMENT_SECONDS', 10);
const OPUS_KBPS = number('OPUS_KBPS', 24);
const POOL_SIZE = integer('POOL', 16);
const P95_MS = number('P95_MS', 250);
const P99_MS = number('P99_MS', 1000);
const UPLOADS_PER_SOURCE_PER_100S = 7; // 0.07 uploads/s per active source (docs/capacity.md)

// Each VU builds a small pool of distinct segments once; hashing cost on the server is the same
// whether or not two uploads share bytes, and generating 30 KB per upload in JS would be noise.
const POOL = Array.from({ length: POOL_SIZE }, (_, i) => segment({ seconds: SEGMENT_SECONDS, kbps: OPUS_KBPS, seed: i + 1 }));

const accepted = new Counter('uploads_accepted'); // 202
const duplicates = new Counter('uploads_duplicate'); // 200
const throttled = new Counter('uploads_throttled'); // 503
const rejected = new Counter('uploads_rejected'); // 4xx
const failed = new Counter('uploads_failed'); // other 5xx and network errors
const givenUp = new Counter('uploads_given_up');
const skewFlagged = new Counter('receipts_skew_flagged');
const retryAfter = new Trend('retry_after_seconds');
const attemptsPerSegment = new Trend('attempts_per_segment');
const flushSeconds = new Trend('buffer_flush_seconds', true);

const SCENARIOS = {
  steady: {
    executor: 'constant-arrival-rate',
    rate: SOURCES * UPLOADS_PER_SOURCE_PER_100S,
    timeUnit: '100s',
    duration: DURATION,
    preAllocatedVUs: Math.max(5, Math.ceil(SOURCES / 10)),
    maxVUs: Math.max(20, SOURCES),
    exec: 'steady',
  },
  storm: {
    executor: 'per-vu-iterations',
    vus: SOURCES,
    iterations: 1,
    maxDuration: DURATION,
    exec: 'storm',
  },
};

const THRESHOLDS = {
  steady: {
    'http_req_duration{kind:first}': [`p(95)<${P95_MS}`, `p(99)<${P99_MS}`],
    'http_req_duration{kind:retry}': [`p(95)<${P95_MS}`],
    checks: ['rate>0.999'],
    uploads_rejected: ['count==0'],
    uploads_failed: ['count==0'],
  },
  storm: {
    uploads_given_up: ['count==0'],
    uploads_rejected: ['count==0'],
    uploads_failed: ['count==0'],
  },
};

if (!SCENARIOS[SCENARIO]) {
  throw new Error(`SCENARIO must be one of ${Object.keys(SCENARIOS).join(', ')}, not '${SCENARIO}'`);
}

export const options = {
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] },
  thresholds: THRESHOLDS[SCENARIO],
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  // Tags that would otherwise split every metric by URL (one per segment id).
  systemTags: ['status', 'method', 'scenario', 'check', 'error_code', 'expected_response'],
};

/** One location and SOURCES sources for this run, created through the Admin API as an adopter would. */
export function setup() {
  const readiness = http.get(`${BASE_URL}/actuator/health/readiness`);
  if (readiness.status !== 200) {
    fail(`server not ready at ${BASE_URL}: ${readiness.status} ${readiness.body}`);
  }
  const runId = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15);
  const location = admin('POST', '/v1/locations', {
    name: `load ${runId}`,
    timezone: 'America/Mexico_City',
    shifts: [{ name: 'Morning', windows: [{ days: ['MONDAY'], start: '06:00', end: '14:00' }] }],
  });
  if (location.status !== 201) {
    fail(`could not create the location: ${location.status} ${location.body}`);
  }
  const locationId = location.json('id');
  const sources = [];
  for (let from = 0; from < SOURCES; from += 50) {
    const batch = [];
    for (let i = from; i < Math.min(SOURCES, from + 50); i++) {
      batch.push(adminRequest('POST', '/v1/sources', {
        name: `load ${runId} source ${i + 1}`,
        locationId,
        kind: 'MICROPHONE',
        operatingHours: [{ days: ['MONDAY'], start: '08:00', end: '20:00' }],
      }));
    }
    for (const response of http.batch(batch)) {
      if (response.status !== 201) {
        fail(`could not create a source: ${response.status} ${response.body}`);
      }
      sources.push(response.json('id'));
    }
  }
  console.log(`run ${runId}: ${sources.length} sources in location ${locationId}, scenario ${SCENARIO}`);
  return { runId, locationId, sources };
}

/** Nothing is deleted: the sources and the location of this run are archived. */
export function teardown(data) {
  for (let from = 0; from < data.sources.length; from += 50) {
    http.batch(data.sources.slice(from, from + 50).map((id) => adminRequest('POST', `/v1/sources/${id}/archive`, null)));
  }
  admin('POST', `/v1/locations/${data.locationId}/archive`, null);
}

export function steady(data) {
  const sourceId = data.sources[Math.floor(Math.random() * data.sources.length)];
  const id = segmentId();
  const body = multipart(metadata(sourceId, { seconds: SEGMENT_SECONDS }), POOL[__ITER % POOL.length]);

  const first = put(BASE_URL, INGEST_KEY, id, body, { kind: 'first' });
  record(first);
  check(first, { 'first upload is accepted (202)': (r) => r.status === 202 });

  if (Math.random() < RETRY_RATIO) {
    const retry = put(BASE_URL, INGEST_KEY, id, body, { kind: 'retry' });
    record(retry);
    check(retry, {
      'retry returns the original receipt (200)': (r) => r.status === 200 && r.json('segmentId') === id,
    });
  }
}

export function storm(data) {
  const sourceId = data.sources[(__VU - 1) % data.sources.length];
  const started = Date.now();
  // The buffer filled up during the outage: the segments ended one after another in the past,
  // and their ids carry that time (UUIDv7), so the server receives them in capture order.
  for (let i = 0; i < BUFFER; i++) {
    const end = new Date(started - (BUFFER - i) * SEGMENT_SECONDS * 1000);
    const body = multipart(metadata(sourceId, { end, seconds: SEGMENT_SECONDS }), POOL[i % POOL.length]);
    const outcome = uploadLikeAnAgent(BASE_URL, INGEST_KEY, segmentId(end.getTime()), body, { onAttempt: record });
    attemptsPerSegment.add(outcome.attempts);
    if (outcome.gaveUp) {
      givenUp.add(1);
    }
  }
  flushSeconds.add(Date.now() - started);
}

function record(response) {
  if (response.status === 202) {
    accepted.add(1);
  } else if (response.status === 200) {
    duplicates.add(1);
  } else if (response.status === 503) {
    throttled.add(1);
    const seconds = Number(response.headers['Retry-After']);
    if (seconds > 0) {
      retryAfter.add(seconds);
    }
  } else if (response.status >= 400 && response.status < 500) {
    rejected.add(1);
  } else {
    failed.add(1);
  }
  if ((response.status === 202 || response.status === 200) && response.json('clockSkewSuspected') === true) {
    skewFlagged.add(1);
  }
}

function admin(method, path, json) {
  const request = adminRequest(method, path, json);
  return http.request(request.method, request.url, request.body, request.params);
}

function adminRequest(method, path, json) {
  return {
    method,
    url: `${BASE_URL}${path}`,
    body: json === null ? null : JSON.stringify(json),
    params: { headers: { Authorization: `Bearer ${ADMIN_KEY}`, 'Content-Type': 'application/json' }, tags: { kind: 'admin' } },
  };
}

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required (see load/README.md)`);
  }
  return value;
}

function integer(name, fallback) {
  const value = number(name, fallback);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(`${name} must be a positive integer, not '${__ENV[name]}'`);
  }
  return value;
}

function number(name, fallback) {
  if (__ENV[name] === undefined || __ENV[name] === '') {
    return fallback;
  }
  const value = Number(__ENV[name]);
  if (!Number.isFinite(value)) {
    throw new Error(`${name} must be a number, not '${__ENV[name]}'`);
  }
  return value;
}
