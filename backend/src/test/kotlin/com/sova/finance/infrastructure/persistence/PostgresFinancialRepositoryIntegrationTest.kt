package com.sova.finance.infrastructure.persistence

import com.sova.finance.application.CreateAccountCommand
import com.sova.finance.application.CreateCashFlowEntryCommand
import com.sova.finance.application.CreateCategoryCommand
import com.sova.finance.application.CreateTransactionCommand
import com.sova.finance.application.Creation
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.application.TransactionFilters
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PostgresFinancialRepositoryIntegrationTest {
    @Test
    fun `persists isolated financial core and enforces database invariants`() {
        EmbeddedPostgres.builder().start().use { postgres ->
            val database = PostgresDatabase(
                DatabaseConfig(
                    url = postgres.getJdbcUrl("postgres", "postgres"),
                    user = "postgres",
                    password = "",
                ),
            )
            database.migrate()

            val householdA = id("10000000-0000-0000-0000-000000000001")
            val householdB = id("10000000-0000-0000-0000-000000000002")
            val userA = id("20000000-0000-0000-0000-000000000001")
            val userB = id("20000000-0000-0000-0000-000000000002")
            provisionIdentityData(database, householdA, userA, householdB, userB)

            val repository = PostgresFinancialRepository(database)
            val service = FinancialApplicationService(
                repository = repository,
                clock = Clock.fixed(Instant.parse("2026-08-24T20:00:00Z"), ZoneOffset.UTC),
            )
            val contextA = FinancialContext(householdA, userA)
            val contextB = FinancialContext(householdB, userB)

            runBlocking {
                val account = service.createAccount(
                    contextA,
                    CreateAccountCommand(AccountName.of("Joint checking"), AccountType.CHECKING),
                ).createdValue()
                val expenseCategory = service.createCategory(
                    contextA,
                    CreateCategoryCommand(CategoryName.of("Food"), FinancialEntryType.EXPENSE),
                ).createdValue()
                val incomeCategory = service.createCategory(
                    contextA,
                    CreateCategoryCommand(CategoryName.of("Salary"), FinancialEntryType.INCOME),
                ).createdValue()
                val categoryB = service.createCategory(
                    contextB,
                    CreateCategoryCommand(CategoryName.of("Other household"), FinancialEntryType.EXPENSE),
                ).createdValue()

                val transactionCommand = CreateTransactionCommand(
                    accountId = account.id,
                    type = FinancialEntryType.EXPENSE,
                    money = Money.of(BigDecimal("87.45")),
                    description = FinancialDescription.of("Groceries"),
                    categoryId = expenseCategory.id,
                    occurredOn = LocalDate.parse("2026-08-23"),
                    source = FinancialSource.WHATSAPP_TEXT,
                    sourceReferenceId = SourceReferenceId.of("SM-FINANCE-1"),
                )
                val first = service.createTransaction(contextA, transactionCommand).creation()
                val duplicate = service.createTransaction(contextA, transactionCommand).creation()

                assertTrue(first.created)
                assertFalse(duplicate.created)
                assertFalse(repository.insertTransaction(first.value.copy(id = UUID.randomUUID())))
                assertEquals(first.value.id, duplicate.value.id)
                assertEquals(BigDecimal("87.45"), first.value.money.amount)
                assertEquals(LocalDate.parse("2026-08-23"), first.value.occurredOn)
                assertEquals(Instant.parse("2026-08-24T20:00:00Z"), first.value.createdAt)
                assertEquals(
                    listOf(first.value),
                    service.listTransactions(
                        contextA,
                        TransactionFilters(
                            occurredFrom = LocalDate.parse("2026-08-23"),
                            occurredTo = LocalDate.parse("2026-08-23"),
                            type = FinancialEntryType.EXPENSE,
                            accountId = account.id,
                            categoryId = expenseCategory.id,
                        ),
                    ).value(),
                )
                assertTrue(service.listTransactions(contextB).value().isEmpty())

                val cashFlowCommand = CreateCashFlowEntryCommand(
                    type = FinancialEntryType.INCOME,
                    money = Money.of(BigDecimal("6000")),
                    description = FinancialDescription.of("September salary"),
                    categoryId = incomeCategory.id,
                    startDate = LocalDate.parse("2026-09-01"),
                    source = FinancialSource.DASHBOARD,
                    sourceReferenceId = SourceReferenceId.of("cash-flow-1"),
                )
                val cashFlow = service.createCashFlowEntry(contextA, cashFlowCommand).creation()
                val cashFlowDuplicate = service.createCashFlowEntry(contextA, cashFlowCommand).creation()
                assertTrue(cashFlow.created)
                assertFalse(cashFlowDuplicate.created)
                assertFalse(repository.insertCashFlowEntry(cashFlow.value.copy(id = UUID.randomUUID())))
                assertEquals(listOf(cashFlow.value), service.listCashFlowEntries(contextA).value())

                assertDatabaseConstraints(
                    database = database,
                    householdA = householdA,
                    userA = userA,
                    userB = userB,
                    accountId = account.id,
                    expenseCategoryId = expenseCategory.id,
                    categoryBId = categoryB.id,
                )
            }
        }
    }

    private fun provisionIdentityData(
        database: PostgresDatabase,
        householdA: UUID,
        userA: UUID,
        householdB: UUID,
        userB: UUID,
    ) {
        database.withConnection { connection ->
            val sql = DSL.using(connection, SQLDialect.POSTGRES)
            sql.execute("INSERT INTO households (id, name) VALUES (?, ?), (?, ?)", householdA, "A", householdB, "B")
            sql.execute(
                "INSERT INTO users (id, household_id, name, active) VALUES (?, ?, ?, TRUE), (?, ?, ?, TRUE)",
                userA,
                householdA,
                "User A",
                userB,
                householdB,
                "User B",
            )
        }
    }

    private fun assertDatabaseConstraints(
        database: PostgresDatabase,
        householdA: UUID,
        userA: UUID,
        userB: UUID,
        accountId: UUID,
        expenseCategoryId: UUID,
        categoryBId: UUID,
    ) {
        database.withConnection { connection ->
            val sql = DSL.using(connection, SQLDialect.POSTGRES)
            assertFailsWith<DataAccessException> {
                sql.execute(
                    """
                    INSERT INTO accounts
                        (id, household_id, created_by_user_id, name, type, currency, active, created_at, updated_at)
                    VALUES (?, ?, ?, 'Cross household', 'CASH', 'BRL', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    householdA,
                    userB,
                )
            }
            assertFailsWith<DataAccessException> {
                insertTransaction(
                    sql = sql,
                    householdId = householdA,
                    userId = userA,
                    accountId = accountId,
                    categoryId = categoryBId,
                    amount = BigDecimal("1.00"),
                    type = "EXPENSE",
                    reference = "cross-category",
                )
            }
            assertFailsWith<DataAccessException> {
                insertTransaction(
                    sql = sql,
                    householdId = householdA,
                    userId = userA,
                    accountId = accountId,
                    categoryId = expenseCategoryId,
                    amount = BigDecimal("-1.00"),
                    type = "EXPENSE",
                    reference = "negative",
                )
            }
            assertFailsWith<DataAccessException> {
                insertTransaction(
                    sql = sql,
                    householdId = householdA,
                    userId = userA,
                    accountId = accountId,
                    categoryId = expenseCategoryId,
                    amount = BigDecimal("1.00"),
                    type = "INCOME",
                    reference = "wrong-type",
                )
            }
            assertFailsWith<DataAccessException> {
                insertTransaction(
                    sql = sql,
                    householdId = householdA,
                    userId = userA,
                    accountId = accountId,
                    categoryId = expenseCategoryId,
                    amount = BigDecimal("1.00"),
                    type = "EXPENSE",
                    reference = "wrong-currency",
                    currency = "USD",
                )
            }
            assertFailsWith<DataAccessException> {
                sql.execute(
                    """
                    INSERT INTO cash_flow_entries
                        (id, household_id, created_by_user_id, type, amount, currency, description, category_id,
                         recurrence, start_date, end_date, active, source, source_reference_id, created_at, updated_at)
                    VALUES (?, ?, ?, 'EXPENSE', 1.00, 'BRL', 'Invalid recurrence', ?, 'MONTHLY',
                            DATE '2026-09-01', NULL, TRUE, 'DASHBOARD', 'monthly', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    householdA,
                    userA,
                    expenseCategoryId,
                )
            }
            assertEquals(
                1L,
                sql.fetchOne("SELECT count(*) AS total FROM transactions WHERE household_id = ?", householdA)
                    ?.get("total", Long::class.java),
            )
        }
    }

    private fun insertTransaction(
        sql: org.jooq.DSLContext,
        householdId: UUID,
        userId: UUID,
        accountId: UUID,
        categoryId: UUID,
        amount: BigDecimal,
        type: String,
        reference: String,
        currency: String = "BRL",
    ) {
        sql.execute(
            """
            INSERT INTO transactions
                (id, household_id, created_by_user_id, account_id, type, amount, currency, description,
                 category_id, occurred_on, source, source_reference_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'Constraint test', ?, DATE '2026-08-24',
                    'DASHBOARD', ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
            UUID.randomUUID(),
            householdId,
            userId,
            accountId,
            type,
            amount,
            currency,
            categoryId,
            reference,
        )
    }
}

private fun <T> FinancialResult<T>.value(): T = assertIs<FinancialResult.Success<T>>(this).value

private fun <T> FinancialResult<Creation<T>>.creation(): Creation<T> = value()

private fun <T> FinancialResult<Creation<T>>.createdValue(): T = creation().also { assertTrue(it.created) }.value

private fun id(value: String): UUID = UUID.fromString(value)
