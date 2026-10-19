-- Solo aditiva: sesiones del asistente, espera tras canalizar, anuncios de portales y consultas por propiedad.

-- AlterTable
ALTER TABLE "assistant_settings" ADD COLUMN     "handoffReopenMinutes" INTEGER NOT NULL DEFAULT 10;

-- AlterTable
ALTER TABLE "conversations" ADD COLUMN     "assignmentNoticeAt" TIMESTAMP(3),
ADD COLUMN     "previousContext" JSONB,
ADD COLUMN     "reopenAt" TIMESTAMP(3),
ADD COLUMN     "reopenReason" TEXT,
ADD COLUMN     "sessionCount" INTEGER NOT NULL DEFAULT 1,
ADD COLUMN     "sessionStartSeq" INTEGER NOT NULL DEFAULT 0,
ADD COLUMN     "sessionStartedAt" TIMESTAMP(3),
ADD COLUMN     "waitNoticeAt" TIMESTAMP(3);

-- AlterTable
ALTER TABLE "property_cache" ADD COLUMN     "internalId" TEXT;

-- CreateTable
CREATE TABLE "portal_listings" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "portal" TEXT NOT NULL,
    "portalName" TEXT NOT NULL,
    "externalKey" TEXT NOT NULL,
    "listingUrl" TEXT,
    "published" BOOLEAN NOT NULL DEFAULT false,
    "status" TEXT,
    "source" TEXT NOT NULL DEFAULT 'easybroker_integration',
    "syncedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "portal_listings_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "property_inquiries" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "contactKey" TEXT NOT NULL,
    "day" TEXT NOT NULL,
    "conversationId" TEXT,
    "leadId" TEXT,
    "channel" TEXT NOT NULL DEFAULT 'whatsapp',
    "linkPortal" TEXT,
    "method" TEXT NOT NULL,
    "evidence" TEXT,
    "acquisitionSource" TEXT,
    "declaredSource" TEXT,
    "messageCount" INTEGER NOT NULL DEFAULT 1,
    "source" TEXT NOT NULL,
    "isTest" BOOLEAN NOT NULL DEFAULT false,
    "firstAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "property_inquiries_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "portal_listings_companyId_publicId_idx" ON "portal_listings"("companyId", "publicId");

-- CreateIndex
CREATE UNIQUE INDEX "portal_listings_companyId_portal_externalKey_key" ON "portal_listings"("companyId", "portal", "externalKey");

-- CreateIndex
CREATE INDEX "property_inquiries_companyId_day_idx" ON "property_inquiries"("companyId", "day");

-- CreateIndex
CREATE INDEX "property_inquiries_companyId_publicId_idx" ON "property_inquiries"("companyId", "publicId");

-- CreateIndex
CREATE UNIQUE INDEX "property_inquiries_companyId_publicId_contactKey_day_key" ON "property_inquiries"("companyId", "publicId", "contactKey", "day");

-- AddForeignKey
ALTER TABLE "portal_listings" ADD CONSTRAINT "portal_listings_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_inquiries" ADD CONSTRAINT "property_inquiries_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_inquiries" ADD CONSTRAINT "property_inquiries_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "conversations"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_inquiries" ADD CONSTRAINT "property_inquiries_leadId_fkey" FOREIGN KEY ("leadId") REFERENCES "leads"("id") ON DELETE SET NULL ON UPDATE CASCADE;


-- ---------------------------------------------------------------------------
-- Datos
-- ---------------------------------------------------------------------------

-- Nombre visible de la marca: Inova lleva una sola N (el slug técnico no cambia).
UPDATE "companies" SET "name" = 'Century 21 Inova', "updatedAt" = CURRENT_TIMESTAMP
WHERE "slug" = 'century21-innova' AND "name" = 'Century 21 Innova';

-- Sesión actual de las conversaciones existentes: desde su creación.
UPDATE "conversations" SET "sessionStartedAt" = "createdAt" WHERE "sessionStartedAt" IS NULL;

-- Espera automática de conversaciones ya canalizadas: se calcula desde la
-- fecha real de canalización (no desde el despliegue), así que las antiguas
-- ya quedan liberadas y su próximo mensaje abre una sesión nueva.
UPDATE "conversations"
SET "reopenAt" = "handoffAt" + INTERVAL '10 minutes',
    "reopenReason" = CASE WHEN "handoffState" = 'NOT_APPLICABLE' THEN 'management' ELSE 'commercial' END,
    "assignmentNoticeAt" = CASE WHEN "handoffState" IN ('ASSIGNED', 'EXISTING_LEAD') THEN "handoffAt" ELSE NULL END
WHERE "handoffAt" IS NOT NULL AND "handoffState" <> 'NONE';

-- La IA marcaba "atención humana" permanente cuando el cliente pedía una
-- persona: eso era una espera automática, no una pausa manual. Se convierte
-- a espera de 10 minutos desde la fecha en que ocurrió. Las pausas hechas
-- desde el panel (controlChangedBy = correo) no se tocan.
UPDATE "conversations"
SET "control" = 'AI',
    "controlReason" = 'Espera automática por solicitud de atención humana (migrada; la pausa permanente la aplicaba la IA).',
    "reopenAt" = COALESCE("controlChangedAt", "updatedAt") + INTERVAL '10 minutes',
    "reopenReason" = 'human',
    "processedSeq" = "lastSeq",
    "processAfter" = NULL,
    "burstStartedAt" = NULL
WHERE "control" = 'HUMAN' AND "controlChangedBy" = 'assistant';

-- Consultas por propiedad del histórico disponible: leads cuya ruta fue
-- Propiedad o Campaña con código EB- en el dato de propiedad. "Explorar" no
-- se usa (el flujo anterior arrastraba Datos_Propiedad de conversaciones viejas).
INSERT INTO "property_inquiries" ("id", "companyId", "publicId", "contactKey", "day", "leadId", "channel", "method", "evidence", "messageCount", "source", "isTest", "firstAt", "lastAt")
SELECT gen_random_uuid()::text, x."companyId", x."publicId", x."phone", x."day", x."leadId", 'whatsapp', 'lead_property_code', x."publicId", x."n", 'lead_backfill', false, x."firstAt", x."lastAt"
FROM (
  SELECT l."companyId",
         'EB-' || upper((regexp_match(l."propertyData", 'EB[\s_-]?([A-Za-z]{2}[0-9]{3,6})', 'i'))[1]) AS "publicId",
         l."phone",
         to_char(l."createdAt" AT TIME ZONE 'UTC' AT TIME ZONE 'America/Mexico_City', 'YYYY-MM-DD') AS "day",
         (array_agg(l."id" ORDER BY l."createdAt"))[1] AS "leadId",
         count(*)::int AS "n",
         min(l."createdAt") AS "firstAt",
         max(l."createdAt") AS "lastAt"
  FROM "leads" l
  WHERE l."interestType" IN ('PROPERTY', 'CAMPAIGN')
    AND l."propertyData" ~* 'EB[\s_-]?[A-Za-z]{2}[0-9]{3,6}'
    AND COALESCE(l."source", '') <> 'testing_ui'
  GROUP BY 1, 2, 3, 4
) x
ON CONFLICT ("companyId", "publicId", "contactKey", "day") DO NOTHING;
