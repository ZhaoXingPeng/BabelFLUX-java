package com.babelflux.backend.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SQL boundary for the session aggregate snapshot. */
public interface SessionPersistenceMapper {
    String COLUMNS = "session_id, created_at_epoch, ended_at_epoch, status, "
            + "session_name, source_language, target_language, domain, model_profile, product_mode, "
            + "input_mode, source_label, source_url, source_permission, tts_enabled, glossary_json, "
            + "segments_json, report_json";

    @Update("""
            update babelflux_sessions set created_at_epoch=#{createdAtEpoch}, ended_at_epoch=#{endedAtEpoch}, status=#{status},
            session_name=#{sessionName}, source_language=#{sourceLanguage}, target_language=#{targetLanguage}, domain=#{domain},
            model_profile=#{modelProfile}, product_mode=#{productMode}, input_mode=#{inputMode}, source_label=#{sourceLabel},
            source_url=#{sourceUrl}, source_permission=#{sourcePermission}, tts_enabled=#{ttsEnabled}, glossary_json=#{glossaryJson},
            segments_json=#{segmentsJson}, report_json=#{reportJson} where session_id=#{sessionId}
            """)
    int update(Row row);

    @Insert("""
            insert into babelflux_sessions (session_id, created_at_epoch, ended_at_epoch, status, session_name,
            source_language, target_language, domain, model_profile, product_mode, input_mode, source_label, source_url,
            source_permission, tts_enabled, glossary_json, segments_json, report_json)
            values (#{sessionId}, #{createdAtEpoch}, #{endedAtEpoch}, #{status}, #{sessionName}, #{sourceLanguage},
            #{targetLanguage}, #{domain}, #{modelProfile}, #{productMode}, #{inputMode}, #{sourceLabel}, #{sourceUrl},
            #{sourcePermission}, #{ttsEnabled}, #{glossaryJson}, #{segmentsJson}, #{reportJson})
            """)
    int insert(Row row);

    @Select("select " + COLUMNS + " from babelflux_sessions where session_id=#{sessionId}")
    @Results(id = "sessionRow", value = {
            @Result(column = "session_id", property = "sessionId", id = true),
            @Result(column = "created_at_epoch", property = "createdAtEpoch"),
            @Result(column = "ended_at_epoch", property = "endedAtEpoch"),
            @Result(column = "session_name", property = "sessionName"),
            @Result(column = "source_language", property = "sourceLanguage"),
            @Result(column = "target_language", property = "targetLanguage"),
            @Result(column = "model_profile", property = "modelProfile"),
            @Result(column = "product_mode", property = "productMode"),
            @Result(column = "input_mode", property = "inputMode"),
            @Result(column = "source_label", property = "sourceLabel"),
            @Result(column = "source_url", property = "sourceUrl"),
            @Result(column = "source_permission", property = "sourcePermission"),
            @Result(column = "tts_enabled", property = "ttsEnabled"),
            @Result(column = "glossary_json", property = "glossaryJson"),
            @Result(column = "segments_json", property = "segmentsJson"),
            @Result(column = "report_json", property = "reportJson")
    })
    List<Row> findById(@Param("sessionId") String sessionId);

    @Select("select " + COLUMNS + " from babelflux_sessions where session_id=#{sessionId} for update")
    @ResultMap("sessionRow")
    List<Row> findByIdForUpdate(@Param("sessionId") String sessionId);

    @Select("select " + COLUMNS + " from babelflux_sessions order by created_at_epoch desc")
    @ResultMap("sessionRow")
    List<Row> findAll();

    @Delete("delete from babelflux_sessions where session_id=#{sessionId}")
    int deleteById(@Param("sessionId") String sessionId);

    final class Row {
        private String sessionId;
        private long createdAtEpoch;
        private Long endedAtEpoch;
        private String status;
        private String sessionName;
        private String sourceLanguage;
        private String targetLanguage;
        private String domain;
        private String modelProfile;
        private String productMode;
        private String inputMode;
        private String sourceLabel;
        private String sourceUrl;
        private String sourcePermission;
        private boolean ttsEnabled;
        private String glossaryJson;
        private String segmentsJson;
        private String reportJson;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String value) { sessionId = value; }
        public long getCreatedAtEpoch() { return createdAtEpoch; }
        public void setCreatedAtEpoch(long value) { createdAtEpoch = value; }
        public Long getEndedAtEpoch() { return endedAtEpoch; }
        public void setEndedAtEpoch(Long value) { endedAtEpoch = value; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public String getSessionName() { return sessionName; }
        public void setSessionName(String value) { sessionName = value; }
        public String getSourceLanguage() { return sourceLanguage; }
        public void setSourceLanguage(String value) { sourceLanguage = value; }
        public String getTargetLanguage() { return targetLanguage; }
        public void setTargetLanguage(String value) { targetLanguage = value; }
        public String getDomain() { return domain; }
        public void setDomain(String value) { domain = value; }
        public String getModelProfile() { return modelProfile; }
        public void setModelProfile(String value) { modelProfile = value; }
        public String getProductMode() { return productMode; }
        public void setProductMode(String value) { productMode = value; }
        public String getInputMode() { return inputMode; }
        public void setInputMode(String value) { inputMode = value; }
        public String getSourceLabel() { return sourceLabel; }
        public void setSourceLabel(String value) { sourceLabel = value; }
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String value) { sourceUrl = value; }
        public String getSourcePermission() { return sourcePermission; }
        public void setSourcePermission(String value) { sourcePermission = value; }
        public boolean isTtsEnabled() { return ttsEnabled; }
        public void setTtsEnabled(boolean value) { ttsEnabled = value; }
        public String getGlossaryJson() { return glossaryJson; }
        public void setGlossaryJson(String value) { glossaryJson = value; }
        public String getSegmentsJson() { return segmentsJson; }
        public void setSegmentsJson(String value) { segmentsJson = value; }
        public String getReportJson() { return reportJson; }
        public void setReportJson(String value) { reportJson = value; }
    }
}
