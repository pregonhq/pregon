package io.github.pregonhq.pregon.server.admin;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class SourceApiTest extends AdminApiTestSupport {

    @Test
    void createsASourceWithTheDocumentedInteractionDefaults() throws Exception {
        String key = newTenant();
        String locationId = createLocation(key, "Centro");

        postAs(key, "/v1/sources", source(locationId, "\"role\":\"cashier\""))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.role").value("cashier"))
                .andExpect(jsonPath("$.interaction.strategy").value("SILENCE_GAP"))
                .andExpect(jsonPath("$.interaction.gap").value("PT10S"))
                .andExpect(jsonPath("$.interaction.allowedLateness").value("PT2M"))
                .andExpect(jsonPath("$.interaction.minVoicedDuration").value("PT2S"))
                .andExpect(jsonPath("$.interaction.maxDuration").value("PT5M"))
                .andExpect(jsonPath("$.audio").isMap());
    }

    @Test
    void fillsOnlyTheMissingInteractionSettings() throws Exception {
        String key = newTenant();
        String locationId = createLocation(key, "Banco");

        postAs(key, "/v1/sources", source(locationId,
                "\"interaction\":{\"strategy\":\"SILENCE_GAP\",\"gap\":\"PT45S\",\"maxDuration\":\"PT40M\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.interaction.gap").value("PT45S"))
                .andExpect(jsonPath("$.interaction.maxDuration").value("PT40M"))
                .andExpect(jsonPath("$.interaction.allowedLateness").value("PT2M"));
    }

    @Test
    void reportsInvalidSourceFields() throws Exception {
        String key = newTenant();
        String locationId = createLocation(key, "Centro");

        postAs(key, "/v1/sources", """
                {"name":"X","locationId":"%s","kind":"CAMERA","role":"Cashier!","operatingHours":[],
                 "interaction":{"strategy":"POS_TICKET","gap":"10 seconds","maxDuration":"PT0S"}}""".formatted(locationId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].pointer").value(hasItems("/kind", "/role", "/operatingHours",
                        "/interaction/strategy", "/interaction/gap", "/interaction/maxDuration")));
        postAs(key, "/v1/sources", source(locationId,
                "\"interaction\":{\"strategy\":\"SILENCE_GAP\",\"gap\":\"PT1M\",\"maxDuration\":\"PT30S\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].detail").value("must be longer than gap"));
    }

    @Test
    void cannotAddSourcesToAnArchivedLocationNorMoveASource() throws Exception {
        String key = newTenant();
        String first = createLocation(key, "Primera");
        String second = createLocation(key, "Segunda");
        String sourceId = read(postAs(key, "/v1/sources", source(first, null)).andReturn(), "$.id");

        putAs(key, "/v1/sources/" + sourceId, "\"1\"", source(second, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].pointer").value("/locationId"));
        postAs(key, "/v1/locations/" + second + "/archive", null).andExpect(status().isOk());
        postAs(key, "/v1/sources", source(second, null)).andExpect(status().isConflict());
    }

    @Test
    void replacesWithETagAndFiltersByLocation() throws Exception {
        String key = newTenant();
        String a = createLocation(key, "A");
        String b = createLocation(key, "B");
        String sourceId = read(postAs(key, "/v1/sources", source(a, null)).andReturn(), "$.id");
        postAs(key, "/v1/sources", source(b, null));

        putAs(key, "/v1/sources/" + sourceId, "\"1\"", source(a, "\"role\":\"advisor\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.role").value("advisor"));
        getAs(key, "/v1/sources?locationId=" + a)
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(sourceId));
        getAs(key, "/v1/sources").andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void anotherTenantsSourceDoesNotExistForTheCaller() throws Exception {
        String mine = newTenant();
        String theirs = newTenant();
        String theirSource = read(postAs(theirs, "/v1/sources", source(createLocation(theirs, "T"), null)).andReturn(), "$.id");

        getAs(mine, "/v1/sources/" + theirSource).andExpect(status().isNotFound());
        postAs(mine, "/v1/sources/" + theirSource + "/archive", null).andExpect(status().isNotFound());
        getAs(theirs, "/v1/sources/" + theirSource).andExpect(status().isOk());
    }
}
