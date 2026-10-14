# ADR-0023: Business-agnostic domain names and scope
Status: Accepted (amends ADR-0022)

## Context
A review of ADR-0022 against several kinds of business (restaurant, pharmacy, convenience store,
gas station, bank or telecom branch, car dealership, hotel) found the mechanics generic — a target
with phrases and items, versioned, measured as a rate over counted interactions — but four biases
that would turn into breaking changes once an adopter integrates:
- campaigns could only be scoped by location, while many questions apply to one kind of post
  ("did the advisor offer the insurance?" must not count the cashier's interactions);
- the `Offer*` names tied the model to one use, although the same engine serves greeting
  protocols, mandatory disclosures and forbidden phrases, the last with inverted polarity;
- `PRODUCT` / `CATEGORY` encoded a retail taxonomy that the data already expresses;
- interactions grouped only by silence gap break under continuous speech at peak hours.

## Decision
1. **Roles.** A source may carry a `role`: a short tenant-defined label of the post it covers
   (`cashier`, `advisor`, `pump-island`, `pharmacy-counter`). A campaign scope selects locations
   (all, or a list) and optionally roles. Only interactions of sources in scope enter the
   campaign's denominator.
2. **Neutral names and polarity.**
   | ADR-0022 | Now |
   |---|---|
   | OfferTarget | Target |
   | OfferItem | TargetItem |
   | OfferEvent | MatchEvent (CloudEvent type `io.github.pregonhq.match.detected`) |
   | OfferRate, `/v1/kpis/offer-rate` | MatchRate, `/v1/kpis/match-rate` |
   A campaign has an `expectation`: `EXPECTED` (default; offers, greetings, disclosures — higher is
   better) or `FORBIDDEN` (phrases that must not be said — lower is better). The formula does not
   change; the expectation tells clients how to read it. "Offer rate" stays the main use case in
   the documentation.
3. **No target kind.** A target has phrases and optional items. Mentioning one of its phrases or
   any of its items is a match; the MatchEvent records the item when one matched.
4. **Explicit interaction strategy.** Interaction settings carry a `strategy`. `SILENCE_GAP` is the
   only one in the MVP (`POS_TICKET` comes later) and adds `maxDuration`: an interaction longer
   than that is split, so continuous speech at peak hours does not become one endless interaction.
   How the split is made is part of the maintainer-designed interaction grouping.
5. **Extensible enums.** Values that will grow — source kinds (badges, POS, phone), interaction
   strategies, KPI grouping dimensions — are documented as extensible: clients must tolerate
   values they do not know. Closed enums (lifecycle status, campaign state, expectation) stay closed.

Everything else in ADR-0022 stands.

## Consequences
One contract serves many kinds of business and several uses beyond offering, and these changes
land before any adopter depends on the old names. Later additions are non-breaking: a rate of
"all targets matched" for checklists, active time windows per campaign, and a take rate that
crosses matches with POS tickets.
