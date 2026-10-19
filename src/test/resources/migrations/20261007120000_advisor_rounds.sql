-- Solo aditiva: rondas por horario de asesores (sin rondas, la ruleta funciona igual que antes).

-- CreateTable
CREATE TABLE "advisor_rounds" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "advisorId" TEXT NOT NULL,
    "weekdays" INTEGER[],
    "startMinute" INTEGER NOT NULL,
    "endMinute" INTEGER NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "note" TEXT,
    "createdBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "advisor_rounds_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "advisor_rounds_companyId_active_idx" ON "advisor_rounds"("companyId", "active");

-- CreateIndex
CREATE INDEX "advisor_rounds_advisorId_idx" ON "advisor_rounds"("advisorId");

-- AddForeignKey
ALTER TABLE "advisor_rounds" ADD CONSTRAINT "advisor_rounds_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "advisor_rounds" ADD CONSTRAINT "advisor_rounds_advisorId_fkey" FOREIGN KEY ("advisorId") REFERENCES "advisors"("id") ON DELETE CASCADE ON UPDATE CASCADE;
