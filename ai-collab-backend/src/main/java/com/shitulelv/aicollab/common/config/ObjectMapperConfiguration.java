package com.shitulelv.aicollab.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObjectMapperConfiguration {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Boot 4 HTTP/SSE uses Jackson 3; preserve the existing Jackson 2 domain tree contract. */
    @Bean
    public tools.jackson.databind.JacksonModule legacyJsonNodeHttpModule(ObjectMapper legacy) {
        var transport = tools.jackson.databind.json.JsonMapper.builder().build();
        var module = new tools.jackson.databind.module.SimpleModule("LegacyJsonNodeHttp");
        module.addSerializer(com.fasterxml.jackson.databind.JsonNode.class,
                new tools.jackson.databind.ValueSerializer<com.fasterxml.jackson.databind.JsonNode>() {
                    @Override public void serialize(com.fasterxml.jackson.databind.JsonNode value,
                            tools.jackson.core.JsonGenerator generator, tools.jackson.databind.SerializationContext context) {
                        context.writeTree(generator, transport.readTree(value.toString()));
                    }
                });
        module.addDeserializer(com.fasterxml.jackson.databind.JsonNode.class,
                new tools.jackson.databind.ValueDeserializer<com.fasterxml.jackson.databind.JsonNode>() {
                    @Override public com.fasterxml.jackson.databind.JsonNode deserialize(
                            tools.jackson.core.JsonParser parser, tools.jackson.databind.DeserializationContext context) {
                        try { return legacy.readTree(context.readTree(parser).toString()); }
                        catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid JSON tree", failure); }
                    }
                });
        return module;
    }
}
