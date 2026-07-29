package com.cryptoalgo.backend.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Jackson cannot serialize io.r2dbc.postgresql.codec.Json by default.
 * Register a serializer that emits the JSON payload as raw JSON.
 * Use modulesToInstall so we do NOT replace Boot's JavaTimeModule.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer r2dbcJsonSerializer() {
        return builder -> builder.modulesToInstall(r2dbcJsonModule());
    }

    private static SimpleModule r2dbcJsonModule() {
        SimpleModule module = new SimpleModule("R2dbcJsonModule");
        module.addSerializer(Json.class, new JsonSerializer<>() {
            @Override
            public void serialize(Json value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null) {
                    gen.writeNull();
                    return;
                }
                String raw = value.asString();
                if (raw == null || raw.isBlank()) {
                    gen.writeNull();
                } else {
                    gen.writeRawValue(raw);
                }
            }
        });
        return module;
    }
}
