-- Transcripts (ADR-0022 "Transcript", ADR-0006, ADR-0027).
--
-- Written by the transcription worker only when PREGON_RETENTION_TRANSCRIPTS is a duration; with
-- the default `none` this table stays empty and the text of a segment exists only for the time it
-- takes the rules to read it. Rows older than the retention are deleted daily: this is the one
-- place where deleting is the feature (privacy), not an accident.
--
-- `segment_id` is a value, not a foreign key: the segment's monthly partition is dropped after
-- PREGON_RETENTION_SEGMENTS, and a transcript may outlive it (ADR-0025 §8). The capture window and
-- the source are copied here for the same reason. UNIQUE (segment_id) completes the guarantee of
-- ADR-0025 §9 that transcribing a segment twice never duplicates its text.
CREATE TABLE transcript (
    tenant_id         text        NOT NULL REFERENCES tenant (id) ON DELETE RESTRICT,
    segment_id        uuid        NOT NULL,
    source_id         uuid        NOT NULL,
    capture_start     timestamptz NOT NULL,
    capture_end       timestamptz NOT NULL,
    language          text        NOT NULL,
    text              text        NOT NULL,
    spans             jsonb       NOT NULL,
    audio_millis      bigint      NOT NULL CHECK (audio_millis >= 0),
    processing_millis bigint      NOT NULL CHECK (processing_millis >= 0),
    transcribed_at    timestamptz NOT NULL,
    PRIMARY KEY (segment_id),
    CHECK (capture_end > capture_start)
);

-- The daily purge scans by age; readers look up one segment (the primary key) or a source's window.
CREATE INDEX transcript_retention_idx ON transcript (transcribed_at);
CREATE INDEX transcript_source_idx ON transcript (tenant_id, source_id, capture_start);
