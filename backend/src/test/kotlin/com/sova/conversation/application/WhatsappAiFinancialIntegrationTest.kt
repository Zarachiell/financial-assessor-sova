package com.sova.conversation.application

import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProvider
import com.sova.ai.application.AiRequest
import com.sova.ai.application.AiResponse
import com.sova.ai.application.AiToolCall
import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.financialLocalTools
import com.sova.conversation.infrastructure.persistence.PostgresConversationHistory
import com.sova.finance.application.DefaultFinancialCatalogProvisioner
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.domain.FinancialContext
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HandleWhatsappTextMessage
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.InboundWhatsappMessage
import com.sova.whatsapp.application.ProvisionedWhatsappUser
import com.sova.whatsapp.application.SendMessageResult
import com.sova.whatsapp.application.WhatsappHandlingResult
import com.sova.whatsapp.application.WhatsappMessageSender
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.infrastructure.persistence.PostgresWhatsappRepository
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WhatsappAiFinancialIntegrationTest {
    @Test
    fun `mandatory Portuguese scenarios flow from WhatsApp through fake AI and local finance tools`() = runBlocking {
        EmbeddedPostgres.builder().start().use { postgres ->
            val database = PostgresDatabase(
                DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
            )
            database.migrate()
            val whatsappRepository = PostgresWhatsappRepository(database)
            val senderAddress = WhatsappAddress("whatsapp:+15550000000")
            val userAAddress = WhatsappAddress("whatsapp:+15550000001")
            val userBAddress = WhatsappAddress("whatsapp:+15550000002")
            whatsappRepository.provision(
                HouseholdProvisioning(
                    "Sova Household",
                    listOf(
                        ProvisionedWhatsappUser("User A", userAAddress),
                        ProvisionedWhatsappUser("User B", userBAddress),
                    ),
                ),
            )
            val userA = requireNotNull(whatsappRepository.findActiveUser(userAAddress))
            val userB = requireNotNull(whatsappRepository.findActiveUser(userBAddress))
            val financialService = FinancialApplicationService(PostgresFinancialRepository(database), CLOCK)
            DefaultFinancialCatalogProvisioner(financialService).provision(
                FinancialContext(userA.householdId, userA.userId),
            )
            val registry = LocalToolRegistry(financialLocalTools(financialService))
            val provider = ScenarioAiProvider(
                listOf(
                    tool(
                        "expense",
                        "create_transaction",
                        """{"type":"EXPENSE","amount":32,"description":"almoço","category":"Alimentação","occurredOn":"2026-08-25"}""",
                    ),
                    answer("Despesa de R$ 32,00 registrada em Alimentação."),
                    tool(
                        "income",
                        "create_transaction",
                        """{"type":"INCOME","amount":500,"description":"reembolso","category":"Reembolso","occurredOn":"2026-08-25"}""",
                    ),
                    answer("Receita de R$ 500,00 registrada em Reembolso."),
                    tool(
                        "expenses",
                        "get_expenses",
                        """{"from":"2026-08-01","to":"2026-08-31"}""",
                    ),
                    answer("Gastamos R$ 32,00 neste mês."),
                    tool("balance", "get_current_balance", "{}"),
                    answer("Nosso saldo registrado é R$ 468,00."),
                    tool(
                        "restaurant",
                        "get_expenses",
                        """{"from":"2026-08-01","to":"2026-08-31","category":"Restaurante"}""",
                    ),
                    answer("Não há despesas de restaurante registradas neste mês."),
                    tool(
                        "rent",
                        "create_cash_flow_entry",
                        """{"type":"EXPENSE","amount":2000,"description":"aluguel","category":"Moradia","recurrence":"MONTHLY","startDate":"2026-08-25","dayOfMonth":10}""",
                    ),
                    answer("Aluguel mensal de R$ 2.000,00 registrado para todo dia 10."),
                    tool(
                        "salary",
                        "create_cash_flow_entry",
                        """{"type":"INCOME","amount":8000,"description":"salário","category":"Salário","recurrence":"MONTHLY","startDate":"2026-08-25","dayOfMonth":5}""",
                    ),
                    answer("Receita mensal de R$ 8.000,00 registrada para todo dia 5."),
                    tool(
                        "projection",
                        "get_cash_flow_projection",
                        """{"targetDate":"2026-09-25"}""",
                    ),
                    answer("Teremos uma projeção de R$ 6.468,00 em 25 de setembro."),
                ),
            )
            val conversation = AiConversationOrchestrator(
                provider = provider,
                registry = registry,
                dispatcher = LocalToolDispatcher(registry),
                history = PostgresConversationHistory(database),
                familyRolesByUserId = mapOf(
                    userA.userId to FamilyRole.FATHER,
                    userB.userId to FamilyRole.MOTHER,
                ),
                clock = CLOCK,
            )
            val sender = ScenarioSender()
            val handler = HandleWhatsappTextMessage(
                repository = whatsappRepository,
                senderAddress = senderAddress,
                sender = sender,
                clock = CLOCK,
                conversation = conversation,
            )

            val expenseResult = handler.handle(message("SM-EXPENSE", userAAddress, "Gastei 32 reais no almoço hoje"))
            val incomeResult = handler.handle(message("SM-INCOME", userBAddress, "Recebi 500 reais de reembolso"))
            handler.handle(message("SM-EXPENSES", userAAddress, "Quanto gastamos este mês?"))
            handler.handle(message("SM-BALANCE", userBAddress, "Qual nosso saldo?"))
            handler.handle(message("SM-RESTAURANT", userAAddress, "Quanto gastamos com restaurante?"))
            handler.handle(message("SM-RENT", userAAddress, "Todo dia 10 pago R$ 2.000 de aluguel"))
            handler.handle(message("SM-SALARY", userBAddress, "Recebo R$ 8.000 todo dia 5"))
            handler.handle(message("SM-PROJECTION", userAAddress, "Quanto teremos no dia 25?"))
            val duplicate = handler.handle(message("SM-EXPENSE", userAAddress, "Gastei 32 reais no almoço hoje"))

            assertIs<WhatsappHandlingResult.Processed>(expenseResult)
            assertIs<WhatsappHandlingResult.Processed>(incomeResult)
            assertIs<WhatsappHandlingResult.Duplicate>(duplicate)
            assertEquals(8, sender.messages.size)
            assertEquals(16, provider.requests.size)
            assertEquals(
                listOf(
                    "Pai, despesa de R$ 32,00 registrada em Alimentação. Meu faro conferiu cada centavo. 🐾",
                    "Mãe, receita de R$ 500,00 registrada em Reembolso. Meu faro conferiu cada centavo. 🐾",
                    "Pai, gastamos R$ 32,00 neste mês. Meu faro conferiu cada centavo. 🐾",
                    "Mãe, nosso saldo registrado é R$ 468,00. Meu faro conferiu cada centavo. 🐾",
                    "Pai, não há despesas de restaurante registradas neste mês. Meu faro conferiu cada centavo. 🐾",
                    "Pai, aluguel mensal de R$ 2.000,00 registrado para todo dia 10. " +
                        "Meu faro conferiu cada centavo. 🐾",
                    "Mãe, receita mensal de R$ 8.000,00 registrada para todo dia 5. " +
                        "Meu faro conferiu cada centavo. 🐾",
                    "Pai, teremos uma projeção de R$ 6.468,00 em 25 de setembro. " +
                        "Meu faro conferiu cada centavo. 🐾",
                ),
                sender.messages.map { it.second },
            )

            val transactions = financialService.listTransactions(
                FinancialContext(userA.householdId, userA.userId),
            ).successValue()
            assertEquals(2, transactions.size)
            assertEquals(setOf(userA.userId, userB.userId), transactions.map { it.createdByUserId }.toSet())
            assertTrue(transactions.all { it.householdId == userA.householdId })
            val cashFlowEntries = financialService.listCashFlowEntries(
                FinancialContext(userA.householdId, userA.userId),
            ).successValue()
            assertEquals(2, cashFlowEntries.size)
            assertEquals(setOf(userA.userId, userB.userId), cashFlowEntries.map { it.createdByUserId }.toSet())
            assertTrue(cashFlowEntries.all { it.householdId == userA.householdId })

            val toolResults = provider.requests.flatMap { request ->
                request.messages.filter { it.role == AiMessageRole.TOOL }.mapNotNull { it.content }
            }
            assertTrue(toolResults.any { it.contains("\"amount\":32.00") })
            assertTrue(toolResults.any { it.contains("\"amount\":500.00") })
            assertTrue(toolResults.any { it.contains("\"balance\":468.00") })
            assertTrue(toolResults.any { it.contains("\"total\":32.00") })
            assertTrue(toolResults.any { it.contains("\"total\":0.00") && it.contains("Restaurante") })
            assertTrue(toolResults.any { it.contains("\"recurrence\":\"MONTHLY\"") && it.contains("\"dayOfMonth\":10") })
            assertTrue(toolResults.any { it.contains("\"recurrence\":\"MONTHLY\"") && it.contains("\"dayOfMonth\":5") })
            assertTrue(
                toolResults.any {
                    it.contains("\"projectedBalance\":6468.00") &&
                        it.contains("\"incomeOccurrenceCount\":1") &&
                        it.contains("\"expenseOccurrenceCount\":1")
                },
            )

            val exposedNames = provider.requests.flatMap(AiRequest::tools)
                .flatMap { definition -> definition.parameters.properties.keys }
                .toSet()
            assertFalse("userId" in exposedNames)
            assertFalse("householdId" in exposedNames)
            assertFalse("createdByUserId" in exposedNames)
            assertFalse("databaseId" in exposedNames)
            assertFalse("SQL" in exposedNames)
        }
    }

    private fun message(sid: String, from: WhatsappAddress, text: String) = InboundWhatsappMessage(
        messageSid = sid,
        accountSid = "AC00000000000000000000000000000000",
        from = from,
        to = WhatsappAddress("whatsapp:+15550000000"),
        body = text,
        numMedia = 0,
    )

    private fun tool(id: String, name: String, arguments: String) = AiResponse(
        AiMessage(
            role = AiMessageRole.ASSISTANT,
            toolCalls = listOf(AiToolCall("call-$id", name, arguments)),
        ),
    )

    private fun answer(text: String) = AiResponse(AiMessage(AiMessageRole.ASSISTANT, text))

    private companion object {
        val CLOCK = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC)
    }
}

private class ScenarioAiProvider(responses: List<AiResponse>) : AiProvider {
    private val responses = ArrayDeque(responses)
    val requests = mutableListOf<AiRequest>()

    override suspend fun complete(request: AiRequest): AiResponse {
        requests += request
        return responses.removeFirst()
    }
}

private class ScenarioSender : WhatsappMessageSender {
    val messages = mutableListOf<Pair<WhatsappAddress, String>>()

    override suspend fun send(to: WhatsappAddress, body: String): SendMessageResult {
        messages += to to body
        return SendMessageResult("SM-OUT-${messages.size}")
    }
}

private fun <T> FinancialResult<T>.successValue(): T = (this as FinancialResult.Success<T>).value
