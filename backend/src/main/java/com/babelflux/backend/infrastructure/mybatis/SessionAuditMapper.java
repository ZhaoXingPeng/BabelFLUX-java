package com.babelflux.backend.infrastructure.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** MyBatis statement for the relational session audit trail. */
public interface SessionAuditMapper {
    @Insert("insert into babelflux_session_audit(session_id, status) values (#{sessionId}, #{status})")
    int recordCreated(@Param("sessionId") String sessionId, @Param("status") String status);
}
