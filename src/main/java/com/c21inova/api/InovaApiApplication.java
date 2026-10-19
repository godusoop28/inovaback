package com.c21inova.api;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class InovaApiApplication {

	public static void main(String[] args) {
		// Las columnas TIMESTAMP(3) de Prisma guardan UTC sin zona: el proceso
		// debe leer y escribir en UTC o cada fecha se corre 6 horas.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(InovaApiApplication.class, args);
	}
}
