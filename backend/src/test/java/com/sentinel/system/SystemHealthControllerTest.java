package com.sentinel.system;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemHealthController.class)
class SystemHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsUpWithBuildIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("sentinel"))
                .andExpect(jsonPath("$.timestamp").value(not(blankOrNullString())));
    }

    /**
     * The version is injected through Maven resource filtering of {@code application.yml}.
     * If that filtering ever breaks, the property leaks the raw {@code @project.version@}
     * placeholder instead of failing loudly — so assert on the resolved value directly.
     */
    @Test
    void reportsAResolvedBuildVersion() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(not(blankOrNullString())))
                .andExpect(jsonPath("$.version").value(not(startsWith("@"))));
    }
}
