# inovaback

Backend en Spring Boot del panel de **Century 21 Inova**: leads de WhatsApp (ManyChat), asignación de asesores (asesor propio de la propiedad, ruleta ponderada y rondas por horario), EasyBroker, avisos por ManyChat con cola de reintentos, el asistente conversacional con OpenAI ("Centurion"), reportes y reportes a propietarios ("Pulso Inova").

Es una migración uno a uno de la lógica que vivía en el panel de Next.js (`webpanelinova`). Se conservan las mismas reglas, los mismos textos y la misma base de datos: las tablas las crean las migraciones de Prisma del panel, y este backend las usa tal cual, sin cambiar el esquema.

## Stack

- Java 21 y Spring Boot 4 (web MVC, Security, JDBC). Sin JPA: SQL explícito con `JdbcClient`, porque el esquema usa enums, arreglos y JSONB de Postgres, y la asignación necesita bloqueos `FOR UPDATE`.
- PostgreSQL (Render o Neon).
- JWT (jjwt) para el panel. Las contraseñas son las mismas: bcrypt de costo 10, compatible con los usuarios existentes.

## Variables de entorno

| Variable | Obligatoria | Uso |
|---|---|---|
| `DATABASE_URL` | sí | `postgresql://usuario:clave@host/db` (la misma URL que da Render o Neon). También se acepta `JDBC_DATABASE_URL`. |
| `JWT_SECRET` | sí | Firma de los tokens del panel. Si falta, se usa `AUTH_SECRET`. |
| `INTEGRATION_SECRET` | sí | Header `x-inova-secret` de los webhooks de ManyChat. |
| `CRON_SECRET` | sí | Protege `/api/cron/*` (`Authorization: Bearer`, `x-cron-secret` o `?secret=`). |
| `EASYBROKER_API_KEY` | sí | API de EasyBroker. |
| `EASYBROKER_FALLBACK_AGENT_EMAIL` | no | Correo "comodín": esos leads van a la ruleta. Por defecto `contacto@c21inova.com`. |
| `MANYCHAT_API_KEY` | sí | API de ManyChat. |
| `MANYCHAT_ADVISOR_FLOW_ID` | sí | Flujo que avisa al asesor. |
| `OPENAI_API_KEY` | sí | OpenAI. |
| `OPENAI_PROPERTY_SEARCH_MODEL` | sí | Modelo de la búsqueda de propiedades (y del asistente si no hay otro). |
| `OPENAI_ASSISTANT_MODEL` | no | Modelo del asistente. |
| `ASSISTANT_DISABLED` | no | `true` apaga el asistente pase lo que pase (emergencia). |
| `ASSISTANT_LINK_DOMAINS` | no | Dominios cuyos anuncios puede leer el asistente. |
| `AUTOMATION_MODE` | no | `live` llama de verdad a EasyBroker y ManyChat; cualquier otro valor es `shadow` (simulación). |
| `SCHEDULER_ENABLED` | no | `true` corre los crons dentro del proceso. Déjalo apagado mientras otro despliegue (Vercel) siga corriendo los mismos crons. |
| `PANEL_URL` | no | URL del panel (enlaces en avisos a gerencia). |
| `CORS_ORIGINS` | no | Orígenes del panel separados por coma. |
| `ENABLE_INTERNAL_TESTING` | no | `true` habilita `/api/testing/simulate-lead`. |
| `JWT_TTL_HOURS` | no | Vigencia del token (por defecto 168 h). |
| `DB_POOL_SIZE` | no | Conexiones a la base (por defecto 5). |
| `PROPERTY_SYNC_ENABLED` | no | `false` apaga la sincronización del inventario EasyBroker → CRM → 21Online (encendida por defecto si hay `EASYBROKER_API_KEY`). |
| `PROPERTY_SYNC_INTERVAL_MINUTES` | no | Cada cuánto corre (por defecto 10). |
| `C21_CLIENT_ID` / `C21_CLIENT_SECRET` | no | Credencial de la API Externa de 21Online (21Online → Administración y Config → API Externa → Credenciales API, con lectura y escritura de propiedades). Sin ellas el inventario solo se copia al CRM. |
| `C21_SCOPE` | no | Scopes a pedir en el token, si la credencial los exige (p. ej. `properties:read properties:write`). |
| `C21_SYNC_ENABLED` | no | `false` deja de enviar a 21Online sin borrar la credencial. |
| `C21_API_BASE_URL` | no | Por defecto `https://api.21online.lat`. |

## API

Todas las rutas del panel usan `Authorization: Bearer <token>`. Los roles (`ADMIN`, `DIRECCION`, `CONSULTA`) son los mismos del panel.

