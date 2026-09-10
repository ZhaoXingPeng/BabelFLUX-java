package com.babelflux.backend;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "babelflux.infrastructure.mysql-enabled=true")
@AutoConfigureMockMvc
class SessionControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired com.babelflux.backend.service.SessionService sessions;

    @Test
    void createsSessionAndListsHistory() throws Exception {
        String response = mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"sourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").isString()).andExpect(jsonPath("$.wsToken").isString())
                .andReturn().getResponse().getContentAsString();
        String sessionId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("sessionId").asText();
        mockMvc.perform(get("/api/sessions/history")).andExpect(status().isOk()).andExpect(jsonPath("$.items[*].sessionId", hasItem(sessionId)));
    }

    @Test
    void preservesExtendedSessionConfiguration() throws Exception {
        String response = mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputMode\":\"browser_audio\",\"sourceLanguage\":\"auto\","
                                + "\"targetLanguage\":\"zh\",\"sourceUrl\":\"https://example.test/live\","
                                + "\"sourcePermission\":\"granted\",\"ttsEnabled\":true,"
                                + "\"glossary\":[{\"sourceTerm\":\"API\",\"targetTerm\":\"接口\","
                                + "\"priority\":4}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("sessionId").asText();
        var session = sessions.get(sessionId);
        org.junit.jupiter.api.Assertions.assertEquals("https://example.test/live", session.getSourceUrl());
        org.junit.jupiter.api.Assertions.assertEquals("granted", session.getSourcePermission());
        org.junit.jupiter.api.Assertions.assertTrue(session.isTtsEnabled());
        org.junit.jupiter.api.Assertions.assertEquals("接口", session.getGlossary().getFirst().targetTerm());
    }

    @Test
    void rejectsUnknownInputModeBeforePersistingSession() throws Exception {
        mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputMode\":\"python_pipeline\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("inputMode")));
    }

    @Test
    void healthIsAvailable() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void exposesStrategyPlanWithCamelCaseContract() throws Exception {
        mockMvc.perform(post("/api/models/strategy/plan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceLanguage\":\"en\",\"targetLanguage\":\"zh\","
                                + "\"domain\":\"技术\",\"ttsEnabled\":true,\"glossary\":[{"
                                + "\"sourceTerm\":\"API\",\"targetTerm\":\"接口\",\"priority\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryProvider").value("qwen_live_translate"))
                .andExpect(jsonPath("$.ttsProvider").value("qwen_tts"))
                .andExpect(jsonPath("$.realtimeRevisionPolicy.windowSegments").value(4))
                .andExpect(jsonPath("$.liveTranslateSession.event.session.modalities[1]").value("audio"))
                .andExpect(jsonPath("$.finalCorrectionPrompt").value(org.hamcrest.Matchers.containsString("API -> 接口")));
    }

    @Test
    void issuesAndConsumesOneTimeHandoffToken() throws Exception {
        String response = mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionName\":\"handoff-test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("sessionId").asText();
        String handoff = mockMvc.perform(post("/api/sessions/" + sessionId + "/handoff")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayMode\":\"bilingual\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handoffToken").isString()).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(handoff).get("handoffToken").asText();
        org.hamcrest.MatcherAssert.assertThat(token, org.hamcrest.Matchers.startsWith("h_"));
        mockMvc.perform(post("/api/sessions/handoff/claim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wsToken").value(org.hamcrest.Matchers.startsWith("w_")))
                .andExpect(jsonPath("$.wsUrl").value(org.hamcrest.Matchers.containsString("?token=w_")));
        mockMvc.perform(post("/api/sessions/handoff/claim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/sessions/handoff/claim").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownHandoffDisplayMode() throws Exception {
        String response = mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionName\":\"handoff-validation\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("sessionId").asText();

        mockMvc.perform(post("/api/sessions/" + sessionId + "/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayMode\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("displayMode")));
    }

    @Test
    void generatesReportAndExportsAllSupportedFormats() throws Exception {
        String response = mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionName\":\"报告测试\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"zh\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("sessionId").asText();

        mockMvc.perform(get("/api/sessions/" + sessionId + "/report"))
                .andExpect(status().isNotFound());

        var latest = sessions.get(sessionId);
        latest.addSegment(new com.babelflux.backend.domain.Session.Segment(
                "segment-1", "hello | world", "你好世界", 1234, 2345, "final"));
        sessions.finish(latest);

        mockMvc.perform(get("/api/sessions/" + sessionId + "/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(sessionId + "-report"))
                .andExpect(jsonPath("$.metrics.segments").value(1))
                .andExpect(jsonPath("$.correctionStatus").value("skipped"))
                .andExpect(jsonPath("$.segments[0].finalTranslation").value("你好世界"));

        mockMvc.perform(get("/api/sessions/" + sessionId + "/report/download?format=txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello | world")));
        mockMvc.perform(get("/api/sessions/" + sessionId + "/report/download?format=srt"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("00:00:01,234 --> 00:00:02,345")));
        mockMvc.perform(get("/api/sessions/" + sessionId + "/report/download?format=md"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello \\| world")));
        mockMvc.perform(get("/api/sessions/" + sessionId + "/report/download?format=json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId));
        mockMvc.perform(get("/api/sessions/" + sessionId + "/report/download?format=csv"))
                .andExpect(status().isBadRequest());
    }
}
