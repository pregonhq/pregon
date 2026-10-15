package io.github.pregonhq.pregon.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LocationApiTest extends AdminApiTestSupport {

    @Test
    void createsAndReadsALocationWithItsETag() throws Exception {
        String key = newTenant();

        var created = postAs(key, "/v1/locations", location("Centro"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", matchesPattern("/v1/locations/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.name").value("Centro"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.shifts[1].windows[0].start").value("14:00"))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z")))
                .andReturn();
        String id = read(created, "$.id");
        assertThat(id.charAt(14)).as("UUID version").isEqualTo('7');

        getAs(key, "/v1/locations/" + id).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() throws Exception {
        String key = newTenant();

        postAs(key, "/v1/locations", """
                {"name":" ","timezone":"Mars/Olympus","shifts":[
                  {"name":"Morning","windows":[{"days":["MONDAY","FUNDAY"],"start":"6:00","end":"14:00"}]}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.errors[*].pointer").value(hasItems(
                        "/name", "/timezone", "/shifts/0/windows/0/days/1", "/shifts/0/windows/0/start")));
    }

    @Test
    void rejectsOverlappingShiftsAndCrossesMidnightCorrectly() throws Exception {
        String key = newTenant();

        postAs(key, "/v1/locations", """
                {"name":"Bar","timezone":"America/Mexico_City","shifts":[
                  {"name":"Night","windows":[{"days":["FRIDAY"],"start":"18:00","end":"02:00"}]},
                  {"name":"Early","windows":[{"days":["SATURDAY"],"start":"01:00","end":"09:00"}]}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("/shifts/1"))
                .andExpect(jsonPath("$.errors[0].detail").value("shifts Night and Early overlap"));
    }

    @Test
    void rejectsUnknownFieldsInsteadOfIgnoringThem() throws Exception {
        String key = newTenant();

        postAs(key, "/v1/locations", "{\"name\":\"A\",\"timezone\":\"UTC\",\"shifts\":[],\"timeZone\":\"x\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void replacingRequiresTheCurrentETag() throws Exception {
        String key = newTenant();
        String path = "/v1/locations/" + createLocation(key, "Centro");

        putAs(key, path, null, location("Centro 2")).andExpect(status().isPreconditionRequired());
        putAs(key, path, "\"7\"", location("Centro 2")).andExpect(status().isPreconditionFailed());
        putAs(key, path, "\"1\"", location("Centro 2"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.name").value("Centro 2"));
        putAs(key, path, "\"1\"", location("Centro 3")).andExpect(status().isPreconditionFailed());
        getAs(key, path).andExpect(jsonPath("$.name").value("Centro 2"));
    }

    @Test
    void pagesWithAnOpaqueCursor() throws Exception {
        String key = newTenant();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(createLocation(key, "L" + i));
        }

        var first = getAs(key, "/v1/locations?limit=2").andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        List<String> seen = new ArrayList<>(List.of(read(first, "$.items[0].id"), read(first, "$.items[1].id")));
        String cursor = read(first, "$.nextCursor");
        var second = getAs(key, "/v1/locations?limit=3&cursor=" + cursor).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist()).andReturn();
        for (int i = 0; i < 3; i++) {
            seen.add(read(second, "$.items[" + i + "].id"));
        }

        assertThat(seen).containsExactlyElementsOf(ids);
        getAs(key, "/v1/locations?cursor=garbage").andExpect(status().isBadRequest());
        getAs(key, "/v1/locations?limit=0").andExpect(status().isBadRequest());
        getAs(key, "/v1/locations?status=DELETED").andExpect(status().isBadRequest());
    }

    @Test
    void archivesOnlyWithoutActiveSourcesAndStaysReadable() throws Exception {
        String key = newTenant();
        String locationId = createLocation(key, "Centro");
        String sourceId = read(postAs(key, "/v1/sources", source(locationId, null)).andReturn(), "$.id");
        String path = "/v1/locations/" + locationId;

        postAs(key, path + "/archive", null).andExpect(status().isConflict());
        postAs(key, "/v1/sources/" + sourceId + "/archive", null).andExpect(status().isOk());
        postAs(key, path + "/archive", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED")).andExpect(header().string("ETag", "\"2\""));
        postAs(key, path + "/archive", null).andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));

        putAs(key, path, "\"2\"", location("Again")).andExpect(status().isConflict());
        getAs(key, path).andExpect(status().isOk());
        getAs(key, "/v1/locations").andExpect(jsonPath("$.items.length()").value(0));
        getAs(key, "/v1/locations?status=ARCHIVED").andExpect(jsonPath("$.items[0].id").value(locationId));
    }

    @Test
    void anotherTenantsLocationDoesNotExistForTheCaller() throws Exception {
        String mine = newTenant();
        String theirs = newTenant();
        String theirLocation = createLocation(theirs, "Theirs");

        getAs(mine, "/v1/locations/" + theirLocation).andExpect(status().isNotFound());
        putAs(mine, "/v1/locations/" + theirLocation, "\"1\"", location("Hijack")).andExpect(status().isNotFound());
        postAs(mine, "/v1/locations/" + theirLocation + "/archive", null).andExpect(status().isNotFound());
        getAs(mine, "/v1/locations").andExpect(jsonPath("$.items.length()").value(0));
        postAs(mine, "/v1/sources", source(theirLocation, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].pointer").value("/locationId"));
        getAs(theirs, "/v1/locations/" + theirLocation).andExpect(jsonPath("$.name").value("Theirs"));
    }
}
