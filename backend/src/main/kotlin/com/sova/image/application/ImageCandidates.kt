package com.sova.image.application

import com.sova.finance.domain.FinancialContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ImageCandidate(
    val attachmentId: UUID,
    val householdId: UUID,
    val caption: String,
    val documentType: ImageDocumentType,
    val merchant: String?,
    val amount: BigDecimal?,
    val occurredOn: LocalDate?,
    val suggestedCategory: String?,
    val confidence: BigDecimal,
    val confirmationRequired: Boolean,
    val analyzedAt: Instant,
)

interface ImageCandidateRepository {
    suspend fun find(householdId: UUID, attachmentId: UUID): ImageCandidate?
    suspend fun listPending(householdId: UUID, limit: Int): List<ImageCandidate>
    suspend fun markConsumed(householdId: UUID, attachmentId: UUID, consumedAt: Instant): Boolean
}

class ImageCandidateService(
    private val repository: ImageCandidateRepository,
) {
    suspend fun find(context: FinancialContext, attachmentId: UUID): ImageCandidate? =
        repository.find(context.householdId, attachmentId)

    suspend fun listPending(context: FinancialContext, limit: Int = 5): List<ImageCandidate> {
        require(limit in 1..10) { "Image candidate limit must be between 1 and 10" }
        return repository.listPending(context.householdId, limit)
    }

    suspend fun markConsumed(context: FinancialContext, attachmentId: UUID, consumedAt: Instant): Boolean =
        repository.markConsumed(context.householdId, attachmentId, consumedAt)
}
