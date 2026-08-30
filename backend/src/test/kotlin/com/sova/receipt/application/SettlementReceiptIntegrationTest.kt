package com.sova.receipt.application

import com.sova.finance.application.CreateCashFlowEntryCommand
import com.sova.finance.application.DefaultFinancialCatalogProvisioner
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.application.SettleCashFlowOccurrenceCommand
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.receipt.infrastructure.persistence.PostgresSettlementReceiptRepository
import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.InboundWhatsappMedia
import com.sova.whatsapp.application.ProvisionedWhatsappUser
import com.sova.whatsapp.application.StoredWhatsappAttachment
import com.sova.whatsapp.application.StoredWhatsappMessage
import com.sova.whatsapp.application.WhatsappAttachmentStatus
import com.sova.whatsapp.application.WhatsappMediaType
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.domain.WhatsappMessageDirection
import com.sova.whatsapp.infrastructure.persistence.PostgresWhatsappRepository
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettlementReceiptIntegrationTest {
    @Test
    fun `receipt is household scoped idempotent and recoverable with integrity metadata`() = runBlocking {
        EmbeddedPostgres.builder().start().use { postgres ->
            val database = PostgresDatabase(
                DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
            )
            database.migrate()
            val whatsapp = PostgresWhatsappRepository(database)
            val userA = provision(whatsapp, "A", "+15550000001", "+15550000002")
            val userB = FinancialContext(UUID.randomUUID(), UUID.randomUUID())
            val finance = FinancialApplicationService(
                PostgresFinancialRepository(database),
                clock = CLOCK,
            )
            DefaultFinancialCatalogProvisioner(finance).provision(userA)
            val categories = finance.listCategories(userA).successValue()
            val accounts = finance.listAccounts(userA).successValue()
            val entry = finance.createCashFlowEntry(
                userA,
                CreateCashFlowEntryCommand(
                    type = FinancialEntryType.EXPENSE,
                    money = Money.of("850.00".toBigDecimal()),
                    description = FinancialDescription.of("Aluguel mensal"),
                    accountId = accounts.single { it.active }.id,
                    categoryId = categories.single { it.name.value == "Moradia" }.id,
                    recurrence = CashFlowRecurrence.MONTHLY,
                    startDate = LocalDate.of(2026, 8, 1),
                    dayOfMonth = 30,
                    source = FinancialSource.WHATSAPP_TEXT,
                    sourceReferenceId = SourceReferenceId.of("SM-RENT"),
                ),
            ).successValue().value
            val settlement = finance.settleCashFlowOccurrence(
                userA,
                entry.id,
                SettleCashFlowOccurrenceCommand(
                    scheduledOn = LocalDate.of(2026, 8, 30),
                    paidOn = LocalDate.of(2026, 8, 29),
                ),
            ).successValue().value.settlement
            val attachmentId = saveProcessedImage(database, whatsapp, userA)
            val repository = PostgresSettlementReceiptRepository(database)
            val service = SettlementReceiptService(repository, CLOCK)

            val first = assertNotNull(service.link(userA, settlement.id, attachmentId))
            val duplicate = assertNotNull(service.link(userA, settlement.id, attachmentId))

            assertTrue(first.created)
            assertFalse(duplicate.created)
            assertEquals(first.receipt.id, duplicate.receipt.id)
            assertEquals("comprovante do aluguel", first.receipt.caption)
            assertEquals("b".repeat(64), first.receipt.sha256Hex)
            assertEquals(321, first.receipt.sizeBytes)
            assertEquals(first.receipt, service.list(userA, settlement.id).single())
            assertNull(service.link(userB, settlement.id, attachmentId))
            assertTrue(service.list(userB, settlement.id).isEmpty())
            assertEquals("media/$attachmentId.png", repository.findMedia(first.receipt.id)?.storageKey)
            database.withConnection { connection ->
                val sql = DSL.using(connection, SQLDialect.POSTGRES)
                assertEquals(1L, sql.fetchValue("SELECT count(*) FROM settlement_receipts", Long::class.java))
                assertEquals(
                    "CONSUMED",
                    sql.fetchOne("SELECT status FROM image_analyses WHERE attachment_id = ?", attachmentId)
                        ?.get(0, String::class.java),
                )
            }
        }
    }

    private suspend fun provision(
        whatsapp: PostgresWhatsappRepository,
        suffix: String,
        firstNumber: String,
        secondNumber: String,
    ): FinancialContext {
        val first = WhatsappAddress("whatsapp:$firstNumber")
        whatsapp.provision(
            HouseholdProvisioning(
                "Household $suffix",
                listOf(
                    ProvisionedWhatsappUser("User ${suffix}1", first),
                    ProvisionedWhatsappUser("User ${suffix}2", WhatsappAddress("whatsapp:$secondNumber")),
                ),
            ),
        )
        return requireNotNull(whatsapp.findActiveUser(first)).let { FinancialContext(it.householdId, it.userId) }
    }

    private suspend fun saveProcessedImage(
        database: PostgresDatabase,
        whatsapp: PostgresWhatsappRepository,
        context: FinancialContext,
    ): UUID {
        val from = WhatsappAddress("whatsapp:+15550000001")
        val message = StoredWhatsappMessage(
            messageSid = "MM-RECEIPT",
            direction = WhatsappMessageDirection.INBOUND,
            userId = context.userId,
            from = from,
            to = WhatsappAddress("whatsapp:+15550000000"),
            body = "comprovante do aluguel",
            occurredAt = CLOCK.instant(),
        )
        val attachmentId = UUID.randomUUID()
        assertTrue(
            whatsapp.saveInboundIfNew(
                message,
                listOf(
                    StoredWhatsappAttachment(
                        id = attachmentId,
                        whatsappMessageId = message.id,
                        mediaIndex = 0,
                        mediaUrl = "https://api.twilio.com/media/ME-RECEIPT",
                        contentType = "image/png",
                        mediaType = WhatsappMediaType.IMAGE,
                        status = WhatsappAttachmentStatus.RECEIVED,
                    ),
                ),
                imageJobAttachmentId = attachmentId,
            ),
        )
        database.withConnection { connection ->
            val sql = DSL.using(connection, SQLDialect.POSTGRES)
            sql.execute(
                """
                UPDATE attachments
                SET status = 'PROCESSED', size_bytes = 321, storage_key = ?, sha256_hex = ?
                WHERE id = ?
                """.trimIndent(),
                "media/$attachmentId.png",
                "b".repeat(64),
                attachmentId,
            )
            sql.execute(
                """
                INSERT INTO image_analyses (
                    attachment_id, household_id, document_type, merchant, amount, occurred_on,
                    suggested_category, confidence, confirmation_required, provider, model, analyzed_at
                ) VALUES (?, ?, 'PAYMENT_PROOF', 'Imobiliária', 850.00, '2026-08-29',
                          'Moradia', 0.99, false, 'gemini', 'gemini-test', CAST(? AS TIMESTAMPTZ))
                """.trimIndent(),
                attachmentId,
                context.householdId,
                CLOCK.instant().toString(),
            )
        }
        return attachmentId
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-30T01:00:00Z"), ZoneOffset.UTC)
    }
}

private fun <T> FinancialResult<T>.successValue(): T = (this as FinancialResult.Success<T>).value
