-- CreateEnum
CREATE TYPE "UserRole" AS ENUM ('SUPER_ADMIN', 'ADMIN', 'DIRECCION', 'CONSULTA');

-- CreateEnum
CREATE TYPE "LeadInterestType" AS ENUM ('PROPERTY', 'EXPLORE', 'CAMPAIGN', 'TIMEOUT', 'NO_RESPONSE', 'OTHER');

-- CreateEnum
CREATE TYPE "LeadStatus" AS ENUM ('RECEIVED', 'PROCESSING', 'CREATED_IN_EASYBROKER', 'ASSIGNED', 'NOTIFIED', 'COMPLETED', 'FAILED');

-- CreateEnum
CREATE TYPE "AssignmentStatus" AS ENUM ('PENDING', 'ASSIGNED', 'CONFIRMED', 'FAILED');

-- CreateEnum
CREATE TYPE "AssignmentMethod" AS ENUM ('DIRECT_PROPERTY_ADVISOR', 'WEIGHTED_ROTATION', 'CAMPAIGN_DIRECT', 'MANUAL', 'FALLBACK');

-- CreateEnum
CREATE TYPE "IntegrationType" AS ENUM ('EASYBROKER', 'MANYCHAT', 'OPENAI', 'GOOGLE_SHEETS', 'MAKE');

