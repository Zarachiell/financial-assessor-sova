package com.sova.ai.application

import com.sova.finance.application.DefaultFinancialCatalogProvisioner
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.domain.ExpenseNature
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.ProvisionedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.infrastructure.persistence.PostgresWhatsappRepository
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinancialToolsIntegrationTest {
    @Test
    fun `multiple recurring entries from one MessageSid keep independent idempotency keys`() = runBlocking {
        withFixture { fixture ->
            val entries = listOf(
                Triple("Assinatura Wellhub", "99.90", 12),
                Triple("Refinanciamento Itaú", "699.58", 15),
                Triple("Internet 1", "99.90", 15),
                Triple("Internet 2", "99.90", 15),
                Triple("Energia", "305", 7),
                Triple("Água", "130", 15),
                Triple("Comida", "900", 1),
                Triple("Parcelas de empréstimos", "2755.99", 30),
                Triple("Cartão de crédito Nubank", "1500", 10),
            )

            entries.forEachIndexed { index, (description, amount, dayOfMonth) ->
                val messageSid = if (index < 4) "SM-FIXED-BATCH-1" else "SM-FIXED-BATCH-2"
                val itemIndex = if (index < 4) index + 1 else index - 3
                val arguments =
                    """{"type":"EXPENSE","amount":$amount,"description":"$description","recurrence":"MONTHLY","startDate":"2026-08-27","dayOfMonth":$dayOfMonth,"itemIndex":$itemIndex}"""
                assertTrue(fixture.dispatch("create_cash_flow_entry", arguments, fixture.userA, messageSid).successful)
                assertTrue(fixture.dispatch("create_cash_flow_entry", arguments, fixture.userA, messageSid).successful)
            }
            fixture.dispatch(
                "create_cash_flow_entry",
                """{"type":"EXPENSE","amount":777,"description":"Despesa pontual","recurrence":"ONCE","startDate":"2026-09-20"}""",
                fixture.userA,
                "SM-ONCE",
            )

            val persisted = fixture.service.listCashFlowEntries(fixture.userA).successValue()
            assertEquals(entries.map { it.first }.toSet() + "Despesa pontual", persisted.map { it.description.value }.toSet())
            assertEquals(10, persisted.size)
            assertEquals(
                (1..4).map { "SM-FIXED-BATCH-1:$it" }.toSet() +
                    (1..5).map { "SM-FIXED-BATCH-2:$it" }.toSet(),
                persisted.mapNotNull { it.sourceReferenceId?.value }.filterNot { it == "SM-ONCE" }.toSet(),
            )
            val fixedExpenses = fixture.dispatch(
                "get_cash_flow_entries",
                """{"from":"2026-09-01","to":"2026-09-30","type":"EXPENSE","recurrence":"MONTHLY"}""",
                fixture.userA,
                "SM-LIST-FIXED",
            ).json()
            assertEquals(9, fixedExpenses.getValue("count").jsonPrimitive.content.toInt())
            assertEquals(BigDecimal("6590.27"), fixedExpenses.getValue("expenseTotal").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("6590.27"), fixedExpenses.getValue("totalAmount").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("0.00"), fixedExpenses.getValue("incomeTotal").jsonPrimitive.content.toBigDecimal())
            assertTrue(fixedExpenses.toString().contains("Assinatura Wellhub"))
            assertFalse(fixedExpenses.toString().contains("Despesa pontual"))
        }
    }

    @Test
    fun `monthly cash flow panorama calculates fixed expenses categorized income and remaining amount`() = runBlocking {
        withFixture { fixture ->
            listOf(
                """{"type":"EXPENSE","amount":3000,"description":"Moradia","recurrence":"MONTHLY","startDate":"2026-08-01","dayOfMonth":10,"category":"Moradia"}""",
                """{"type":"EXPENSE","amount":990,"description":"Contas da casa","recurrence":"MONTHLY","startDate":"2026-08-01","dayOfMonth":15,"category":"Moradia"}""",
                """{"type":"INCOME","amount":13250,"description":"Salário líquido","recurrence":"MONTHLY","startDate":"2026-08-01","dayOfMonth":30,"category":"Salário"}""",
                """{"type":"INCOME","amount":1000,"description":"Bônus","recurrence":"ONCE","startDate":"2026-09-20","category":"Bônus"}""",
                """{"type":"INCOME","amount":1000,"description":"Benefícios","recurrence":"MONTHLY","startDate":"2026-08-01","dayOfMonth":1,"category":"VR/VA"}""",
            ).forEachIndexed { index, arguments ->
                assertTrue(
                    fixture.dispatch("create_cash_flow_entry", arguments, fixture.userA, "SM-PANORAMA-$index").successful,
                )
            }

            val panorama = fixture.dispatch(
                "get_cash_flow_entries",
                """{"from":"2026-09-01","to":"2026-09-30"}""",
                fixture.userA,
                "SM-GET-PANORAMA",
            ).json()

            assertEquals(BigDecimal("3990.00"), panorama.getValue("monthlyExpenseTotal").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("15250.00"), panorama.getValue("incomeTotal").jsonPrimitive.content.toBigDecimal())
            assertEquals(
                BigDecimal("11260.00"),
                panorama.getValue("incomeAfterMonthlyExpenses").jsonPrimitive.content.toBigDecimal(),
            )
            assertEquals(BigDecimal("11260.00"), panorama.getValue("plannedNetAmount").jsonPrimitive.content.toBigDecimal())
            assertTrue(panorama.getValue("incomeByCategory").jsonArray.any { it.toString().contains("Salário") })
            assertTrue(panorama.getValue("incomeByCategory").jsonArray.any { it.toString().contains("Bônus") })
            assertTrue(panorama.getValue("incomeByCategory").jsonArray.any { it.toString().contains("VR/VA") })
        }
    }

    @Test
    fun `audio tool context preserves source and MessageSid on transaction`() = runBlocking {
        withFixture { fixture ->
            fixture.dispatch(
                "create_transaction",
                """{"type":"EXPENSE","amount":35,"description":"almoço","category":"Alimentação","occurredOn":"2026-08-25"}""",
                fixture.userA,
                "SM-AUDIO-EXPENSE",
                FinancialSource.WHATSAPP_AUDIO,
            )

            val transaction = fixture.service.listTransactions(fixture.userA).successValue().single()
            assertEquals(FinancialSource.WHATSAPP_AUDIO, transaction.source)
            assertEquals("SM-AUDIO-EXPENSE", transaction.sourceReferenceId?.value)
        }
    }

    @Test
    fun `all financial tools execute locally with shared household authorship and idempotency`() = runBlocking {
        withFixture { fixture ->
            val catalog = fixture.service.listCategories(fixture.userA).successValue().associateBy { it.name.value }
            assertEquals(ExpenseNature.FIXED, catalog.getValue("Moradia").expenseNature)
            assertEquals(ExpenseNature.DISCRETIONARY, catalog.getValue("Restaurante").expenseNature)
            assertEquals(ExpenseNature.UNCLASSIFIED, catalog.getValue("Alimentação").expenseNature)
            assertEquals(FinancialEntryType.INCOME, catalog.getValue("Bônus").type)
            assertEquals(FinancialEntryType.INCOME, catalog.getValue("VR/VA").type)

            val expense = fixture.dispatch(
                "create_transaction",
                """
                {
                  "type":"EXPENSE",
                  "amount":32,
                  "description":"almoço",
                  "category":"Alimentação",
                  "occurredOn":"2026-08-25"
                }
                """.trimIndent(),
                fixture.userA,
                "SM-EXPENSE",
            )
            val duplicate = fixture.dispatch(
                "create_transaction",
                """
                {
                  "type":"EXPENSE",
                  "amount":32,
                  "description":"almoço",
                  "category":"Alimentação",
                  "occurredOn":"2026-08-25"
                }
                """.trimIndent(),
                fixture.userA,
                "SM-EXPENSE",
            )
            val income = fixture.dispatch(
                "create_transaction",
                """
                {
                  "type":"INCOME",
                  "amount":500,
                  "description":"reembolso",
                  "category":"Reembolso",
                  "occurredOn":"2026-08-25"
                }
                """.trimIndent(),
                fixture.userB,
                "SM-INCOME",
            )

            assertTrue(expense.successful)
            assertTrue(expense.json().getValue("created").jsonPrimitive.boolean)
            assertFalse(duplicate.json().getValue("created").jsonPrimitive.boolean)
            assertTrue(income.successful)

            val balance = fixture.dispatch(
                "get_current_balance",
                """{"account":null}""",
                fixture.userA,
                "SM-BALANCE",
            ).json()
            val expenses = fixture.dispatch(
                "get_expenses",
                """{"from":"2026-08-01","to":"2026-08-31"}""",
                fixture.userA,
                "SM-EXPENSES",
            ).json()
            val receivedIncome = fixture.dispatch(
                "get_income",
                """{"from":"2026-08-01","to":"2026-08-31"}""",
                fixture.userB,
                "SM-INCOMES",
            ).json()
            val normalizedNames = fixture.dispatch(
                "get_expenses",
                """{"from":"2026-08-01","to":"2026-08-31","category":"alimentacao","account":"CONTA PRINCIPAL"}""",
                fixture.userA,
                "SM-NORMALIZED",
            ).json()
            val listed = fixture.dispatch(
                "get_transactions",
                """{"from":"2026-08-01","to":"2026-08-31","limit":10}""",
                fixture.userA,
                "SM-LIST",
            ).json()
            val summary = fixture.dispatch(
                "get_category_summary",
                """{"from":"2026-08-01","to":"2026-08-31"}""",
                fixture.userA,
                "SM-SUMMARY",
            ).json()
            val monthlyExpense = fixture.dispatch(
                "create_cash_flow_entry",
                """
                {
                  "type":"EXPENSE",
                  "amount":2000,
                  "description":"aluguel",
                  "recurrence":"MONTHLY",
                  "startDate":"2026-08-25",
                  "dayOfMonth":10,
                  "category":"Moradia"
                }
                """.trimIndent(),
                fixture.userA,
                "SM-RENT",
            ).json()
            val duplicateMonthlyExpense = fixture.dispatch(
                "create_cash_flow_entry",
                """
                {
                  "type":"EXPENSE",
                  "amount":2000,
                  "description":"aluguel",
                  "recurrence":"MONTHLY",
                  "startDate":"2026-08-25",
                  "dayOfMonth":10,
                  "category":"Moradia"
                }
                """.trimIndent(),
                fixture.userA,
                "SM-RENT",
            ).json()
            val monthlyIncome = fixture.dispatch(
                "create_cash_flow_entry",
                """
                {
                  "type":"INCOME",
                  "amount":8000,
                  "description":"salário",
                  "recurrence":"MONTHLY",
                  "startDate":"2026-08-25",
                  "dayOfMonth":5,
                  "category":"Salário"
                }
                """.trimIndent(),
                fixture.userB,
                "SM-SALARY",
            ).json()
            val projection = fixture.dispatch(
                "get_cash_flow_projection",
                """{"targetDate":"2026-09-25"}""",
                fixture.userA,
                "SM-PROJECTION",
            ).json()

            assertEquals(BigDecimal("468.00"), balance.getValue("balance").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("32.00"), expenses.getValue("total").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("500.00"), receivedIncome.getValue("total").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("32.00"), normalizedNames.getValue("total").jsonPrimitive.content.toBigDecimal())
            assertEquals(2, listed.getValue("transactions").jsonArray.size)
            assertFalse(listed.toString().contains("householdId"))
            assertFalse(listed.toString().contains("userId"))
            assertFalse(listed.toString().contains("accountId"))
            assertEquals(2, summary.getValue("categories").jsonArray.size)
            assertTrue(monthlyExpense.getValue("created").jsonPrimitive.boolean)
            assertFalse(duplicateMonthlyExpense.getValue("created").jsonPrimitive.boolean)
            assertTrue(monthlyIncome.getValue("created").jsonPrimitive.boolean)
            assertEquals(
                BigDecimal("6468.00"),
                projection.getValue("projectedBalance").jsonPrimitive.content.toBigDecimal(),
            )
            assertEquals(BigDecimal("8000.00"), projection.getValue("expectedIncome").jsonPrimitive.content.toBigDecimal())
            assertEquals(
                BigDecimal("2000.00"),
                projection.getValue("expectedExpenses").jsonPrimitive.content.toBigDecimal(),
            )
            assertEquals(2, projection.getValue("occurrenceCount").jsonPrimitive.content.toInt())
            assertEquals(1, projection.getValue("incomeOccurrenceCount").jsonPrimitive.content.toInt())
            assertEquals(1, projection.getValue("expenseOccurrenceCount").jsonPrimitive.content.toInt())

            val transactions = fixture.service.listTransactions(fixture.userA).successValue()
            assertEquals(2, transactions.size)
            assertEquals(setOf(fixture.userA.userId, fixture.userB.userId), transactions.map { it.createdByUserId }.toSet())
            assertTrue(transactions.all { it.householdId == fixture.userA.householdId })
            assertTrue(transactions.all { it.source == FinancialSource.WHATSAPP_TEXT })
            assertEquals(setOf("SM-EXPENSE", "SM-INCOME"), transactions.mapNotNull { it.sourceReferenceId?.value }.toSet())
            assertEquals(setOf(FinancialEntryType.EXPENSE, FinancialEntryType.INCOME), transactions.map { it.type }.toSet())
            val cashFlowEntries = fixture.service.listCashFlowEntries(fixture.userA).successValue()
            assertEquals(2, cashFlowEntries.size)
            assertEquals(setOf(fixture.userA.userId, fixture.userB.userId), cashFlowEntries.map { it.createdByUserId }.toSet())
            assertTrue(cashFlowEntries.all { it.householdId == fixture.userA.householdId })
        }
    }

    @Test
    fun `dispatcher rejects unknown tools invalid JSON and forbidden identity arguments`() = runBlocking {
        withFixture { fixture ->
            val unknown = fixture.dispatch("execute_sql", "{}", fixture.userA, "SM-UNKNOWN")
            val malformed = fixture.dispatch("get_current_balance", "not-json", fixture.userA, "SM-MALFORMED")
            val identityInjection = fixture.dispatch(
                "create_transaction",
                """
                {
                  "type":"EXPENSE",
                  "amount":10,
                  "description":"teste",
                  "category":"Outros",
                  "occurredOn":"2026-08-25",
                  "userId":"00000000-0000-0000-0000-000000000000"
                }
                """.trimIndent(),
                fixture.userA,
                "SM-INJECTION",
            )

            assertFalse(unknown.successful)
            assertTrue(unknown.content.contains("unknown_tool"))
            assertFalse(malformed.successful)
            assertTrue(malformed.content.contains("invalid_arguments"))
            assertFalse(identityInjection.successful)
            assertTrue(identityInjection.content.contains("invalid_arguments"))
            assertTrue(fixture.service.listTransactions(fixture.userA).successValue().isEmpty())
        }
    }

    @Test
    fun `manages multiple corrections and early cash flow settlement through local tools`() = runBlocking {
        withFixture { fixture ->
            val first = fixture.dispatch(
                "create_transaction",
                """{"type":"EXPENSE","amount":10,"description":"item um","category":"Outros","occurredOn":"2026-08-25","itemIndex":1}""",
                fixture.userA,
                "SM-MULTI",
            ).json()
            val second = fixture.dispatch(
                "create_transaction",
                """{"type":"EXPENSE","amount":20,"description":"item dois","category":"Outros","occurredOn":"2026-08-25","itemIndex":2}""",
                fixture.userA,
                "SM-MULTI",
            ).json()
            val firstId = first.getValue("transactionId").jsonPrimitive.content
            val secondId = second.getValue("transactionId").jsonPrimitive.content

            assertTrue(first.getValue("created").jsonPrimitive.boolean)
            assertTrue(second.getValue("created").jsonPrimitive.boolean)
            assertEquals(
                setOf("SM-MULTI:1", "SM-MULTI:2"),
                fixture.service.listTransactions(fixture.userA).successValue()
                    .mapNotNull { it.sourceReferenceId?.value }
                    .toSet(),
            )

            val updated = fixture.dispatch(
                "update_transaction",
                """{"transactionId":"$firstId","amount":15.50,"description":"item corrigido"}""",
                fixture.userB,
                "SM-UPDATE",
            ).json()
            val deleted = fixture.dispatch(
                "delete_transaction",
                """{"transactionId":"$secondId"}""",
                fixture.userB,
                "SM-DELETE",
            ).json()
            assertTrue(updated.getValue("updated").jsonPrimitive.boolean)
            assertEquals(BigDecimal("15.50"), updated.getValue("amount").jsonPrimitive.content.toBigDecimal())
            assertTrue(deleted.getValue("deleted").jsonPrimitive.boolean)
            assertEquals(1, fixture.service.listTransactions(fixture.userA).successValue().size)

            val planned = fixture.dispatch(
                "create_cash_flow_entry",
                """
                {
                  "type":"EXPENSE",
                  "amount":100,
                  "description":"conta antecipada",
                  "recurrence":"ONCE",
                  "startDate":"2026-08-30",
                  "category":"Moradia",
                  "account":"Conta principal"
                }
                """.trimIndent(),
                fixture.userA,
                "SM-PLANNED",
            ).json()
            val entryId = planned.getValue("cashFlowEntryId").jsonPrimitive.content
            val pending = fixture.dispatch(
                "get_cash_flow_entries",
                """{"from":"2026-08-25","to":"2026-08-31","status":"PENDING"}""",
                fixture.userA,
                "SM-PENDING",
            ).json()
            assertEquals(entryId, pending.getValue("entries").jsonArray.single().jsonObject
                .getValue("cashFlowEntryId").jsonPrimitive.content)

            val settled = fixture.dispatch(
                "settle_cash_flow_occurrence",
                """{"cashFlowEntryId":"$entryId","scheduledOn":"2026-08-30","paidOn":"2026-08-25","settledAmount":110,"note":"valor real"}""",
                fixture.userB,
                "SM-SETTLE",
            ).json()
            val duplicate = fixture.dispatch(
                "settle_cash_flow_occurrence",
                """{"cashFlowEntryId":"$entryId","scheduledOn":"2026-08-30","paidOn":"2026-08-25"}""",
                fixture.userB,
                "SM-SETTLE-RETRY",
            ).json()
            assertTrue(settled.getValue("created").jsonPrimitive.boolean)
            assertFalse(duplicate.getValue("created").jsonPrimitive.boolean)
            assertEquals("PAID", settled.getValue("status").jsonPrimitive.content)
            assertEquals(BigDecimal("100.00"), settled.getValue("plannedAmount").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("10.00"), settled.getValue("variance").jsonPrimitive.content.toBigDecimal())

            val settlementId = settled.getValue("settlementId").jsonPrimitive.content
            val settlementHistory = fixture.dispatch(
                "get_cash_flow_settlements",
                """{"settlementId":"$settlementId"}""",
                fixture.userA,
                "SM-SETTLEMENT-HISTORY",
            ).json().getValue("settlements").jsonArray.single().jsonObject
            assertEquals("ACTIVE", settlementHistory.getValue("status").jsonPrimitive.content)
            assertEquals(BigDecimal("110.00"), settlementHistory.getValue("settledAmount").jsonPrimitive.content.toBigDecimal())

            val paid = fixture.dispatch(
                "get_cash_flow_entries",
                """{"from":"2026-08-25","to":"2026-08-31","status":"PAID"}""",
                fixture.userA,
                "SM-PAID",
            ).json()
            assertEquals("2026-08-25", paid.getValue("entries").jsonArray.single().jsonObject
                .getValue("paidOn").jsonPrimitive.content)
            val projection = fixture.dispatch(
                "get_cash_flow_projection",
                """{"targetDate":"2026-08-31"}""",
                fixture.userA,
                "SM-PROJECTION-AFTER-PAYMENT",
            ).json()
            assertEquals(BigDecimal("0.00"), projection.getValue("expectedExpenses").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("-125.50"), projection.getValue("projectedBalance").jsonPrimitive.content.toBigDecimal())

            val cancelled = fixture.dispatch(
                "cancel_cash_flow_settlement",
                """{"settlementId":"$settlementId","reason":"pagamento lançado por engano"}""",
                fixture.userB,
                "SM-CANCEL-SETTLEMENT",
            ).json()
            val duplicateCancellation = fixture.dispatch(
                "cancel_cash_flow_settlement",
                """{"settlementId":"$settlementId","reason":"retry"}""",
                fixture.userB,
                "SM-CANCEL-SETTLEMENT-RETRY",
            ).json()
            assertTrue(cancelled.getValue("cancelled").jsonPrimitive.boolean)
            assertFalse(duplicateCancellation.getValue("cancelled").jsonPrimitive.boolean)
            assertEquals("CANCELLED", cancelled.getValue("status").jsonPrimitive.content)

            val pendingAfterCancellation = fixture.dispatch(
                "get_cash_flow_entries",
                """{"from":"2026-08-25","to":"2026-08-31","status":"PENDING"}""",
                fixture.userA,
                "SM-PENDING-AFTER-CANCELLATION",
            ).json()
            assertEquals(entryId, pendingAfterCancellation.getValue("entries").jsonArray.single().jsonObject
                .getValue("cashFlowEntryId").jsonPrimitive.content)
            val cancelledHistory = fixture.dispatch(
                "get_cash_flow_settlements",
                """{"from":"2026-08-25","to":"2026-08-31","status":"CANCELLED"}""",
                fixture.userA,
                "SM-CANCELLED-HISTORY",
            ).json().getValue("settlements").jsonArray.single().jsonObject
            assertEquals("CANCELLED", cancelledHistory.getValue("status").jsonPrimitive.content)
            assertEquals("pagamento lançado por engano", cancelledHistory
                .getValue("cancellationReason").jsonPrimitive.content)

            val concurrent = coroutineScope {
                listOf("A", "B").map { suffix ->
                    async {
                        fixture.dispatch(
                            "settle_cash_flow_occurrence",
                            """{"cashFlowEntryId":"$entryId","scheduledOn":"2026-08-30","paidOn":"2026-08-25","settledAmount":105}""",
                            fixture.userA,
                            "SM-RESETTLE-$suffix",
                        ).json()
                    }
                }.awaitAll()
            }
            assertEquals(1, concurrent.count { it.getValue("created").jsonPrimitive.boolean })
            assertEquals(1, concurrent.count { !it.getValue("created").jsonPrimitive.boolean })
            assertEquals(1, fixture.dispatch(
                "get_cash_flow_settlements",
                """{"from":"2026-08-25","to":"2026-08-31","status":"ACTIVE"}""",
                fixture.userA,
                "SM-ACTIVE-HISTORY",
            ).json().getValue("count").jsonPrimitive.content.toInt())

            fixture.dispatch(
                "create_cash_flow_entry",
                """
                {
                  "type":"EXPENSE",
                  "amount":100,
                  "description":"conta antecipada adicional",
                  "recurrence":"ONCE",
                  "startDate":"2026-08-30",
                  "category":"Moradia",
                  "account":"Conta principal"
                }
                """.trimIndent(),
                fixture.userA,
                "SM-AMBIGUOUS-PLANNED",
            )
            val candidates = fixture.dispatch(
                "get_cash_flow_entries",
                """{"from":"2026-08-30","to":"2026-08-30","description":"conta antecipada","amount":100}""",
                fixture.userA,
                "SM-AMBIGUOUS-CANDIDATES",
            ).json()
            assertEquals("AMBIGUOUS", candidates.getValue("candidateResolution").jsonPrimitive.content)
            assertEquals(2, candidates.getValue("totalCount").jsonPrimitive.content.toInt())
        }
    }
}

