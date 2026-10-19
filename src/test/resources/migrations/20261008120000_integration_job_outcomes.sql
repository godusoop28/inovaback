-- Estado real de las acciones de integración (MANYCHAT_FLOW y demás).
-- Solo agrega estados y columnas: NO reactiva ni reenvía trabajos antiguos.
ALTER TYPE "IntegrationJobStatus" ADD VALUE IF NOT EXISTS 'UNCERTAIN';
ALTER TYPE "IntegrationJobStatus" ADD VALUE IF NOT EXISTS 'CANCELLED';

ALTER TABLE "integration_jobs"
  ADD COLUMN "errorKind" TEXT,
  ADD COLUMN "errorCode" TEXT,
  ADD COLUMN "lastAttemptAt" TIMESTAMP(3),
  ADD COLUMN "resolvedAt" TIMESTAMP(3);

-- Metadatos para que el panel explique los fallos ya registrados (sin tocar su estado).
UPDATE "integration_jobs" SET "lastAttemptAt" = "updatedAt" WHERE "attempts" > 0;
UPDATE "integration_jobs" SET "resolvedAt" = "updatedAt" WHERE "status" = 'SUCCESS';
UPDATE "integration_jobs"
  SET "errorCode" = 'HTTP_' || substring("lastError" from 'ManyChat API error ([0-9]{3})'),
      "errorKind" = 'REJECTED'
  WHERE "type" = 'MANYCHAT_FLOW' AND "lastError" ~ '^ManyChat API error 4[0-9]{2}';
