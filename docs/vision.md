# Vision and scope

## Problem
Businesses launch seasonal products and campaigns but cannot measure whether staff actually
offer them at the counter. Existing solutions are expensive, cloud-only, or invasive.

## What Pregón is
An open, embeddable engine that turns live business signals into events and KPIs:
cheap by default, private by design, usable by many different software products
(POS systems, restaurant SaaS, retail apps) as a module.

## First use case (MVP)
Counter offer analysis: capture counter audio live, keep only speech, transcribe locally,
detect mentions of campaign products, and compute an offer-rate KPI per source and shift.

## Input sources (target)
- MVP: microphone, RTSP stream (IP cameras with audio), WAV replay (testing).
- Later: Android/Windows host apps via SDK (tablet in kiosk mode for continuous capture),
  POS events, webhooks.
- Known limits: voice assistants (e.g. Alexa) do not allow continuous ambient capture — they fit
  as query/command interfaces only. iOS restricts background capture.

## Non-goals
- Identifying people (face or voice biometrics).
- Emotion, attitude or "behavior" scoring of employees.
- Storing raw audio or video.

## Privacy stance
Raw audio exists only in RAM and in the transient queue until transcribed, then it is deleted.
Default storage is business events only; transcripts are optional with configurable retention.
Capture runs only during configured operating hours. Adopters are responsible for legal notices
and consent in their jurisdiction (e.g. Mexico's personal data law, GDPR and the EU AI Act).
Documentation must state this clearly.

## Domain glossary
Defined in [ADR-0022](adr/0022-domain-model.md); short version:
- **Tenant**: the business using Pregón (`default` in single-tenant installs).
- **Location**: a branch or store. Has a timezone and named shifts.
- **Source**: a capture point (a counter mic, an RTSP stream). Has operating hours.
- **Campaign**: what to measure, seasonal (with an end date) or permanent. Has a language.
- **Offer target**: what counts as an offer: a product ("pan de muerto") or a category
  ("drinks") with items ("agua de jamaica", "café").
- **Segment**: a speech fragment produced by the agent. Transient.
- **Transcript**: text of a segment. Optional storage.
- **Interaction**: one customer attention, grouped by silence gap on capture time (MVP) or POS
  ticket (later).
- **Offer event**: a detected mention of an offer target within an interaction.
- **KPI**: computed on demand, e.g. offer rate = interactions with offer / counted interactions.
