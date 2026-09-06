package com.sova.delivery.http

import com.sova.dashboard.application.DashboardQueryService
import com.sova.dashboard.application.DashboardSessionAuthenticator
import com.sova.dashboard.application.DashboardPeriod
import com.sova.dashboard.application.DashboardTimelineFilters
import com.sova.dashboard.application.DashboardTimelineStatus
import com.sova.dashboard.application.DashboardUser
import com.sova.finance.application.CreateAccountCommand
import com.sova.finance.application.CreateCashFlowEntryCommand
import com.sova.finance.application.CreateCategoryCommand
import com.sova.finance.application.CreateTransactionCommand
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DashboardRoutesIntegrationTest {
    @Test
    fun `authenticates scopes CRUD and exposes deterministic wealth totals`() {
        EmbeddedPostgres.builder().start().use { postgres ->
            val database = PostgresDatabase(
                DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
            )
            database.migrate()
            val householdA = id("10000000-0000-0000-0000-000000000001")
            val householdB = id("10000000-0000-0000-0000-000000000002")
            val userA = id("20000000-0000-0000-0000-000000000001")
            val userB = id("20000000-0000-0000-0000-000000000002")
            provision(database, householdA, userA, householdB, userB)
            val clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC)
            val service = FinancialApplicationService(PostgresFinancialRepository(database), clock = clock)
            val contextA = FinancialContext(householdA, userA)
            val contextB = FinancialContext(householdB, userB)
            val seeded = runBlocking { seed(service, contextA, contextB) }
            val duration = Duration.ofHours(24)
            val authenticator = DashboardSessionAuthenticator(
                listOf(DashboardUser("Joao", contextA, "correct-password")),
                "a-dashboard-session-key-with-32-characters",
                duration,
                clock,
            )
            val queries = DashboardQueryService(service, clock = clock)
            assertEquals(
                1,
                runBlocking {
                    queries.timeline(
                        contextA,
                        DashboardPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")),
                        DashboardTimelineFilters(status = DashboardTimelineStatus.OVERDUE),
                    ).size
                },
            )

            testApplication {
                application {
                    configureHttp(
                        database,
                        dashboard = DashboardHttpDependencies(
                            authenticator,
                            queries,
                            service,
                            duration,
                            secureCookies = false,
                        ),
                    )
                }

                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/dashboard/overview?from=2026-08-01&to=2026-08-31").status)
                val login = client.post("/api/dashboard/session") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Joao","password":"correct-password"}""")
                }
                assertEquals(HttpStatusCode.OK, login.status)
                val cookie = requireNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')

                val overview = client.get("/api/dashboard/overview?from=2026-08-01&to=2026-08-31") {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertEquals(HttpStatusCode.OK, overview.status)
                assertContains(overview.bodyAsText(), "\"freeMoney\":\"6500.00\"")
                assertContains(overview.bodyAsText(), "\"investedMoney\":\"2500.00\"")
                assertContains(overview.bodyAsText(), "\"debt\":\"500.00\"")
                assertContains(overview.bodyAsText(), "\"netWorth\":\"8500.00\"")

                val transactionsPage = client.get(
                    "/api/dashboard/transactions?from=2026-08-01&to=2026-08-31&page=1&pageSize=2",
                ) {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertEquals(HttpStatusCode.OK, transactionsPage.status, transactionsPage.bodyAsText())
                assertContains(transactionsPage.bodyAsText(), "\"pageSize\":2")
                assertContains(transactionsPage.bodyAsText(), "\"total\":5")

                val timeline = client.get(
                    "/api/dashboard/timeline?from=2026-08-01&to=2026-08-31&status=OVERDUE&page=1&pageSize=1",
                ) {
                    header(HttpHeaders.Cookie, cookie)
                }
                val timelineBody = timeline.bodyAsText()
                assertEquals(HttpStatusCode.OK, timeline.status, timelineBody)
                assertContains(timelineBody, "\"status\":\"OVERDUE\"")
                assertContains(timelineBody, "\"pageSize\":1")

                val createdTransaction = client.post("/api/dashboard/transactions") {
                    header(HttpHeaders.Cookie, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"accountId":"${seeded.checkingId}","categoryId":"${seeded.expenseCategoryId}","type":"EXPENSE","amount":"42.50","description":"API CRUD","occurredOn":"2026-08-30"}""",
                    )
                }
                assertEquals(HttpStatusCode.Created, createdTransaction.status)
                val createdId = Regex("\"id\":\"([^\"]+)\"").find(createdTransaction.bodyAsText())!!.groupValues[1]
                val updatedTransaction = client.patch("/api/dashboard/transactions/$createdId") {
                    header(HttpHeaders.Cookie, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"accountId":"${seeded.checkingId}","categoryId":"${seeded.expenseCategoryId}","type":"EXPENSE","amount":"45.00","description":"API CRUD corrigido","occurredOn":"2026-08-30"}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, updatedTransaction.status)
                assertContains(updatedTransaction.bodyAsText(), "\"amount\":\"45.00\"")
                assertEquals(
                    HttpStatusCode.OK,
                    client.delete("/api/dashboard/transactions/$createdId") {
                        header(HttpHeaders.Cookie, cookie)
                    }.status,
                )

                val crossHouseholdDelete = client.delete("/api/dashboard/transactions/${seeded.transactionB.id}") {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertEquals(HttpStatusCode.NotFound, crossHouseholdDelete.status)

                val created = client.post("/api/dashboard/accounts") {
                    header(HttpHeaders.Cookie, cookie)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Nova corretora","type":"INVESTMENT"}""")
                }
                assertEquals(HttpStatusCode.Created, created.status)
                assertContains(created.bodyAsText(), "\"type\":\"INVESTMENT\"")
            }
        }
    }

    private suspend fun seed(
        service: FinancialApplicationService,
        contextA: FinancialContext,
        contextB: FinancialContext,
    ): SeededDashboardData {
        val checking = service.createAccount(contextA, CreateAccountCommand(AccountName.of("Conta"), AccountType.CHECKING)).value().value
        val investment = service.createAccount(contextA, CreateAccountCommand(AccountName.of("Corretora"), AccountType.INVESTMENT)).value().value
        val card = service.createAccount(contextA, CreateAccountCommand(AccountName.of("Cartao"), AccountType.CREDIT_CARD)).value().value
        val accountB = service.createAccount(contextB, CreateAccountCommand(AccountName.of("Outra conta"), AccountType.CHECKING)).value().value
        val income = service.createCategory(contextA, CreateCategoryCommand(CategoryName.of("Salario"), FinancialEntryType.INCOME)).value().value
        val expense = service.createCategory(contextA, CreateCategoryCommand(CategoryName.of("Outros"), FinancialEntryType.EXPENSE)).value().value
        val incomeB = service.createCategory(contextB, CreateCategoryCommand(CategoryName.of("Outra renda"), FinancialEntryType.INCOME)).value().value
        suspend fun transaction(accountId: UUID, type: FinancialEntryType, amount: String, categoryId: UUID, description: String, context: FinancialContext = contextA) =
            service.createTransaction(
                context,
                CreateTransactionCommand(accountId, type, Money.of(BigDecimal(amount)), description = FinancialDescription.of(description), categoryId = categoryId, occurredOn = LocalDate.parse("2026-08-20"), source = FinancialSource.DASHBOARD),
            ).value().value
        transaction(checking.id, FinancialEntryType.INCOME, "10000.00", income.id, "Salario")
        transaction(checking.id, FinancialEntryType.EXPENSE, "1000.00", expense.id, "Moradia")
        transaction(checking.id, FinancialEntryType.EXPENSE, "2500.00", expense.id, "Aporte")
        transaction(investment.id, FinancialEntryType.INCOME, "2500.00", income.id, "Aporte")
        transaction(card.id, FinancialEntryType.EXPENSE, "500.00", expense.id, "Cartao")
        service.createCashFlowEntry(
            contextA,
            CreateCashFlowEntryCommand(
                type = FinancialEntryType.EXPENSE,
                money = Money.of(BigDecimal("850.00")),
                description = FinancialDescription.of("Aluguel vencido"),
                accountId = checking.id,
                categoryId = expense.id,
                recurrence = CashFlowRecurrence.ONCE,
                startDate = LocalDate.parse("2026-08-01"),
                source = FinancialSource.DASHBOARD,
            ),
        ).value()
        return SeededDashboardData(
            transaction(accountB.id, FinancialEntryType.INCOME, "900.00", incomeB.id, "Outro household", contextB),
            checking.id,
            expense.id,
        )
    }

    private fun <T> FinancialResult<T>.value(): T = assertIs<FinancialResult.Success<T>>(this).value

    private fun provision(database: PostgresDatabase, householdA: UUID, userA: UUID, householdB: UUID, userB: UUID) {
        database.withConnection { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).apply {
                execute("insert into households (id, name) values (?, ?), (?, ?)", householdA, "A", householdB, "B")
                execute("insert into users (id, household_id, name) values (?, ?, ?), (?, ?, ?)", userA, householdA, "Joao", userB, householdB, "Outro")
            }
        }
    }

    private fun id(value: String): UUID = UUID.fromString(value)

    private data class SeededDashboardData(
        val transactionB: com.sova.finance.domain.Transaction,
        val checkingId: UUID,
        val expenseCategoryId: UUID,
    )
}
