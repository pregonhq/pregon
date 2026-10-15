package io.github.pregonhq.pregon.server.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.server.InfrastructureContainers;
import io.github.pregonhq.pregon.server.apikey.ApiKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Each test gets its own tenant and admin key, so tests never see each other's data. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AdminApiTestSupport {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApiKeys apiKeys;

    @Autowired
    JdbcClient jdbc;

    /** A fresh tenant with an admin key; returns the key. */
    String newTenant() {
        String tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO tenant (id) VALUES (:id)").param("id", tenant).update();
        return apiKeys.create(tenant, "admin", Set.of(ApiKeyScope.ADMIN)).value();
    }

    ResultActions getAs(String key, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + key));
    }

    ResultActions postAs(String key, String path, String json) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json));
    }

    ResultActions putAs(String key, String path, String ifMatch, String json) throws Exception {
        var request = put(path).header("Authorization", "Bearer " + key).contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mockMvc.perform(request);
    }

    static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }

    static final String SHIFTS = """
            [{"name":"Morning","windows":[{"days":["MONDAY","TUESDAY"],"start":"06:00","end":"14:00"}]},
             {"name":"Evening","windows":[{"days":["MONDAY","TUESDAY"],"start":"14:00","end":"22:00"}]}]""";

    static String location(String name) {
        return "{\"name\":\"" + name + "\",\"timezone\":\"America/Mexico_City\",\"shifts\":" + SHIFTS + "}";
    }

    String createLocation(String key, String name) throws Exception {
        return read(postAs(key, "/v1/locations", location(name)).andReturn(), "$.id");
    }

    static String source(String locationId, String extra) {
        return "{\"name\":\"Main counter\",\"locationId\":\"" + locationId + "\",\"kind\":\"MICROPHONE\","
                + "\"operatingHours\":[{\"days\":[\"MONDAY\"],\"start\":\"08:00\",\"end\":\"20:00\"}]"
                + (extra == null ? "" : "," + extra) + "}";
    }
}
