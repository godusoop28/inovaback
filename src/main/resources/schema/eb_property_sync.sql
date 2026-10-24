-- Solo aditiva e idempotente: inventario completo de EasyBroker (fuente de verdad)
-- con copia de las propiedades eliminadas y estado de la sincronización con 21Online.
-- El backend aplica este mismo SQL al arrancar, por eso todo usa IF NOT EXISTS.

-- CreateTable
CREATE TABLE IF NOT EXISTS "eb_properties" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "propertyType" TEXT,
    "status" TEXT NOT NULL,
    "operationType" TEXT,
    "price" DOUBLE PRECISION,
    "currency" TEXT,
    "location" TEXT,
    "bedrooms" DOUBLE PRECISION,
    "bathrooms" DOUBLE PRECISION,
    "parkingSpaces" DOUBLE PRECISION,
    "constructionSize" DOUBLE PRECISION,
    "lotSize" DOUBLE PRECISION,
    "agentName" TEXT,
    "agentEmail" TEXT,
    "coverImageUrl" TEXT,
    "imageCount" INTEGER NOT NULL DEFAULT 0,
    "publicUrl" TEXT,
    "data" JSONB NOT NULL,
    "ebCreatedAt" TEXT,
    "ebUpdatedAt" TEXT,
    "firstSyncedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt" TIMESTAMP(3),
    "deletedReason" TEXT,
    "restoreRequestedAt" TIMESTAMP(3),
    "restoreRequestedBy" TEXT,
    "restoreError" TEXT,
    "restoredAt" TIMESTAMP(3),
    "restoredAsPublicId" TEXT,
    "restoredFromPublicId" TEXT,
    "c21ListingKey" TEXT,
    "c21Status" TEXT NOT NULL DEFAULT 'pending',
    "c21Hash" TEXT,
    "c21SyncedAt" TIMESTAMP(3),
    "c21Error" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "eb_properties_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "property_sync_state" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "requestedAt" TIMESTAMP(3),
    "requestedBy" TEXT,
    "lastRunAt" TIMESTAMP(3),
    "lastSuccessAt" TIMESTAMP(3),
    "lastStatus" TEXT,
    "lastError" TEXT,
    "lastSummary" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "property_sync_state_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX IF NOT EXISTS "eb_properties_companyId_publicId_key" ON "eb_properties"("companyId", "publicId");

-- CreateIndex
CREATE INDEX IF NOT EXISTS "eb_properties_companyId_deletedAt_idx" ON "eb_properties"("companyId", "deletedAt");

-- CreateIndex
CREATE UNIQUE INDEX IF NOT EXISTS "property_sync_state_companyId_key" ON "property_sync_state"("companyId");

-- AddForeignKey
DO $$ BEGIN
  ALTER TABLE "eb_properties" ADD CONSTRAINT "eb_properties_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- AddForeignKey
DO $$ BEGIN
  ALTER TABLE "property_sync_state" ADD CONSTRAINT "property_sync_state_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
