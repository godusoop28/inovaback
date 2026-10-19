-- CreateEnum
CREATE TYPE "AssistantMode" AS ENUM ('OFF', 'TEST_ONLY', 'ON');

-- CreateEnum
CREATE TYPE "ConversationControl" AS ENUM ('AI', 'HUMAN', 'PAUSED');

-- CreateEnum
CREATE TYPE "ConversationHandoffState" AS ENUM ('NONE', 'ASSIGNED', 'EXISTING_LEAD', 'NO_ADVISOR', 'FAILED', 'NOT_APPLICABLE');

-- CreateEnum
CREATE TYPE "ConversationIntent" AS ENUM ('UNKNOWN', 'PROPERTY_INQUIRY', 'BUY', 'RENT', 'SELL', 'LEASE_OUT', 'INVEST', 'AGENT_COLLABORATION', 'PROVIDER', 'MANAGEMENT', 'HUMAN_REQUEST', 'OTHER');

-- CreateEnum
CREATE TYPE "ConversationMessageRole" AS ENUM ('USER', 'ASSISTANT', 'HUMAN_AGENT', 'SYSTEM');

-- CreateEnum
CREATE TYPE "ConversationMessageStatus" AS ENUM ('RECEIVED', 'QUEUED', 'SENDING', 'SENT', 'FAILED', 'UNCERTAIN', 'CANCELLED', 'SIMULATED');

-- CreateEnum
CREATE TYPE "ConversationTurnStatus" AS ENUM ('RUNNING', 'COMPLETED', 'SUPERSEDED', 'FAILED');

-- CreateEnum
CREATE TYPE "EscalationType" AS ENUM ('MANAGEMENT', 'HUMAN', 'FOLLOW_UP', 'PROCESSING_ERROR');

-- CreateEnum
CREATE TYPE "EscalationStatus" AS ENUM ('PENDING', 'NOTIFIED', 'RESOLVED');

