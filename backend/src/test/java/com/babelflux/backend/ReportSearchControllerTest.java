package com.babelflux.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReportSearchControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void disabledSearchKeepsNoopContractAndNormalizesPaging() throws Exception {
        mockMvc.perform(get("/api/reports/search").param("page", "-2").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void missingIndexJobIsNotConfusedWithAReport() throws Exception {
        mockMvc.perform(get("/api/reports/missing-report/index-status"))
                .andExpect(status().isNotFound());
    }
}
