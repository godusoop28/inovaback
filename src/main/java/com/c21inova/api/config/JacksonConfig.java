package com.c21inova.api.config;

import java.time.Instant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.c21inova.api.common.Texts;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/** Fechas en las respuestas con el mismo formato que el panel: "2026-10-18T10:00:00.000Z". */
@Configuration
public class JacksonConfig {

	public static JacksonModule instantModule() {
		SimpleModule module = new SimpleModule("inova-instant");
		module.addSerializer(Instant.class, new ValueSerializer<Instant>() {
			@Override
			public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
				gen.writeString(Texts.iso(value));
			}
		});
		return module;
	}

	@Bean
	JacksonModule inovaInstantModule() {
		return instantModule();
	}
}
