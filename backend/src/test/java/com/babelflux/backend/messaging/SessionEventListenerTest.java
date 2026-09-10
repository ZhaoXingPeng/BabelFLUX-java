package com.babelflux.backend.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.babelflux.backend.infrastructure.mybatis.SessionEventReceiptMapper;
import com.babelflux.backend.support.MyBatisMapperTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SessionEventListenerTest {
    @Test
    void duplicateDeliveryIsRecordedOnce() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:event-receipts;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_session_event_receipts ("
                + "event_id varchar(64) primary key, event_type varchar(128) not null, "
                + "session_id varchar(64) not null, received_at timestamp not null)");
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        SessionEvent event = new SessionEvent("event-1", "session.created", 1, "session-1",
                Instant.now(), Map.of("status", "created"));
        SessionEventListener listener = new SessionEventListener(mapper,
                MyBatisMapperTestSupport.mapper(jdbc.getDataSource(), SessionEventReceiptMapper.class));

        listener.consume(mapper.writeValueAsString(event));
        listener.consume(mapper.writeValueAsString(event));

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from babelflux_session_event_receipts", Integer.class));
    }
}
