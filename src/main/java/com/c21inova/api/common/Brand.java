package com.c21inova.api.common;

/**
 * Nombre visible de la marca y del asistente. "Inova" lleva una sola N; los
 * identificadores técnicos que dicen "innova" (slug de la Company, ns de
 * flujos) NO se renombran: romperían la integración.
 */
public final class Brand {

	public static final String BRAND_NAME = "Century 21 Inova";
	public static final String ASSISTANT_NAME = "CENTURION IA";
	public static final String ASSISTANT_SHORT_NAME = "Centurion";
	public static final String OWNER_REPORT_BRAND = "PULSO INOVA";
	public static final String ASSISTANT_GREETING = "¡Hola! Bienvenido a " + BRAND_NAME + ". Soy " + ASSISTANT_SHORT_NAME
			+ ", tu asesor virtual. Cuéntame, ¿en qué puedo ayudarte?";

	private Brand() {
	}
}
