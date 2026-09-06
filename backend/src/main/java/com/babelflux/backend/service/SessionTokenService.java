package com.babelflux.backend.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class SessionTokenService {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public String issue(String sessionId) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        tokens.put(sessionId, token);
        return token;
    }

    public boolean valid(String sessionId, String token) { return token != null && token.equals(tokens.get(sessionId)); }
}
