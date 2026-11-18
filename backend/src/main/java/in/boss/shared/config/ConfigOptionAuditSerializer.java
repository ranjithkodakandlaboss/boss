package in.boss.shared.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ConfigOptionAuditSerializer {

    private ConfigOptionAuditSerializer() {
    }

    static String toJson(ObjectMapper objectMapper, ConfigOptionDto option) {
        try {
            return objectMapper.writeValueAsString(option);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize configuration audit data", exception);
        }
    }
}
