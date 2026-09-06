package com.babelflux.backend;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SessionControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void createsSessionAndListsHistory() throws Exception {
        mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"sourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").isString()).andExpect(jsonPath("$.wsToken").isString());
        mockMvc.perform(get("/api/sessions/history")).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void healthIsAvailable() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }
}
