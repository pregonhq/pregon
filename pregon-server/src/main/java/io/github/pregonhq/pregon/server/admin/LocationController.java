package io.github.pregonhq.pregon.server.admin;

import java.net.URI;
import java.time.Instant;
import java.util.List;
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

/** Locations of the Admin API (api/openapi.yaml). */
@RestController
@RequestMapping("/v1/locations")
class LocationController {

    record LocationInput(String name, String timezone, List<Schedules.ShiftJson> shifts) {
    }

    record Location(UUID id, String name, String timezone, List<Schedules.ShiftJson> shifts, String status,
            Instant createdAt, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Page(List<Location> items, String nextCursor) {
    }

    private final LocationRepository locations;

    LocationController(LocationRepository locations) {
        this.locations = locations;
    }

    @GetMapping
    Page list(@RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int size = Http.limit(limit);
        List<LocationRepository.Row> rows = locations.page(Http.tenantId(), Http.status(status), Http.afterCursor(cursor), size + 1);
        boolean more = rows.size() > size;
        List<LocationRepository.Row> page = more ? rows.subList(0, size) : rows;
        return new Page(page.stream().map(LocationController::toApi).toList(),
                more ? Http.cursor(page.getLast().id()) : null);
    }

    @PostMapping
    ResponseEntity<Location> create(@RequestBody LocationInput input) {
        Valid valid = validate(input);
        LocationRepository.Row row = locations.insert(Http.tenantId(), valid.name, valid.timezone, valid.shifts);
        return ResponseEntity.created(URI.create("/v1/locations/" + row.id())).eTag(Http.etag(row.version())).body(toApi(row));
    }

    @GetMapping("/{locationId}")
    ResponseEntity<Location> get(@PathVariable UUID locationId) {
        return found(locations.find(Http.tenantId(), locationId).orElseThrow(ApiProblems.NotFound::new));
    }

    @PutMapping("/{locationId}")
    @Transactional
    ResponseEntity<Location> replace(@PathVariable UUID locationId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody LocationInput input) {
        long expected = Http.expectedVersion(ifMatch);
        String tenant = Http.tenantId();
        LocationRepository.Row current = locations.find(tenant, locationId).orElseThrow(ApiProblems.NotFound::new);
        if (current.status().equals("ARCHIVED")) {
            throw new ApiProblems.Archived("The location is archived and cannot change.");
        }
        Valid valid = validate(input);
        return found(locations.replace(tenant, locationId, expected, valid.name, valid.timezone, valid.shifts)
                .orElseThrow(ApiProblems.PreconditionFailed::new));
    }

    @PostMapping("/{locationId}/archive")
    @Transactional
    ResponseEntity<Location> archive(@PathVariable UUID locationId) {
        String tenant = Http.tenantId();
        LocationRepository.Row current = locations.find(tenant, locationId).orElseThrow(ApiProblems.NotFound::new);
        if (current.status().equals("ARCHIVED")) {
            return found(current);
        }
        if (locations.hasActiveSources(tenant, locationId)) {
            throw new ApiProblems.Conflict("Archive the sources of this location first.");
        }
        return found(locations.archive(tenant, locationId).orElseThrow(ApiProblems.NotFound::new));
    }

    private record Valid(String name, String timezone, List<Schedules.ShiftJson> shifts) {
    }

    private static Valid validate(LocationInput input) {
        Validation v = new Validation();
        if (input == null) {
            v.add("", "a body is required");
            v.throwIfInvalid();
        }
        String name = v.text("/name", input.name(), 120);
        var timezone = v.timezone("/timezone", input.timezone());
        var shifts = Schedules.shifts(v, "/shifts", input.shifts());
        v.throwIfInvalid();
        return new Valid(name, timezone.getId(), Schedules.shiftsToJson(shifts));
    }

    private static ResponseEntity<Location> found(LocationRepository.Row row) {
        return ResponseEntity.ok().eTag(Http.etag(row.version())).body(toApi(row));
    }

    private static Location toApi(LocationRepository.Row row) {
        return new Location(row.id(), row.name(), row.timezone(), row.shifts(), row.status(),
                row.createdAt().toInstant(), row.updatedAt().toInstant());
    }
}
