package io.github.pregonhq.pregon.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "management.endpoint.health.show-components=always")
@AutoConfigureMockMvc
class HealthEndpointTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsUpOnlyWhenDatabaseAndValkeyAreReachable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    void readinessIncludesTheStoresAndLivenessDoesNot() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db").doesNotExist());
    }
}
