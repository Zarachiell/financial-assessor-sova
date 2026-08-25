package com.sova.ai.application

import com.sova.finance.application.DefaultFinancialCatalogProvisioner
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinancialToolsIntegrationTest {
    @Test
    fun `all financial tools execute locally with shared household authorship and idempotency`() = runBlocking {
        withFixture { fixture ->
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

            val balance = fixture.dispatch("get_current_balance", "{}", fixture.userA, "SM-BALANCE").json()
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

            assertEquals(BigDecimal("468.00"), balance.getValue("balance").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("32.00"), expenses.getValue("total").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("500.00"), receivedIncome.getValue("total").jsonPrimitive.content.toBigDecimal())
            assertEquals(BigDecimal("32.00"), normalizedNames.getValue("total").jsonPrimitive.content.toBigDecimal())
            assertEquals(2, listed.getValue("transactions").jsonArray.size)
            assertFalse(listed.toString().contains("householdId"))
            assertFalse(listed.toString().contains("userId"))
            assertFalse(listed.toString().contains("accountId"))
            assertEquals(2, summary.getValue("categories").jsonArray.size)

            val transactions = fixture.service.listTransactions(fixture.userA).successValue()
            assertEquals(2, transactions.size)
            assertEquals(setOf(fixture.userA.userId, fixture.userB.userId), transactions.map { it.createdByUserId }.toSet())
            assertTrue(transactions.all { it.householdId == fixture.userA.householdId })
            assertTrue(transactions.all { it.source == FinancialSource.WHATSAPP_TEXT })
            assertEquals(setOf("SM-EXPENSE", "SM-INCOME"), transactions.mapNotNull { it.sourceReferenceId?.value }.toSet())
            assertEquals(setOf(FinancialEntryType.EXPENSE, FinancialEntryType.INCOME), transactions.map { it.type }.toSet())
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
        val service = FinancialApplicationService(PostgresFinancialRepository(database))
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
    ): ToolDispatchResult = dispatcher.dispatch(
        AiToolCall("call-$messageSid", name, arguments),
        ToolExecutionContext(financialContext, messageSid),
    )
}

private fun ToolDispatchResult.json() = Json.parseToJsonElement(content).jsonObject

private fun <T> FinancialResult<T>.successValue(): T = (this as FinancialResult.Success<T>).value