private suspend fun withFixture(block: suspend (FinancialToolFixture) -> Unit) {
    EmbeddedPostgres.builder().start().use { postgres ->
        val database = PostgresDatabase(
            DatabaseConfig(
                url = postgres.getJdbcUrl("postgres", "postgres"),
                user = "postgres",
                password = "",
            ),
        )
        database.migrate()
        val whatsapp = PostgresWhatsappRepository(database)
        val userAAddress = WhatsappAddress("whatsapp:+15550000001")
        val userBAddress = WhatsappAddress("whatsapp:+15550000002")
        whatsapp.provision(
            HouseholdProvisioning(
                name = "Sova Household",
                users = listOf(
                    ProvisionedWhatsappUser("User A", userAAddress),
                    ProvisionedWhatsappUser("User B", userBAddress),
                ),
            ),
        )
        val userA = requireNotNull(whatsapp.findActiveUser(userAAddress)).let {
            FinancialContext(it.householdId, it.userId)
        }
        val userB = requireNotNull(whatsapp.findActiveUser(userBAddress)).let {
            FinancialContext(it.householdId, it.userId)
        }
        val service = FinancialApplicationService(PostgresFinancialRepository(database), PHASE_CLOCK)
        DefaultFinancialCatalogProvisioner(service).provision(userA)
        val registry = LocalToolRegistry(financialLocalTools(service))
        block(FinancialToolFixture(service, LocalToolDispatcher(registry), userA, userB))
    }
}

private data class FinancialToolFixture(
    val service: FinancialApplicationService,
    val dispatcher: LocalToolDispatcher,
    val userA: FinancialContext,
    val userB: FinancialContext,
) {
    suspend fun dispatch(
        name: String,
        arguments: String,
        financialContext: FinancialContext,
        messageSid: String,
        source: FinancialSource = FinancialSource.WHATSAPP_TEXT,
    ): ToolDispatchResult = dispatcher.dispatch(
        AiToolCall("call-$messageSid", name, arguments),
        ToolExecutionContext(financialContext, messageSid, source),
    )
}

private fun ToolDispatchResult.json() = Json.parseToJsonElement(content).jsonObject

private fun <T> FinancialResult<T>.successValue(): T = (this as FinancialResult.Success<T>).value

private val PHASE_CLOCK = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC)