**Sesión**
- `POST /api/auth/login` `{ email, password }` → `{ token, user }`
- `GET /api/auth/me`

**ManyChat** (header `x-inova-secret`). Mismos cuerpos y respuestas que antes: en ManyChat solo cambia la URL base.
- `POST /api/webhooks/manychat/lead`
- `POST /api/webhooks/manychat/message` (asistente; responde `handled: "true" | "false"`)
- `POST /api/webhooks/manychat/property-search`

**Inventario EasyBroker → CRM → 21Online** (`inventory/`). EasyBroker es la fuente de verdad. Cada 10 minutos (o cuando el panel lo pide) se copia todo el inventario a `eb_properties` (todos los estados), se detectan cambios por `updated_at` y las propiedades que ya no existen en EasyBroker quedan como eliminadas con la copia completa de sus datos. A 21Online se envían por la API Externa (OAuth2 client_credentials en `/oauth2/token`, `POST /api/v1/external/Property` hace upsert por `ExternalId` = código EB, así nunca se duplica); al primer envío se emparejan las que ya existían en 21Online por precio + ciudad + recámaras. Las eliminadas se retiran (`StandardStatus: Withdrawn`). Restaurar vuelve a crear la propiedad en EasyBroker con la copia y reutiliza su anuncio de 21Online. Las tablas las crea la migración `20261023120000_eb_property_sync` del panel; el backend aplica el mismo SQL idempotente al arrancar.
- `GET /api/inventory?deleted=true|false`, `GET /api/inventory/status`, `POST /api/inventory/sync` (ADMIN), `POST /api/inventory/{publicId}/restore` (ADMIN, DIRECCION), `/api/cron/property-sync` (`CRON_SECRET`).

**Crons** (`CRON_SECRET`, GET o POST): `/api/cron/retry-integrations` cada 5 min, `/api/cron/conversations` cada minuto y `/api/cron/property-reports` cada 15 min.

**Panel**
- Asesores: `GET|POST /api/advisors`, `GET|PATCH|DELETE /api/advisors/{id}`, `PATCH /api/advisors/{id}/active`, `POST /api/advisors/{id}/pause|resume`, `POST /api/advisors/simulate`, `GET|POST /api/advisors/rounds`, `PATCH|DELETE /api/advisors/rounds/{id}`
- Leads: `GET /api/leads`, `GET /api/leads/counts|filters|property-titles`, `GET|PATCH /api/leads/{id}`, `POST /api/leads/{id}/reassign`
- Tablero y reportes: `GET /api/dashboard`, `GET /api/reports`, `GET /api/reports/csv`, `GET /api/reports/properties/csv`
- Conversaciones: `GET /api/conversations`, `/attention`, `/escalations`, `/advisors`, `/advisors/{id}`, `/{id}`; `POST /{id}/control|resume|human-reply`, `POST /escalations/{id}/resolve`, `POST /messages/{id}/requeue`, `GET|PUT /settings`, `POST /catalog/sync`, `POST /simulate`
- Propiedades: `GET /api/properties`, `GET /api/properties/{publicId}`, `GET /{publicId}/weekly-preview`, destinatarios (`POST /recipients`, `PATCH /recipients/{id}`, `POST /recipients/{id}/consent|verify|test`), actividades (`POST /events`, `PUT /events/{id}`) y `GET|PUT /settings`
- Usuarios (ADMIN): `GET|POST /api/users`, `PATCH|DELETE /api/users/{id}`, `PATCH /api/users/{id}/active`, `POST /api/users/{id}/password`
- Administración (ADMIN): `GET /api/admin/readiness`, `GET /api/admin/audit`, `GET|POST /api/admin/integration-jobs/recovery`
- `GET /api/health` (público)

## Desarrollo

```bash
./mvnw spring-boot:run      # con las variables de entorno de arriba
./mvnw test                 # pruebas (levantan un Postgres embebido)
```

Las pruebas incluyen:
- **Paridad** (`ParityTest`): más de 250 casos generados con las funciones originales en TypeScript (teléfonos, ruleta, rondas, avisos, prompt, enlaces de portales, reportes…) que la versión en Java debe reproducir exactamente.
- **API completa** (`ApiIntegrationTest`): Postgres real con las migraciones de Prisma, login, asesores, webhooks, leads, reportes, usuarios, asistente con OpenAI falso, propiedades, crons y permisos por rol.
- **Pipeline en vivo** (`LivePipelineTest`): EasyBroker y ManyChat falsos, con asignación directa, contacto, aviso, fallo de flujo y recuperación por la cola.

## Despliegue

El `Dockerfile` construye y corre el jar en el puerto `PORT` (o 8080). En Render: un Web Service con runtime Docker y la URL **interna** de la base en `DATABASE_URL`.
