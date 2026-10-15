package io.github.pregonhq.pregon.server.admin;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Capture sources of the Admin API (api/openapi.yaml). */
@RestController
@RequestMapping("/v1/sources")
class SourceController {

    /** Kinds accepted today; the enum is extensible in the contract (ADR-0023). */
    static final Set<String> KINDS = Set.of("MICROPHONE", "RTSP", "FILE");

    /**
     * Interaction settings (ADR-0022, ADR-0023). The defaults are starting points to tune with real
     * counter audio: a 10 s silence separates customers at a counter, 2 min covers agent retries,
     * 2 s of voice excludes a lone "thanks", and 5 min splits continuous speech at peak hours.
     */
    record Interaction(String strategy, String gap, String allowedLateness, String minVoicedDuration, String maxDuration) {
        static final Interaction DEFAULTS = new Interaction("SILENCE_GAP", "PT10S", "PT2M", "PT2S", "PT5M");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SourceInput(String name, String locationId, String kind, String role,
            List<Schedules.WindowJson> operatingHours, Interaction interaction, Map<String, Object> audio) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Source(UUID id, String name, UUID locationId, String kind, String role,
            List<Schedules.WindowJson> operatingHours, Interaction interaction, Map<String, Object> audio,
            String status, Instant createdAt, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Page(List<Source> items, String nextCursor) {
    }

    private final SourceRepository sources;
    private final LocationRepository locations;

    SourceController(SourceRepository sources, LocationRepository locations) {
        this.sources = sources;
        this.locations = locations;
    }

    @GetMapping
    Page list(@RequestParam(required = false) UUID locationId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        int size = Http.limit(limit);
        var rows = sources.page(Http.tenantId(), locationId, Http.status(status), Http.afterCursor(cursor), size + 1);
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        return new Page(page.stream().map(SourceController::toApi).toList(), more ? Http.cursor(page.getLast().id()) : null);
    }

    @PostMapping
    @Transactional
    ResponseEntity<Source> create(@RequestBody SourceInput input) {
        String tenant = Http.tenantId();
        SourceRepository.Fields fields = validate(input);
        requireActiveLocation(tenant, fields.locationId());
        SourceRepository.Row row = sources.insert(tenant, fields);
        return ResponseEntity.created(URI.create("/v1/sources/" + row.id())).eTag(Http.etag(row.version())).body(toApi(row));
    }

    @GetMapping("/{sourceId}")
    ResponseEntity<Source> get(@PathVariable UUID sourceId) {
        return found(sources.find(Http.tenantId(), sourceId).orElseThrow(ApiProblems.NotFound::new));
    }

    @PutMapping("/{sourceId}")
    @Transactional
    ResponseEntity<Source> replace(@PathVariable UUID sourceId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody SourceInput input) {
        long expected = Http.expectedVersion(ifMatch);
        String tenant = Http.tenantId();
        SourceRepository.Row current = sources.find(tenant, sourceId).orElseThrow(ApiProblems.NotFound::new);
        if (current.status().equals("ARCHIVED")) {
            throw new ApiProblems.Archived("The source is archived and cannot change.");
        }
        SourceRepository.Fields fields = validate(input);
        if (!fields.locationId().equals(current.fields().locationId())) {
            throw new ApiProblems.InvalidRequest(List.of(new ApiProblems.FieldError("/locationId",
                    "cannot change; register a new source instead")));
        }
        return found(sources.replace(tenant, sourceId, expected, fields).orElseThrow(ApiProblems.PreconditionFailed::new));
    }

    @PostMapping("/{sourceId}/archive")
    @Transactional
    ResponseEntity<Source> archive(@PathVariable UUID sourceId) {
        String tenant = Http.tenantId();
        SourceRepository.Row current = sources.find(tenant, sourceId).orElseThrow(ApiProblems.NotFound::new);
        if (current.status().equals("ARCHIVED")) {
            return found(current);
        }
        return found(sources.archive(tenant, sourceId).orElseThrow(ApiProblems.NotFound::new));
    }

    private void requireActiveLocation(String tenant, UUID locationId) {
        var location = locations.find(tenant, locationId).orElseThrow(() -> new ApiProblems.InvalidRequest(
                List.of(new ApiProblems.FieldError("/locationId", "no such location"))));
        if (location.status().equals("ARCHIVED")) {
            throw new ApiProblems.Archived("The location is archived; sources cannot be added to it.");
        }
    }

    private static SourceRepository.Fields validate(SourceInput input) {
        Validation v = new Validation();
        if (input == null) {
            v.add("", "a body is required");
            v.throwIfInvalid();
        }
        String name = v.text("/name", input.name(), 120);
        UUID locationId = v.uuid("/locationId", input.locationId());
        if (input.kind() == null || !KINDS.contains(input.kind())) {
            v.add("/kind", "must be one of " + KINDS.stream().sorted().toList());
        }
        if (input.role() != null && !Validation.ROLE.matcher(input.role()).matches()) {
            v.add("/role", "must be lowercase letters, digits and dashes, at most 40 characters");
        }
        var hours = Schedules.windows(v, "/operatingHours", input.operatingHours(), true);
        Interaction interaction = interaction(v, input.interaction());
        v.throwIfInvalid();
        return new SourceRepository.Fields(locationId, name, input.kind(), input.role(), Schedules.toJson(hours),
                interaction, input.audio() == null ? Map.of() : input.audio());
    }

    /** Missing durations take their default; the stored settings are always complete. */
    private static Interaction interaction(Validation v, Interaction given) {
        if (given == null) {
            return Interaction.DEFAULTS;
        }
        if (!"SILENCE_GAP".equals(given.strategy())) {
            v.add("/interaction/strategy", "only SILENCE_GAP is accepted today");
        }
        Interaction d = Interaction.DEFAULTS;
        String gap = duration(v, "/interaction/gap", given.gap(), d.gap());
        String lateness = duration(v, "/interaction/allowedLateness", given.allowedLateness(), d.allowedLateness());
        String minVoiced = duration(v, "/interaction/minVoicedDuration", given.minVoicedDuration(), d.minVoicedDuration());
        String max = duration(v, "/interaction/maxDuration", given.maxDuration(), d.maxDuration());
        if (v.ok() && Duration.parse(max).compareTo(Duration.parse(gap)) <= 0) {
            v.add("/interaction/maxDuration", "must be longer than gap");
        }
        return new Interaction("SILENCE_GAP", gap, lateness, minVoiced, max);
    }

    private static String duration(Validation v, String pointer, String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        Duration parsed = v.positiveDuration(pointer, value);
        return parsed == null ? null : parsed.toString();
    }

    private static ResponseEntity<Source> found(SourceRepository.Row row) {
        return ResponseEntity.ok().eTag(Http.etag(row.version())).body(toApi(row));
    }

    private static Source toApi(SourceRepository.Row row) {
        var f = row.fields();
        return new Source(row.id(), f.name(), f.locationId(), f.kind(), f.role(), f.operatingHours(), f.interaction(),
                f.audio(), row.status(), row.createdAt().toInstant(), row.updatedAt().toInstant());
    }
}
