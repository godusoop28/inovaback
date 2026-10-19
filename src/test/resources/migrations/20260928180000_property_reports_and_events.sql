-- Solo aditiva: destinatarios por propiedad, actividades, envíos y configuración de reportes (todo apagado por defecto).

-- CreateEnum
CREATE TYPE "RecipientConsent" AS ENUM ('PENDING', 'GRANTED', 'REVOKED');

-- CreateEnum
CREATE TYPE "PropertyEventType" AS ENUM ('SHOWING', 'OPEN_HOUSE', 'APPOINTMENT', 'OFFER', 'PRICE_UPDATE', 'MARKETING', 'OTHER');

-- CreateEnum
CREATE TYPE "PropertyEventStatus" AS ENUM ('SCHEDULED', 'DONE', 'CANCELLED');

-- CreateEnum
CREATE TYPE "DeliveryKind" AS ENUM ('WEEKLY_REPORT', 'EVENT_NOTICE', 'TEST');

-- CreateEnum
CREATE TYPE "DeliveryStatus" AS ENUM ('PENDING', 'SENDING', 'SENT', 'FAILED', 'UNCERTAIN', 'SKIPPED');

-- CreateTable
CREATE TABLE "property_recipients" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "phoneE164" TEXT NOT NULL,
    "relation" TEXT NOT NULL,
    "weeklyReport" BOOLEAN NOT NULL DEFAULT false,
    "eventNotifications" BOOLEAN NOT NULL DEFAULT false,
    "consentStatus" "RecipientConsent" NOT NULL DEFAULT 'PENDING',
    "consentEvidence" TEXT,
    "consentAt" TIMESTAMP(3),
    "consentRecordedBy" TEXT,
    "manyChatSubscriberId" TEXT,
    "verifiedAt" TIMESTAMP(3),
    "verifiedBy" TEXT,
    "verificationNote" TEXT,
    "lastSentAt" TIMESTAMP(3),
    "lastSendStatus" TEXT,
    "lastError" TEXT,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "notes" TEXT,
    "createdBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "property_recipients_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "property_events" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "type" "PropertyEventType" NOT NULL,
    "status" "PropertyEventStatus" NOT NULL DEFAULT 'SCHEDULED',
    "title" TEXT NOT NULL,
    "description" TEXT,
    "scheduledAt" TIMESTAMP(3),
    "completedAt" TIMESTAMP(3),
    "outcome" TEXT,
    "origin" TEXT NOT NULL DEFAULT 'panel',
    "externalId" TEXT,
    "version" INTEGER NOT NULL DEFAULT 1,
    "notifyRecipients" BOOLEAN NOT NULL DEFAULT true,
    "createdBy" TEXT,
    "updatedBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "property_events_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "property_report_deliveries" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "recipientId" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "kind" "DeliveryKind" NOT NULL,
    "dedupeKey" TEXT NOT NULL,
    "periodStart" TIMESTAMP(3),
    "periodEnd" TIMESTAMP(3),
    "eventId" TEXT,
    "status" "DeliveryStatus" NOT NULL DEFAULT 'PENDING',
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "nextAttemptAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastError" TEXT,
    "payload" JSONB NOT NULL,
    "text" TEXT NOT NULL,
    "sentAt" TIMESTAMP(3),
    "createdBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "property_report_deliveries_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "property_report_settings" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "weeklyEnabled" BOOLEAN NOT NULL DEFAULT false,
    "weeklyWeekday" INTEGER NOT NULL DEFAULT 5,
    "weeklyHour" INTEGER,
    "weeklyMinute" INTEGER NOT NULL DEFAULT 0,
    "timeZone" TEXT NOT NULL DEFAULT 'America/Mexico_City',
    "eventNotificationsEnabled" BOOLEAN NOT NULL DEFAULT false,
    "manyChatWeeklyFlowNs" TEXT,
    "manyChatEventFlowNs" TEXT,
    "manyChatFieldIds" JSONB,
    "templateNote" TEXT,
    "updatedBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "property_report_settings_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "property_recipients_companyId_publicId_idx" ON "property_recipients"("companyId", "publicId");

-- CreateIndex
CREATE UNIQUE INDEX "property_recipients_companyId_publicId_phoneE164_key" ON "property_recipients"("companyId", "publicId", "phoneE164");

-- CreateIndex
CREATE INDEX "property_events_companyId_publicId_idx" ON "property_events"("companyId", "publicId");

-- CreateIndex
CREATE UNIQUE INDEX "property_events_companyId_origin_externalId_key" ON "property_events"("companyId", "origin", "externalId");

-- CreateIndex
CREATE UNIQUE INDEX "property_report_deliveries_dedupeKey_key" ON "property_report_deliveries"("dedupeKey");

-- CreateIndex
CREATE INDEX "property_report_deliveries_status_nextAttemptAt_idx" ON "property_report_deliveries"("status", "nextAttemptAt");

-- CreateIndex
CREATE INDEX "property_report_deliveries_companyId_publicId_createdAt_idx" ON "property_report_deliveries"("companyId", "publicId", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "property_report_settings_companyId_key" ON "property_report_settings"("companyId");

-- AddForeignKey
ALTER TABLE "property_recipients" ADD CONSTRAINT "property_recipients_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_events" ADD CONSTRAINT "property_events_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_report_deliveries" ADD CONSTRAINT "property_report_deliveries_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_report_deliveries" ADD CONSTRAINT "property_report_deliveries_recipientId_fkey" FOREIGN KEY ("recipientId") REFERENCES "property_recipients"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_report_deliveries" ADD CONSTRAINT "property_report_deliveries_eventId_fkey" FOREIGN KEY ("eventId") REFERENCES "property_events"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_report_settings" ADD CONSTRAINT "property_report_settings_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;


-- Configuración inicial: TODO apagado. Solo se precargan los IDs de los
-- campos personalizados creados en ManyChat el 28-sep-2026 para las
-- plantillas pulso_inova_semanal / pulso_inova_actividad (pendientes de
-- aprobación en Meta). Sin hora, sin flujo y sin destinatarios no se envía nada.
INSERT INTO "property_report_settings" ("id", "companyId", "manyChatFieldIds", "templateNote", "updatedAt")
SELECT gen_random_uuid()::text, c."id",
  '{"weekly":{"property":15008619,"period":15008620,"weekLeads":15008621,"cumulative":15008622,"sources":15008623,"activity":15008624},"event":{"property":15008625,"headline":15008626,"detail":15008627}}'::jsonb,
  'Plantillas pulso_inova_semanal y pulso_inova_actividad por crear/aprobar en Meta desde ManyChat; flujos pendientes.',
  CURRENT_TIMESTAMP
FROM "companies" c
WHERE c."slug" = 'century21-innova'
ON CONFLICT ("companyId") DO NOTHING;
