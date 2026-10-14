# ADR-0008: Single language in MVP, multilingual-ready
Status: Accepted

## Decision
One audio language per installation (`PREGON_LANGUAGE`, default `es`), fixed in Whisper.
To keep the door open: language is configuration only, `language` field stored on campaigns and
events, text normalization behind an interface (Spanish implementation only). Use multilingual
Whisper models (not `.en`). Full multilingual design (per-location lists, detection once per
interaction, phrases per language) is a later phase. Audio language ≠ UI language (i18n).
