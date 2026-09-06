package com.sova.delivery.http

import com.sova.dashboard.application.DashboardPeriod
import com.sova.dashboard.application.DashboardPrincipal
import com.sova.dashboard.application.DashboardQueryRejected
import com.sova.dashboard.application.DashboardQueryService
import com.sova.dashboard.application.DashboardSessionAuthenticator
import com.sova.dashboard.application.DashboardTimelineFilters
import com.sova.dashboard.application.DashboardTimelineItem
import com.sova.dashboard.application.DashboardTimelineStatus
import com.sova.finance.application.CashFlowEntryFilters
import com.sova.finance.application.CashFlowOccurrenceStatus
import com.sova.finance.application.CreateCashFlowEntryCommand
import com.sova.finance.application.CreateAccountCommand
import com.sova.finance.application.CreateCategoryCommand
import com.sova.finance.application.CreateTransactionCommand
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialRejection
import com.sova.finance.application.FinancialResult
import com.sova.finance.application.GetCashFlowOccurrencesQuery
import com.sova.finance.application.TransactionFilters
import com.sova.finance.application.UpdateCashFlowEntryCommand
import com.sova.finance.application.UpdateTransactionCommand
import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.Category
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.ExpenseNature
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.Transaction
import com.sova.receipt.application.ReceiptAccessLinkSigner
import com.sova.receipt.application.SettlementReceiptService
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

data class DashboardHttpDependencies(
    val authenticator: DashboardSessionAuthenticator,
    val queries: DashboardQueryService,
    val financialService: FinancialApplicationService,
    val sessionDuration: Duration,
    val secureCookies: Boolean,
    val receiptService: SettlementReceiptService? = null,
    val receiptLinkSigner: ReceiptAccessLinkSigner? = null,
)

