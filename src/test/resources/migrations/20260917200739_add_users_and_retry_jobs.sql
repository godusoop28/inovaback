-- CreateEnum
CREATE TYPE "IntegrationJobType" AS ENUM ('EASYBROKER_CREATE', 'EASYBROKER_ASSIGN', 'MANYCHAT_FIELDS', 'MANYCHAT_FLOW');

-- CreateEnum
CREATE TYPE "IntegrationJobStatus" AS ENUM ('PENDING', 'RETRYING', 'SUCCESS', 'FAILED');

-- AlterEnum
BEGIN;
CREATE TYPE "IntegrationType_new" AS ENUM ('EASYBROKER', 'MANYCHAT', 'OPENAI');
ALTER TABLE "integrations" ALTER COLUMN "type" TYPE "IntegrationType_new" USING ("type"::text::"IntegrationType_new");
ALTER TYPE "IntegrationType" RENAME TO "IntegrationType_old";
ALTER TYPE "IntegrationType_new" RENAME TO "IntegrationType";
DROP TYPE "public"."IntegrationType_old";
COMMIT;

-- CreateTable
CREATE TABLE "integration_jobs" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "leadId" TEXT,
    "type" "IntegrationJobType" NOT NULL,
    "status" "IntegrationJobStatus" NOT NULL DEFAULT 'PENDING',
    "payload" JSONB NOT NULL,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "lastError" TEXT,
    "nextRetryAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "integration_jobs_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "integration_jobs_status_nextRetryAt_idx" ON "integration_jobs"("status", "nextRetryAt");

-- CreateIndex
CREATE INDEX "integration_jobs_leadId_idx" ON "integration_jobs"("leadId");

-- AddForeignKey
ALTER TABLE "integration_jobs" ADD CONSTRAINT "integration_jobs_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "integration_jobs" ADD CONSTRAINT "integration_jobs_leadId_fkey" FOREIGN KEY ("leadId") REFERENCES "leads"("id") ON DELETE SET NULL ON UPDATE CASCADE;

