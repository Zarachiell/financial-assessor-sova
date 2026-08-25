package com.sova.finance.insights.infrastructure.persistence

import com.sova.ai.application.AiToolCall
import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.ToolExecutionContext
import com.sova.ai.application.financialLocalTools
import com.sova.finance.application.CreateAccountCommand
import com.sova.finance.application.CreateCategoryCommand
import com.sova.finance.application.CreateTransactionCommand
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.ExpenseNature
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.finance.insights.application.ComparePeriodsQuery
import com.sova.finance.insights.application.FinancialInsightsApplicationService
import com.sova.finance.insights.application.GetBudgetDeviationsQuery
import com.sova.finance.insights.application.GetExpenseTrendQuery
import com.sova.finance.insights.application.GetFinancialHealthSnapshotQuery
import com.sova.finance.insights.application.GetFinancialSnapshotQuery
import com.sova.finance.insights.application.GetLargestExpensesQuery
import com.sova.finance.insights.application.GetRecurringExpensesQuery
import com.sova.finance.insights.domain.InsightPeriod
import com.sova.finance.insights.domain.RecurrenceConfidence
import com.sova.finance.insights.domain.TrendDirection
import com.sova.finance.insights.domain.TrendGrouping
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PostgresFinancialInsightsRepositoryIntegrationTest {
    @Test
    fun `calculates all insights in PostgreSQL and isolates households`() {
        EmbeddedPostgres.builder().start().use { postgres ->
            val database = PostgresDatabase(
                DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
            )
            database.migrate()
            val householdA = UUID.fromString("10000000-0000-0000-0000-000000000011")
            val householdB = UUID.fromString("10000000-0000-0000-0000-000000000012")
            val userA = UUID.fromString("20000000-0000-0000-0000-000000000011")
            val userB = UUID.fromString("20000000-0000-0000-0000-000000000012")
            provisionUsers(database, householdA, userA, householdB, userB)

            val clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC)
            val financialService = FinancialApplicationService(PostgresFinancialRepository(database), clock)
            val insightsService = FinancialInsightsApplicationService(
                PostgresFinancialInsightsRepository(database),
                clock,
            )
            val contextA = FinancialContext(householdA, userA)
            val contextB = FinancialContext(householdB, userB)

            runBlocking {
                val accountA = financialService.createAccount(
                    contextA,
                    CreateAccountCommand(AccountName.of("Conta A"), AccountType.CHECKING),
                ).value().value
                val accountB = financialService.createAccount(
                    contextB,
                    CreateAccountCommand(AccountName.of("Conta B"), AccountType.CHECKING),
                ).value().value
                val salaryA = category(financialService, contextA, "Salário", FinancialEntryType.INCOME)
                val restaurantA = category(
                    financialService,
                    contextA,
                    "Restaurante",
                    FinancialEntryType.EXPENSE,
                    ExpenseNature.DISCRETIONARY,
                )
                val subscriptionsA = category(
                    financialService,
                    contextA,
                    "Assinaturas",
                    FinancialEntryType.EXPENSE,
                    ExpenseNature.DISCRETIONARY,
                )
                val housingA = category(
                    financialService,
                    contextA,
                    "Moradia",
                    FinancialEntryType.EXPENSE,
                    ExpenseNature.FIXED,
                )
                val groceriesA = category(
                    financialService,
                    contextA,
                    "Supermercado",
                    FinancialEntryType.EXPENSE,
                )
                val salaryB = category(financialService, contextB, "Salário", FinancialEntryType.INCOME)

                listOf(
                    MonthData("2026-05", "300", "100"),
                    MonthData("2026-06", "400", "102"),
                    MonthData("2026-07", "500", "101"),
                    MonthData("2026-08", "900", "100"),
                ).forEachIndexed { index, month ->
                    transaction(
                        financialService,
                        contextA,
                        accountA.id,
                        salaryA.id,
                        FinancialEntryType.INCOME,
                        "10000",
                        "Salário",
                        "${month.month}-05",
                        "salary-$index",
                    )
                    transaction(
                        financialService,
                        contextA,
                        accountA.id,
                        restaurantA.id,
                        FinancialEntryType.EXPENSE,
                        month.restaurant,
                        "Restaurante",
                        "${month.month}-15",
                        "restaurant-$index",
                    )
                    transaction(
                        financialService,
                        contextA,
                        accountA.id,
                        subscriptionsA.id,
                        FinancialEntryType.EXPENSE,
                        month.subscription,
                        "Netflix ${index + 5}/2026",
                        "${month.month}-10",
                        "netflix-$index",
                    )
                    transaction(
                        financialService,
                        contextA,
                        accountA.id,
                        housingA.id,
                        FinancialEntryType.EXPENSE,
                        "2000",
                        "Aluguel",
                        "${month.month}-01",
                        "rent-$index",
                    )
                }
                transaction(
                    financialService,
                    contextA,
                    accountA.id,
                    groceriesA.id,
                    FinancialEntryType.EXPENSE,
                    "800",
                    "Compra do mês",
                    "2026-08-20",
                    "groceries-august",
                )
                transaction(
                    financialService,
                    contextB,
                    accountB.id,
                    salaryB.id,
                    FinancialEntryType.INCOME,
                    "99999",
                    "Outro household",
                    "2026-08-05",
                    "other-household",
                )

                val august = InsightPeriod(date("2026-08-01"), date("2026-08-25"))
                val july = InsightPeriod(date("2026-07-01"), date("2026-07-31"))
                val history = InsightPeriod(date("2026-05-01"), date("2026-08-25"))

                val snapshot = insightsService.getFinancialSnapshot(
                    contextA,
                    GetFinancialSnapshotQuery(august),
                ).value()
                assertEquals(decimal("10000.00"), snapshot.totals.totalIncome)
                assertEquals(decimal("3800.00"), snapshot.totals.totalExpenses)
                assertEquals(decimal("6200.00"), snapshot.totals.netCashFlow)
                assertEquals(5, snapshot.totals.transactionCount)
                assertEquals("Moradia", snapshot.topExpenseCategories.first().category)
                assertEquals(decimal("2000.00"), snapshot.largestExpenses.first().amount)

                val comparison = insightsService.comparePeriods(
                    contextA,
                    ComparePeriodsQuery(august, july),
                ).value()
                assertEquals(decimal("1199.00"), comparison.expenses.absoluteChange)
                assertEquals(decimal("46.10"), comparison.expenses.percentageChange)
                assertEquals(decimal("400.00"), comparison.categoryChanges.single {
                    it.category == "Restaurante"
                }.absoluteChange)

                val monthlyTrend = insightsService.getExpenseTrend(
                    contextA,
                    GetExpenseTrendQuery(history, TrendGrouping.MONTH),
                ).value()
                assertEquals(4, monthlyTrend.points.size)
                assertEquals(TrendDirection.UP, monthlyTrend.direction)

                val weeklyTrend = insightsService.getExpenseTrend(
                    contextA,
                    GetExpenseTrendQuery(august, TrendGrouping.WEEK, restaurantA.id, "Restaurante"),
                ).value()
                assertTrue(weeklyTrend.points.isNotEmpty())
                assertEquals(decimal("900.00"), weeklyTrend.points.fold(BigDecimal.ZERO) { total, point ->
                    total + point.total
                })

                val largest = insightsService.getLargestExpenses(
                    contextA,
                    GetLargestExpensesQuery(history, limit = 3),
                ).value()
                assertEquals(3, largest.size)
                assertTrue(largest.zipWithNext().all { (first, second) -> first.amount >= second.amount })
                assertFailsWith<IllegalArgumentException> { GetLargestExpensesQuery(history, limit = 21) }

                val recurring = insightsService.getRecurringExpenses(
                    contextA,
                    GetRecurringExpensesQuery(history),
                ).value()
                assertEquals(setOf("Aluguel", "Netflix 8/2026"), recurring.map { it.description }.toSet())
                assertTrue(recurring.all { it.confidence == RecurrenceConfidence.HIGH })

                val deviations = insightsService.getBudgetDeviations(
                    contextA,
                    GetBudgetDeviationsQuery(august, decimal("1000")),
                ).value()
                val restaurantDeviation = deviations.deviations.single { it.category == "Restaurante" }
                assertEquals(decimal("400.00"), restaurantDeviation.baselineAverage)
                assertEquals(decimal("500.00"), restaurantDeviation.absoluteDeviation)
                assertEquals(decimal("125.00"), restaurantDeviation.percentageDeviation)
                assertEquals(decimal("300.00"), deviations.savingsPlan?.plannedAmount)
                assertEquals(decimal("700.00"), deviations.savingsPlan?.remainingAmount)

                val health = insightsService.getFinancialHealthSnapshot(
                    contextA,
                    GetFinancialHealthSnapshotQuery(history),
                ).value()
                assertTrue(requireNotNull(health.savingsRate) > BigDecimal.ZERO)
                assertTrue(requireNotNull(health.fixedExpenseRatio) > BigDecimal.ZERO)
                assertTrue(requireNotNull(health.discretionaryExpenseRatio) > BigDecimal.ZERO)
                assertEquals(decimal("4712.00"), health.monthEndProjection.projectedExpenses)
                assertEquals(decimal("27785.00"), health.monthEndProjection.projectedEndBalance)

                val otherHousehold = insightsService.getFinancialSnapshot(
                    contextB,
                    GetFinancialSnapshotQuery(august),
                ).value()
                assertEquals(decimal("99999.00"), otherHousehold.totals.totalIncome)
                assertEquals(decimal("0.00"), otherHousehold.totals.totalExpenses)
                assertTrue(otherHousehold.largestExpenses.isEmpty())
                assertEquals(
                    decimal("0.00"),
                    insightsService.comparePeriods(contextB, ComparePeriodsQuery(august, july))
                        .value().expenses.current,
                )
                assertTrue(
                    insightsService.getExpenseTrend(
                        contextB,
                        GetExpenseTrendQuery(history, TrendGrouping.MONTH),
                    ).value().points.all { it.total == decimal("0.00") },
                )
                assertTrue(
                    insightsService.getLargestExpenses(
                        contextB,
                        GetLargestExpensesQuery(history, 5),
                    ).value().isEmpty(),
                )
                assertTrue(
                    insightsService.getRecurringExpenses(
                        contextB,
                        GetRecurringExpensesQuery(history),
                    ).value().isEmpty(),
                )
                assertTrue(
                    insightsService.getBudgetDeviations(
                        contextB,
                        GetBudgetDeviationsQuery(august),
                    ).value().deviations.isEmpty(),
                )
                assertEquals(
                    decimal("1.0000"),
                    insightsService.getFinancialHealthSnapshot(
                        contextB,
                        GetFinancialHealthSnapshotQuery(history),
                    ).value().savingsRate,
                )

                assertInsightTools(financialService, insightsService, contextA)
            }
        }
    }

    private suspend fun assertInsightTools(
        financialService: FinancialApplicationService,
        insightsService: FinancialInsightsApplicationService,
        context: FinancialContext,
    ) {
        val registry = LocalToolRegistry(financialLocalTools(financialService, insightsService))
        val dispatcher = LocalToolDispatcher(registry)
        val executionContext = ToolExecutionContext(context, "SM-INSIGHTS")
        val expectedNames = setOf(
            "get_financial_snapshot",
            "compare_periods",
            "get_expense_trend",
            "get_largest_expenses",
            "get_recurring_expenses",
            "get_budget_deviations",
            "get_financial_health_snapshot",
        )
        assertTrue(expectedNames.all { name -> registry.definitions.any { it.name == name } })

        val calls = mapOf(
            "get_financial_snapshot" to """{"from":"2026-08-01","to":"2026-08-25"}""",
            "compare_periods" to
                """{"currentFrom":"2026-08-01","currentTo":"2026-08-25","comparisonFrom":"2026-07-01","comparisonTo":"2026-07-31"}""",
            "get_expense_trend" to
                """{"from":"2026-05-01","to":"2026-08-25","grouping":"MONTH"}""",
            "get_largest_expenses" to
                """{"from":"2026-05-01","to":"2026-08-25","limit":3}""",
            "get_recurring_expenses" to """{"from":"2026-05-01","to":"2026-08-25"}""",
            "get_budget_deviations" to
                """{"from":"2026-08-01","to":"2026-08-25","savingsTarget":1000}""",
            "get_financial_health_snapshot" to
                """{"from":"2026-05-01","to":"2026-08-25"}""",
        )
        val responses = calls.mapValues { (name, arguments) ->
            val result = dispatcher.dispatch(AiToolCall("call-$name", name, arguments), executionContext)
            assertTrue(result.successful, "$name must execute successfully")
            JSON.parseToJsonElement(result.content).jsonObject
        }
        assertEquals("6200.00", responses.getValue("get_financial_snapshot").getValue("netCashFlow").jsonPrimitive.content)
        assertEquals(
            "46.10",
            responses.getValue("compare_periods").getValue("expensePercentageChange").jsonPrimitive.content,
        )
        assertTrue(responses.values.none { response ->
            response.toString().contains("Infinity") || response.toString().contains("NaN")
        })
        assertEquals("MONTH", responses.getValue("get_expense_trend").getValue("grouping").jsonPrimitive.content)
        assertEquals("3", responses.getValue("get_largest_expenses").getValue("count").jsonPrimitive.content)
        assertEquals("2", responses.getValue("get_recurring_expenses").getValue("count").jsonPrimitive.content)
        assertTrue("savingsPlan" in responses.getValue("get_budget_deviations"))
        assertTrue(responses.getValue("get_financial_health_snapshot").getValue("score").toString() == "null")

        val excessive = dispatcher.dispatch(
            AiToolCall(
                "call-limit",
                "get_largest_expenses",
                """{"from":"2026-05-01","to":"2026-08-25","limit":21}""",
            ),
            executionContext,
        )
        assertTrue(!excessive.successful)
        assertTrue(excessive.content.contains("invalid_arguments"))
    }

    private suspend fun category(
        service: FinancialApplicationService,
        context: FinancialContext,
        name: String,
        type: FinancialEntryType,
        nature: ExpenseNature = ExpenseNature.UNCLASSIFIED,
    ) = service.createCategory(
        context,
        CreateCategoryCommand(CategoryName.of(name), type, nature),
    ).value().value

    private suspend fun transaction(
        service: FinancialApplicationService,
        context: FinancialContext,
        accountId: UUID,
        categoryId: UUID,
        type: FinancialEntryType,
        amount: String,
        description: String,
        occurredOn: String,
        reference: String,
    ) {
        service.createTransaction(
            context,
            CreateTransactionCommand(
                accountId = accountId,
                type = type,
                money = Money.of(decimal(amount)),
                description = FinancialDescription.of(description),
                categoryId = categoryId,
                occurredOn = date(occurredOn),
                source = FinancialSource.DASHBOARD,
                sourceReferenceId = SourceReferenceId.of(reference),
            ),
        ).value()
    }

    private fun provisionUsers(
        database: PostgresDatabase,
        householdA: UUID,
        userA: UUID,
        householdB: UUID,
        userB: UUID,
    ) {
        database.withConnection { connection ->
            val sql = DSL.using(connection, SQLDialect.POSTGRES)
            sql.execute("INSERT INTO households (id, name) VALUES (?, 'A'), (?, 'B')", householdA, householdB)
            sql.execute(
                """
                INSERT INTO users (id, household_id, name, active)
                VALUES (?, ?, 'A', TRUE), (?, ?, 'B', TRUE)
                """.trimIndent(),
                userA,
                householdA,
                userB,
                householdB,
            )
        }
    }
}

private data class MonthData(val month: String, val restaurant: String, val subscription: String)

private fun date(value: String): LocalDate = LocalDate.parse(value)

private fun decimal(value: String): BigDecimal = BigDecimal(value)

private fun <T> FinancialResult<T>.value(): T = assertIs<FinancialResult.Success<T>>(this).value

private val JSON = Json { ignoreUnknownKeys = true }