fun Route.dashboardRoutes(dependencies: DashboardHttpDependencies) {
    route("/api/dashboard") {
        post("/session") {
            val request = call.receive<DashboardLoginRequest>()
            val token = dependencies.authenticator.login(request.name, request.password)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, DashboardErrorResponse("INVALID_CREDENTIALS"))
            call.response.cookies.append(
                Cookie(
                    name = DASHBOARD_COOKIE,
                    value = token,
                    maxAge = dependencies.sessionDuration.seconds.toInt(),
                    path = "/",
                    secure = dependencies.secureCookies,
                    httpOnly = true,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
            call.respond(DashboardSessionResponse(request.name.trim()))
        }

        delete("/session") {
            call.response.cookies.append(
                Cookie(DASHBOARD_COOKIE, "", maxAge = 0, path = "/", httpOnly = true),
            )
            call.respond(HttpStatusCode.NoContent)
        }

        get("/session") {
            val principal = call.principal(dependencies) ?: return@get
            call.respond(DashboardSessionResponse(principal.name))
        }

        get("/overview") {
            val principal = call.principal(dependencies) ?: return@get
            val period = call.dashboardPeriod() ?: return@get
            call.respondQuery { DashboardOverviewResponse.from(dependencies.queries.overview(principal.context, period)) }
        }

        get("/catalog") {
            val principal = call.principal(dependencies) ?: return@get
            call.respondFinancial(
                combineCatalog(
                    dependencies.financialService.listAccounts(principal.context),
                    dependencies.financialService.listCategories(principal.context),
                ),
            )
        }

        post("/accounts") {
            val principal = call.principal(dependencies) ?: return@post
            val request = call.receive<AccountMutationRequest>()
            call.respondFinancial(
                dependencies.financialService.createAccount(
                    principal.context,
                    CreateAccountCommand(AccountName.of(request.name), AccountType.valueOf(request.type.uppercase())),
                ).map { AccountResponse.from(it.value) },
                HttpStatusCode.Created,
            )
        }

        post("/categories") {
            val principal = call.principal(dependencies) ?: return@post
            val request = call.receive<CategoryMutationRequest>()
            call.respondFinancial(
                dependencies.financialService.createCategory(
                    principal.context,
                    CreateCategoryCommand(
                        CategoryName.of(request.name),
                        FinancialEntryType.valueOf(request.type.uppercase()),
                        request.expenseNature?.let { ExpenseNature.valueOf(it.uppercase()) }
                            ?: ExpenseNature.UNCLASSIFIED,
                    ),
                ).map { CategoryResponse.from(it.value) },
                HttpStatusCode.Created,
            )
        }

        get("/transactions") {
            val principal = call.principal(dependencies) ?: return@get
            val filters = TransactionFilters(
                occurredFrom = call.request.queryParameters["from"]?.toLocalDateOrNull(),
                occurredTo = call.request.queryParameters["to"]?.toLocalDateOrNull(),
                type = call.request.queryParameters["type"]?.toEntryTypeOrNull(),
                accountId = call.request.queryParameters["accountId"]?.toUuidOrNull(),
                categoryId = call.request.queryParameters["categoryId"]?.toUuidOrNull(),
            )
            call.respondQuery {
                dependencies.queries.transactions(principal.context, filters)
                    .sortedWith(compareByDescending<Transaction> { it.occurredOn }.thenByDescending { it.createdAt })
                    .paged(call).map(TransactionResponse::from)
            }
        }

        post("/transactions") {
            val principal = call.principal(dependencies) ?: return@post
            val request = call.receive<TransactionMutationRequest>()
            call.respondFinancial(
                dependencies.financialService.createTransaction(
                    principal.context,
                    request.toCreateCommand(),
                ).map { TransactionResponse.from(it.value) },
                HttpStatusCode.Created,
            )
        }

        patch("/transactions/{id}") {
            val principal = call.principal(dependencies) ?: return@patch
            val id = call.requirePathUuid("id") ?: return@patch
            val request = call.receive<TransactionMutationRequest>()
            call.respondFinancial(
                dependencies.financialService.updateTransaction(principal.context, id, request.toUpdateCommand())
                    .map(TransactionResponse::from),
            )
        }

        delete("/transactions/{id}") {
            val principal = call.principal(dependencies) ?: return@delete
            val id = call.requirePathUuid("id") ?: return@delete
            call.respondFinancial(
                dependencies.financialService.deleteTransaction(principal.context, id).map(TransactionResponse::from),
            )
        }

        get("/cash-flow") {
            val principal = call.principal(dependencies) ?: return@get
            val filters = CashFlowEntryFilters(
                type = call.request.queryParameters["type"]?.toEntryTypeOrNull(),
                active = call.request.queryParameters["active"]?.toBooleanStrictOrNull(),
                from = call.request.queryParameters["from"]?.toLocalDateOrNull(),
                to = call.request.queryParameters["to"]?.toLocalDateOrNull(),
                accountId = call.request.queryParameters["accountId"]?.toUuidOrNull(),
            )
            call.respondQuery {
                dependencies.queries.cashFlowEntries(principal.context, filters)
                    .sortedBy(CashFlowEntry::startDate).paged(call).map(CashFlowEntryResponse::from)
            }
        }

        post("/cash-flow") {
            val principal = call.principal(dependencies) ?: return@post
            val request = call.receive<CashFlowMutationRequest>()
            call.respondFinancial(
                dependencies.financialService.createCashFlowEntry(principal.context, request.toCreateCommand())
                    .map { CashFlowEntryResponse.from(it.value) },
                HttpStatusCode.Created,
            )
        }

        patch("/cash-flow/{id}") {
            val principal = call.principal(dependencies) ?: return@patch
            val id = call.requirePathUuid("id") ?: return@patch
            val request = call.receive<CashFlowMutationRequest>()
            call.respondFinancial(
                dependencies.financialService.updateCashFlowEntry(principal.context, id, request.toUpdateCommand())
                    .map(CashFlowEntryResponse::from),
            )
        }

        delete("/cash-flow/{id}") {
            val principal = call.principal(dependencies) ?: return@delete
            val id = call.requirePathUuid("id") ?: return@delete
            call.respondFinancial(
                dependencies.financialService.deleteCashFlowEntry(principal.context, id).map(CashFlowEntryResponse::from),
            )
        }

        get("/occurrences") {
            val principal = call.principal(dependencies) ?: return@get
            val period = call.dashboardPeriod() ?: return@get
            val status = call.request.queryParameters["status"]?.let {
                runCatching { CashFlowOccurrenceStatus.valueOf(it.uppercase()) }.getOrNull()
            }
            call.respondQuery {
                dependencies.queries.occurrences(
                    principal.context,
                    GetCashFlowOccurrencesQuery(
                        period.from,
                        period.to,
                        type = call.request.queryParameters["type"]?.toEntryTypeOrNull(),
                        status = status,
                    ),
                ).paged(call).map(CashFlowOccurrenceResponse::from)
            }
        }

        get("/timeline") {
            val principal = call.principal(dependencies) ?: return@get
            val period = call.dashboardPeriod() ?: return@get
            val status = call.request.queryParameters["status"]?.let {
                runCatching { DashboardTimelineStatus.valueOf(it.uppercase()) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_STATUS"))
            }
            val type = call.request.queryParameters["type"]?.let {
                it.toEntryTypeOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_TYPE"))
            }
            val categoryId = call.request.queryParameters["categoryId"]?.let {
                it.toUuidOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_CATEGORY"))
            }
            val recurring = call.request.queryParameters["recurring"]?.let {
                it.toBooleanStrictOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_RECURRING"))
            }
            try {
                val response = dependencies.queries.timeline(
                    principal.context,
                    period,
                    DashboardTimelineFilters(type, status, categoryId, recurring),
                ).paged(call).map(TimelineItemResponse::from)
                call.respond(response)
            } catch (rejected: DashboardQueryRejected) {
                call.respond(rejected.rejection.httpStatus(), DashboardErrorResponse(rejected.rejection.name))
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_REQUEST"))
            }
        }

        get("/settlements") {
            val principal = call.principal(dependencies) ?: return@get
            val period = call.dashboardPeriod() ?: return@get
            call.respondQuery {
                dependencies.queries.settlements(principal.context, period).paged(call).map { details ->
                    SettlementResponse(
                        id = details.receipt.settlement.id.toString(),
                        cashFlowEntryId = details.receipt.settlement.cashFlowEntryId.toString(),
                        description = details.receipt.transaction.description.value,
                        plannedAmount = details.plannedMoney.amount.moneyString(),
                        settledAmount = details.receipt.transaction.money.amount.moneyString(),
                        variance = details.variance.moneyString(),
                        scheduledOn = details.receipt.settlement.scheduledOn.toString(),
                        paidOn = details.receipt.transaction.occurredOn.toString(),
                        settledAt = details.receipt.settlement.settledAt.toString(),
                    )
                }
            }
        }

        get("/settlements/{id}/receipts") {
            val principal = call.principal(dependencies) ?: return@get
            val settlementId = call.requirePathUuid("id") ?: return@get
            val service = dependencies.receiptService
                ?: return@get call.respond(emptyList<SettlementReceiptResponse>())
            val signer = dependencies.receiptLinkSigner
                ?: return@get call.respond(emptyList<SettlementReceiptResponse>())
            call.respond(
                service.list(principal.context, settlementId).map { receipt ->
                    SettlementReceiptResponse(
                        receipt.id.toString(),
                        receipt.caption,
                        receipt.contentType,
                        receipt.sizeBytes,
                        receipt.linkedAt.toString(),
                        signer.create(receipt.id),
                    )
                },
            )
        }
    }
}

private suspend fun ApplicationCall.principal(dependencies: DashboardHttpDependencies): DashboardPrincipal? {
    val principal = dependencies.authenticator.authenticate(request.cookies[DASHBOARD_COOKIE])
    if (principal == null) respond(HttpStatusCode.Unauthorized, DashboardErrorResponse("UNAUTHENTICATED"))
    return principal
}

private suspend fun ApplicationCall.dashboardPeriod(): DashboardPeriod? {
    val from = request.queryParameters["from"]?.toLocalDateOrNull()
    val to = request.queryParameters["to"]?.toLocalDateOrNull()
    if (from == null || to == null || from.isAfter(to)) {
        respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_PERIOD"))
        return null
    }
    return DashboardPeriod(from, to)
}

private suspend inline fun <reified T : Any> ApplicationCall.respondQuery(crossinline block: suspend () -> T) {
    try {
        val response: T = block()
        respond(response)
    } catch (rejected: DashboardQueryRejected) {
        respond(rejected.rejection.httpStatus(), DashboardErrorResponse(rejected.rejection.name))
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_REQUEST"))
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondFinancial(
    result: FinancialResult<T>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
) {
    when (result) {
        is FinancialResult.Success -> respond(successStatus, result.value)
        is FinancialResult.Rejected -> respond(result.reason.httpStatus(), DashboardErrorResponse(result.reason.name))
    }
}

private fun FinancialRejection.httpStatus(): HttpStatusCode = when (this) {
    FinancialRejection.UNAUTHORIZED_CONTEXT -> HttpStatusCode.Forbidden
    FinancialRejection.TRANSACTION_NOT_FOUND,
    FinancialRejection.CASH_FLOW_ENTRY_NOT_FOUND,
    FinancialRejection.CASH_FLOW_OCCURRENCE_NOT_FOUND,
    FinancialRejection.CASH_FLOW_SETTLEMENT_NOT_FOUND,
    FinancialRejection.ACCOUNT_NOT_FOUND,
    FinancialRejection.CATEGORY_NOT_FOUND,
    -> HttpStatusCode.NotFound
    FinancialRejection.PERSISTENCE_CONFLICT,
    FinancialRejection.DUPLICATE_ACCOUNT_NAME,
    FinancialRejection.DUPLICATE_CATEGORY_NAME,
    FinancialRejection.CASH_FLOW_ENTRY_HAS_SETTLEMENTS,
    FinancialRejection.RECORD_MANAGED_BY_CASH_FLOW,
    -> HttpStatusCode.Conflict
    else -> HttpStatusCode.UnprocessableEntity
}

private fun <T, R> FinancialResult<T>.map(transform: (T) -> R): FinancialResult<R> = when (this) {
    is FinancialResult.Success -> FinancialResult.Success(transform(value))
    is FinancialResult.Rejected -> this
}

private fun combineCatalog(
    accounts: FinancialResult<List<Account>>,
    categories: FinancialResult<List<Category>>,
): FinancialResult<DashboardCatalogResponse> = when {
    accounts is FinancialResult.Rejected -> accounts
    categories is FinancialResult.Rejected -> categories
    else -> FinancialResult.Success(
        DashboardCatalogResponse(
            (accounts as FinancialResult.Success).value.map(AccountResponse::from),
            (categories as FinancialResult.Success).value.map(CategoryResponse::from),
        ),
    )
}

private fun <T> List<T>.paged(call: ApplicationCall): PageResponse<T> {
    val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
    val offset = (page - 1) * pageSize
    return PageResponse(drop(offset).take(pageSize), page, pageSize, size)
}

private fun <T, R> PageResponse<T>.map(transform: (T) -> R): PageResponse<R> =
    PageResponse(items.map(transform), page, pageSize, total)

private suspend fun ApplicationCall.requirePathUuid(name: String): UUID? {
    val value = parameters[name]?.toUuidOrNull()
    if (value == null) respond(HttpStatusCode.BadRequest, DashboardErrorResponse("INVALID_ID"))
    return value
}
private fun String.toUuidOrNull(): UUID? = runCatching(UUID::fromString).getOrNull()
private fun String.toLocalDateOrNull(): LocalDate? = runCatching(LocalDate::parse).getOrNull()
private fun String.toEntryTypeOrNull(): FinancialEntryType? = runCatching {
    FinancialEntryType.valueOf(uppercase())
}.getOrNull()
private fun BigDecimal.moneyString(): String = setScale(2).toPlainString()

@Serializable data class DashboardLoginRequest(val name: String, val password: String)
@Serializable data class DashboardSessionResponse(val name: String)
@Serializable data class DashboardErrorResponse(val error: String)
@Serializable data class PageResponse<T>(val items: List<T>, val page: Int, val pageSize: Int, val total: Int)
@Serializable data class AccountMutationRequest(val name: String, val type: String)
@Serializable data class CategoryMutationRequest(val name: String, val type: String, val expenseNature: String? = null)

@Serializable
data class TransactionMutationRequest(
    val accountId: String,
    val type: String,
    val amount: String,
    val description: String,
    val categoryId: String,
    val occurredOn: String,
) {
    fun toCreateCommand() = CreateTransactionCommand(
        UUID.fromString(accountId), FinancialEntryType.valueOf(type.uppercase()), Money.of(BigDecimal(amount)),
        description = FinancialDescription.of(description), categoryId = UUID.fromString(categoryId),
        occurredOn = LocalDate.parse(occurredOn), source = FinancialSource.DASHBOARD,
    )
    fun toUpdateCommand() = UpdateTransactionCommand(
        UUID.fromString(accountId), FinancialEntryType.valueOf(type.uppercase()), Money.of(BigDecimal(amount)),
        description = FinancialDescription.of(description), categoryId = UUID.fromString(categoryId),
        occurredOn = LocalDate.parse(occurredOn),
    )
}

@Serializable
data class CashFlowMutationRequest(
    val type: String,
    val amount: String,
    val description: String,
    val accountId: String? = null,
    val categoryId: String? = null,
    val recurrence: String,
    val startDate: String,
    val endDate: String? = null,
    val dayOfMonth: Int? = null,
    val active: Boolean = true,
) {
    fun toCreateCommand() = CreateCashFlowEntryCommand(
        FinancialEntryType.valueOf(type.uppercase()), Money.of(BigDecimal(amount)),
        description = FinancialDescription.of(description), accountId = accountId?.let(UUID::fromString),
        categoryId = categoryId?.let(UUID::fromString), recurrence = CashFlowRecurrence.valueOf(recurrence.uppercase()),
        startDate = LocalDate.parse(startDate), endDate = endDate?.let(LocalDate::parse), dayOfMonth = dayOfMonth,
        source = FinancialSource.DASHBOARD,
    )
    fun toUpdateCommand() = UpdateCashFlowEntryCommand(
        FinancialEntryType.valueOf(type.uppercase()), Money.of(BigDecimal(amount)),
        description = FinancialDescription.of(description), accountId = accountId?.let(UUID::fromString),
        categoryId = categoryId?.let(UUID::fromString), recurrence = CashFlowRecurrence.valueOf(recurrence.uppercase()),
        startDate = LocalDate.parse(startDate), endDate = endDate?.let(LocalDate::parse), dayOfMonth = dayOfMonth,
        active = active,
    )
}

@Serializable data class TransactionResponse(
    val id: String, val accountId: String, val categoryId: String, val type: String, val amount: String,
    val description: String, val occurredOn: String, val source: String,
) { companion object { fun from(value: Transaction) = TransactionResponse(value.id.toString(), value.accountId.toString(), value.categoryId.toString(), value.type.name, value.money.amount.moneyString(), value.description.value, value.occurredOn.toString(), value.source.name) } }

@Serializable data class CashFlowEntryResponse(
    val id: String, val type: String, val amount: String, val description: String, val accountId: String?,
    val categoryId: String?, val recurrence: String, val startDate: String, val endDate: String?,
    val dayOfMonth: Int?, val active: Boolean,
) { companion object { fun from(value: CashFlowEntry) = CashFlowEntryResponse(value.id.toString(), value.type.name, value.money.amount.moneyString(), value.description.value, value.accountId?.toString(), value.categoryId?.toString(), value.recurrence.name, value.startDate.toString(), value.endDate?.toString(), value.dayOfMonth, value.active) } }

@Serializable data class AccountResponse(val id: String, val name: String, val type: String, val active: Boolean) { companion object { fun from(value: Account) = AccountResponse(value.id.toString(), value.name.value, value.type.name, value.active) } }
@Serializable data class CategoryResponse(val id: String, val name: String, val type: String, val expenseNature: String) { companion object { fun from(value: Category) = CategoryResponse(value.id.toString(), value.name.value, value.type.name, value.expenseNature.name) } }
@Serializable data class DashboardCatalogResponse(val accounts: List<AccountResponse>, val categories: List<CategoryResponse>)

@Serializable data class DashboardOverviewResponse(
    val from: String, val to: String, val currentBalance: String, val projectedBalance: String,
    val realizedIncome: String, val realizedExpense: String, val realizedResult: String,
    val plannedIncome: String, val plannedExpense: String,
    val pendingIncome: String, val pendingExpense: String, val freeMoney: String, val investedMoney: String,
    val debt: String, val netWorth: String, val investedPercentage: String, val accounts: List<AccountBalanceResponse>,
    val categories: List<CategorySummaryResponse>, val upcoming: List<CashFlowOccurrenceResponse>,
) { companion object { fun from(value: com.sova.dashboard.application.DashboardOverview) = DashboardOverviewResponse(value.period.from.toString(), value.period.to.toString(), value.currentBalance.moneyString(), value.projectedBalance.moneyString(), value.realizedIncome.moneyString(), value.realizedExpense.moneyString(), value.realizedResult.moneyString(), value.plannedIncome.moneyString(), value.plannedExpense.moneyString(), value.pendingIncome.moneyString(), value.pendingExpense.moneyString(), value.freeMoney.moneyString(), value.investedMoney.moneyString(), value.debt.moneyString(), value.netWorth.moneyString(), value.investedPercentage.moneyString(), value.accounts.map { AccountBalanceResponse(AccountResponse.from(it.account), it.balance.moneyString()) }, value.categories.map { CategorySummaryResponse(CategoryResponse.from(it.category), it.realized.moneyString(), it.percentageOfExpenses.moneyString()) }, value.upcoming.map(CashFlowOccurrenceResponse::from)) } }
@Serializable data class AccountBalanceResponse(val account: AccountResponse, val balance: String)
@Serializable data class CategorySummaryResponse(val category: CategoryResponse, val realized: String, val percentageOfExpenses: String)
@Serializable data class CashFlowOccurrenceResponse(val entryId: String, val description: String, val type: String, val plannedAmount: String, val scheduledOn: String, val status: String, val settlementId: String?) { companion object { fun from(value: com.sova.finance.application.CashFlowOccurrenceDetails) = CashFlowOccurrenceResponse(value.entry.id.toString(), value.entry.description.value, value.entry.type.name, value.entry.money.amount.moneyString(), value.scheduledOn.toString(), value.status.name, value.receipt?.settlement?.id?.toString()) } }
@Serializable data class SettlementResponse(val id: String, val cashFlowEntryId: String, val description: String, val plannedAmount: String, val settledAmount: String, val variance: String, val scheduledOn: String, val paidOn: String, val settledAt: String)
@Serializable data class SettlementReceiptResponse(val id: String, val caption: String?, val contentType: String, val sizeBytes: Long, val linkedAt: String, val accessUrl: String)

@Serializable
data class TimelineItemResponse(
    val entryId: String,
    val occurrenceId: String,
    val settlementId: String?,
    val type: String,
    val description: String,
    val categoryId: String?,
    val category: String?,
    val plannedAmount: String,
    val settledAmount: String?,
    val dueDate: String,
    val settledAt: String?,
    val status: String,
    val source: String,
    val recurring: Boolean,
) {
    companion object {
        fun from(value: DashboardTimelineItem): TimelineItemResponse = TimelineItemResponse(
            entryId = value.occurrence.entry.id.toString(),
            occurrenceId = "${value.occurrence.entry.id}:${value.occurrence.scheduledOn}",
            settlementId = value.occurrence.receipt?.settlement?.id?.toString(),
            type = value.occurrence.entry.type.name,
            description = value.occurrence.entry.description.value,
            categoryId = value.category?.id?.toString(),
            category = value.category?.name?.value,
            plannedAmount = value.occurrence.entry.money.amount.moneyString(),
            settledAmount = value.settledAmount?.moneyString(),
            dueDate = value.occurrence.scheduledOn.toString(),
            settledAt = value.settledAt?.toString(),
            status = value.status.name,
            source = value.occurrence.entry.source.name,
            recurring = value.occurrence.entry.recurrence != CashFlowRecurrence.ONCE,
        )
    }
}

private const val DASHBOARD_COOKIE = "sova_dashboard_session"