-- CreateTable
CREATE TABLE "companies" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "slug" TEXT NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "companies_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "users" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "passwordHash" TEXT,
    "role" "UserRole" NOT NULL DEFAULT 'CONSULTA',
    "active" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "advisors" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "email" TEXT,
    "phone" TEXT NOT NULL,
    "easyBrokerEmail" TEXT,
    "easyBrokerAgentId" TEXT,
    "manyChatSubscriberId" TEXT,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "weight" INTEGER NOT NULL DEFAULT 5,
    "dailyLimit" INTEGER,
    "pausedUntil" TIMESTAMP(3),
    "leadsToday" INTEGER NOT NULL DEFAULT 0,
    "allowedProperty" BOOLEAN NOT NULL DEFAULT true,
    "allowedExplore" BOOLEAN NOT NULL DEFAULT true,
    "allowedCampaign" BOOLEAN NOT NULL DEFAULT true,
    "allowedTimeout" BOOLEAN NOT NULL DEFAULT true,
    "priority" INTEGER,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "advisors_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "leads" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "phone" TEXT NOT NULL,
    "interestType" "LeadInterestType" NOT NULL DEFAULT 'OTHER',
    "propertyData" TEXT,
    "origin" TEXT,
    "route" TEXT,
    "status" "LeadStatus" NOT NULL DEFAULT 'RECEIVED',
    "source" TEXT,
    "easyBrokerContactRequestId" TEXT,
    "easyBrokerContactId" TEXT,
    "easyBrokerPropertyId" TEXT,
    "manyChatSubscriberId" TEXT,
    "assignedAdvisorId" TEXT,
    "assignmentStatus" "AssignmentStatus" NOT NULL DEFAULT 'PENDING',
    "requestId" TEXT,
    "rawPayload" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "leads_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "lead_assignments" (
    "id" TEXT NOT NULL,
    "leadId" TEXT NOT NULL,
    "advisorId" TEXT NOT NULL,
    "method" "AssignmentMethod" NOT NULL,
    "weightAtAssignment" INTEGER NOT NULL,
    "reason" TEXT,
    "status" "AssignmentStatus" NOT NULL DEFAULT 'PENDING',
    "easyBrokerConfirmed" BOOLEAN NOT NULL DEFAULT false,
    "manyChatNotified" BOOLEAN NOT NULL DEFAULT false,
    "assignedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "lead_assignments_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "assignment_rules" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "route" TEXT NOT NULL,
    "minimumWeight" INTEGER,
    "maximumDailyLeads" INTEGER,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "assignment_rules_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "integrations" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "type" "IntegrationType" NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "config" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "integrations_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "audit_logs" (
    "id" TEXT NOT NULL,
    "companyId" TEXT,
    "leadId" TEXT,
    "advisorId" TEXT,
    "eventType" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "message" TEXT,
    "metadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "audit_logs_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "webhook_events" (
    "id" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "externalId" TEXT,
    "eventType" TEXT NOT NULL,
    "payload" JSONB NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'received',
    "processingAttempts" INTEGER NOT NULL DEFAULT 0,
    "processedAt" TIMESTAMP(3),
    "lastError" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "webhook_events_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "companies_slug_key" ON "companies"("slug");

-- CreateIndex
CREATE UNIQUE INDEX "users_email_key" ON "users"("email");

-- CreateIndex
CREATE INDEX "users_companyId_idx" ON "users"("companyId");

-- CreateIndex
CREATE INDEX "advisors_companyId_idx" ON "advisors"("companyId");

-- CreateIndex
CREATE INDEX "advisors_active_idx" ON "advisors"("active");

-- CreateIndex
CREATE INDEX "advisors_easyBrokerEmail_idx" ON "advisors"("easyBrokerEmail");

-- CreateIndex
CREATE UNIQUE INDEX "advisors_companyId_easyBrokerEmail_key" ON "advisors"("companyId", "easyBrokerEmail");

-- CreateIndex
CREATE UNIQUE INDEX "leads_requestId_key" ON "leads"("requestId");

-- CreateIndex
CREATE INDEX "leads_phone_idx" ON "leads"("phone");

-- CreateIndex
CREATE INDEX "leads_createdAt_idx" ON "leads"("createdAt");

-- CreateIndex
CREATE INDEX "leads_status_idx" ON "leads"("status");

-- CreateIndex
CREATE INDEX "leads_companyId_idx" ON "leads"("companyId");

-- CreateIndex
CREATE INDEX "leads_assignedAdvisorId_idx" ON "leads"("assignedAdvisorId");

-- CreateIndex
CREATE INDEX "lead_assignments_leadId_idx" ON "lead_assignments"("leadId");

-- CreateIndex
CREATE INDEX "lead_assignments_advisorId_idx" ON "lead_assignments"("advisorId");

-- CreateIndex
CREATE INDEX "lead_assignments_assignedAt_idx" ON "lead_assignments"("assignedAt");

-- CreateIndex
CREATE INDEX "assignment_rules_companyId_idx" ON "assignment_rules"("companyId");

-- CreateIndex
CREATE UNIQUE INDEX "integrations_companyId_type_key" ON "integrations"("companyId", "type");

-- CreateIndex
CREATE INDEX "audit_logs_createdAt_idx" ON "audit_logs"("createdAt");

-- CreateIndex
CREATE INDEX "audit_logs_leadId_idx" ON "audit_logs"("leadId");

-- CreateIndex
CREATE INDEX "webhook_events_externalId_idx" ON "webhook_events"("externalId");

-- CreateIndex
CREATE INDEX "webhook_events_createdAt_idx" ON "webhook_events"("createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "webhook_events_provider_externalId_key" ON "webhook_events"("provider", "externalId");

-- AddForeignKey
ALTER TABLE "users" ADD CONSTRAINT "users_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "advisors" ADD CONSTRAINT "advisors_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "leads" ADD CONSTRAINT "leads_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "leads" ADD CONSTRAINT "leads_assignedAdvisorId_fkey" FOREIGN KEY ("assignedAdvisorId") REFERENCES "advisors"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "lead_assignments" ADD CONSTRAINT "lead_assignments_leadId_fkey" FOREIGN KEY ("leadId") REFERENCES "leads"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "lead_assignments" ADD CONSTRAINT "lead_assignments_advisorId_fkey" FOREIGN KEY ("advisorId") REFERENCES "advisors"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "assignment_rules" ADD CONSTRAINT "assignment_rules_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "integrations" ADD CONSTRAINT "integrations_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "audit_logs" ADD CONSTRAINT "audit_logs_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "audit_logs" ADD CONSTRAINT "audit_logs_leadId_fkey" FOREIGN KEY ("leadId") REFERENCES "leads"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "audit_logs" ADD CONSTRAINT "audit_logs_advisorId_fkey" FOREIGN KEY ("advisorId") REFERENCES "advisors"("id") ON DELETE SET NULL ON UPDATE CASCADE;