-- CreateTable
CREATE TABLE "assistant_settings" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "mode" "AssistantMode" NOT NULL DEFAULT 'OFF',
    "debounceSeconds" INTEGER NOT NULL DEFAULT 6,
    "maxWaitSeconds" INTEGER NOT NULL DEFAULT 25,
    "maxClarifications" INTEGER NOT NULL DEFAULT 3,
    "abandonHandoffMinutes" INTEGER NOT NULL DEFAULT 30,
    "existingLeadWindowDays" INTEGER NOT NULL DEFAULT 30,
    "testSubscriberIds" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "managementSubscriberIds" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "updatedBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "assistant_settings_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "conversations" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "channel" TEXT NOT NULL DEFAULT 'whatsapp',
    "manyChatSubscriberId" TEXT NOT NULL,
    "phone" TEXT,
    "name" TEXT,
    "isTest" BOOLEAN NOT NULL DEFAULT false,
    "control" "ConversationControl" NOT NULL DEFAULT 'AI',
    "controlReason" TEXT,
    "controlChangedAt" TIMESTAMP(3),
    "controlChangedBy" TEXT,
    "handoffState" "ConversationHandoffState" NOT NULL DEFAULT 'NONE',
    "handoffReason" TEXT,
    "handoffAt" TIMESTAMP(3),
    "leadId" TEXT,
    "primaryIntent" "ConversationIntent" NOT NULL DEFAULT 'UNKNOWN',
    "secondaryIntents" "ConversationIntent"[] DEFAULT ARRAY[]::"ConversationIntent"[],
    "facts" JSONB,
    "properties" JSONB,
    "missingData" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "summary" TEXT,
    "clarificationCount" INTEGER NOT NULL DEFAULT 0,
    "campaignRef" TEXT,
    "lastSeq" INTEGER NOT NULL DEFAULT 0,
    "processedSeq" INTEGER NOT NULL DEFAULT 0,
    "burstStartedAt" TIMESTAMP(3),
    "processAfter" TIMESTAMP(3),
    "leaseOwner" TEXT,
    "leaseUntil" TIMESTAMP(3),
    "consecutiveFailures" INTEGER NOT NULL DEFAULT 0,
    "lastError" TEXT,
    "lastInboundAt" TIMESTAMP(3),
    "lastOutboundAt" TIMESTAMP(3),
    "lastActivityAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "conversations_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "conversation_messages" (
    "id" TEXT NOT NULL,
    "conversationId" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "seq" INTEGER NOT NULL,
    "role" "ConversationMessageRole" NOT NULL,
    "text" TEXT NOT NULL,
    "status" "ConversationMessageStatus" NOT NULL,
    "dedupeKey" TEXT,
    "turnId" TEXT,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "lastError" TEXT,
    "sentAt" TIMESTAMP(3),
    "metadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "conversation_messages_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "conversation_turns" (
    "id" TEXT NOT NULL,
    "conversationId" TEXT NOT NULL,
    "fromSeq" INTEGER NOT NULL,
    "toSeq" INTEGER NOT NULL,
    "status" "ConversationTurnStatus" NOT NULL DEFAULT 'RUNNING',
    "model" TEXT,
    "inputTokens" INTEGER,
    "outputTokens" INTEGER,
    "toolCalls" JSONB,
    "error" TEXT,
    "durationMs" INTEGER,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "conversation_turns_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "conversation_escalations" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "conversationId" TEXT NOT NULL,
    "type" "EscalationType" NOT NULL,
    "status" "EscalationStatus" NOT NULL DEFAULT 'PENDING',
    "reason" TEXT NOT NULL,
    "notifiedAt" TIMESTAMP(3),
    "resolvedAt" TIMESTAMP(3),
    "resolvedBy" TEXT,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "conversation_escalations_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "property_cache" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "propertyType" TEXT,
    "location" TEXT,
    "operations" JSONB,
    "bedrooms" INTEGER,
    "bathrooms" DOUBLE PRECISION,
    "publicUrl" TEXT,
    "searchText" TEXT NOT NULL,
    "ebUpdatedAt" TEXT,
    "published" BOOLEAN NOT NULL DEFAULT true,
    "syncedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "property_cache_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "assistant_settings_companyId_key" ON "assistant_settings"("companyId");

-- CreateIndex
CREATE INDEX "conversations_companyId_lastActivityAt_idx" ON "conversations"("companyId", "lastActivityAt");

-- CreateIndex
CREATE INDEX "conversations_companyId_phone_idx" ON "conversations"("companyId", "phone");

-- CreateIndex
CREATE INDEX "conversations_processAfter_idx" ON "conversations"("processAfter");

-- CreateIndex
CREATE UNIQUE INDEX "conversations_companyId_channel_manyChatSubscriberId_key" ON "conversations"("companyId", "channel", "manyChatSubscriberId");

-- CreateIndex
CREATE INDEX "conversation_messages_status_idx" ON "conversation_messages"("status");

-- CreateIndex
CREATE UNIQUE INDEX "conversation_messages_conversationId_seq_key" ON "conversation_messages"("conversationId", "seq");

-- CreateIndex
CREATE UNIQUE INDEX "conversation_messages_conversationId_dedupeKey_key" ON "conversation_messages"("conversationId", "dedupeKey");

-- CreateIndex
CREATE INDEX "conversation_turns_conversationId_createdAt_idx" ON "conversation_turns"("conversationId", "createdAt");

-- CreateIndex
CREATE INDEX "conversation_escalations_companyId_status_idx" ON "conversation_escalations"("companyId", "status");

-- CreateIndex
CREATE INDEX "conversation_escalations_conversationId_idx" ON "conversation_escalations"("conversationId");

-- CreateIndex
CREATE INDEX "property_cache_companyId_published_idx" ON "property_cache"("companyId", "published");

-- CreateIndex
CREATE UNIQUE INDEX "property_cache_companyId_publicId_key" ON "property_cache"("companyId", "publicId");

-- AddForeignKey
ALTER TABLE "assistant_settings" ADD CONSTRAINT "assistant_settings_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_leadId_fkey" FOREIGN KEY ("leadId") REFERENCES "leads"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversation_messages" ADD CONSTRAINT "conversation_messages_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "conversations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversation_turns" ADD CONSTRAINT "conversation_turns_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "conversations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversation_escalations" ADD CONSTRAINT "conversation_escalations_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "conversation_escalations" ADD CONSTRAINT "conversation_escalations_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "conversations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "property_cache" ADD CONSTRAINT "property_cache_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

