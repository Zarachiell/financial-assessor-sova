package com.sova.ai.application

import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.insights.application.ComparePeriodsQuery
import com.sova.finance.insights.application.FinancialInsightsApplicationService
import com.sova.finance.insights.application.GetBudgetDeviationsQuery
import com.sova.finance.insights.application.GetExpenseTrendQuery
import com.sova.finance.insights.application.GetFinancialHealthSnapshotQuery
import com.sova.finance.insights.application.GetFinancialSnapshotQuery
import com.sova.finance.insights.application.GetLargestExpensesQuery
import com.sova.finance.insights.application.GetRecurringExpensesQuery
import com.sova.finance.insights.domain.BudgetDeviation
import com.sova.finance.insights.domain.CategoryMetric
import com.sova.finance.insights.domain.InsightPeriod
import com.sova.finance.insights.domain.LargestExpenseMetric
import com.sova.finance.insights.domain.MetricChange
import com.sova.finance.insights.domain.SavingsPlan
import com.sova.finance.insights.domain.TrendGrouping
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

fun financialInsightTools(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
): List<LocalTool> = listOf(
    GetFinancialSnapshotTool(financialService, insightsService),
    ComparePeriodsTool(financialService, insightsService),
    GetExpenseTrendTool(financialService, insightsService),
    GetLargestExpensesTool(financialService, insightsService),
    GetRecurringExpensesTool(financialService, insightsService),
    GetBudgetDeviationsTool(financialService, insightsService),
    GetFinancialHealthSnapshotTool(financialService, insightsService),
)

class GetFinancialSnapshotTool(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
) : FinancialInsightLocalTool(financialService, insightsService) {
    override val definition = insightPeriodDefinition(
        name = "get_financial_snapshot",
        description = "Resume receitas, despesas, fluxo líquido, categorias e maiores despesas com cálculos locais.",
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val snapshot = insightsService.getFinancialSnapshot(
            context.financialContext,
            GetFinancialSnapshotQuery(arguments.insightPeriod()),
        ).requiredInsightValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            putPeriod(snapshot.period)
            put("currency", JsonPrimitive("BRL"))
            put("totalIncome", insightMoney(snapshot.totals.totalIncome))
            put("totalExpenses", insightMoney(snapshot.totals.totalExpenses))
            put("netCashFlow", insightMoney(snapshot.totals.netCashFlow))
            put("transactionCount", JsonPrimitive(snapshot.totals.transactionCount))
            put("averageDailyExpense", insightMoney(snapshot.averageDailyExpense))
            put("topExpenseCategories", snapshot.topExpenseCategories.toCategoryJson())
            put("topIncomeCategories", snapshot.topIncomeCategories.toCategoryJson())
            put("largestExpenses", snapshot.largestExpenses.toLargestExpenseJson())
        }
    }
}

class ComparePeriodsTool(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
) : FinancialInsightLocalTool(financialService, insightsService) {
    override val definition = AiToolDefinition(
        name = "compare_periods",
        description = "Compara localmente receitas, despesas e categorias entre dois períodos inclusivos.",
        parameters = AiToolParameters(
            properties = mapOf(
                "currentFrom" to insightString("Início do período atual YYYY-MM-DD."),
                "currentTo" to insightString("Fim do período atual YYYY-MM-DD."),
                "comparisonFrom" to insightString("Início do período comparativo YYYY-MM-DD."),
                "comparisonTo" to insightString("Fim do período comparativo YYYY-MM-DD."),
            ),
            required = setOf("currentFrom", "currentTo", "comparisonFrom", "comparisonTo"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val comparison = insightsService.comparePeriods(
            context.financialContext,
            ComparePeriodsQuery(
                currentPeriod = arguments.insightPeriod("currentFrom", "currentTo"),
                comparisonPeriod = arguments.insightPeriod("comparisonFrom", "comparisonTo"),
            ),
        ).requiredInsightValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("currency", JsonPrimitive("BRL"))
            put("currentPeriod", comparison.currentPeriod.toJson())
            put("comparisonPeriod", comparison.comparisonPeriod.toJson())
            putMetricChange("income", comparison.income)
            putMetricChange("expenses", comparison.expenses)
            put(
                "categoryChanges",
                buildJsonArray {
                    comparison.categoryChanges.forEach { change ->
                        add(
                            buildJsonObject {
                                put("category", JsonPrimitive(change.category))
                                put("nature", JsonPrimitive(change.nature.name))
                                put("currentTotal", insightMoney(change.currentTotal))
                                put("comparisonTotal", insightMoney(change.comparisonTotal))
                                put("absoluteChange", insightMoney(change.absoluteChange))
                                putNullableDecimal("percentageChange", change.percentageChange)
                            },
                        )
                    }
                },
            )
        }
    }
}

class GetExpenseTrendTool(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
) : FinancialInsightLocalTool(financialService, insightsService) {
    override val definition = AiToolDefinition(
        name = "get_expense_trend",
        description = "Calcula tendência de despesas por mês ou semana, opcionalmente por categoria.",
        parameters = AiToolParameters(
            properties = mapOf(
                "from" to insightString("Data inicial YYYY-MM-DD."),
                "to" to insightString("Data final YYYY-MM-DD."),
                "grouping" to insightString("Agrupamento temporal.", "MONTH", "WEEK"),
                "category" to insightString("Categoria de despesa opcional."),
            ),
            required = setOf("from", "to", "grouping"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val category = arguments.insightOptionalString("category")?.let { name ->
            resolveCategory(context, name, FinancialEntryType.EXPENSE)
        }
        val trend = insightsService.getExpenseTrend(
            context.financialContext,
            GetExpenseTrendQuery(
                period = arguments.insightPeriod(),
                grouping = TrendGrouping.valueOf(arguments.insightRequiredString("grouping")),
                categoryId = category?.id,
                categoryName = category?.name?.value,
            ),
        ).requiredInsightValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            putPeriod(trend.period)
            put("currency", JsonPrimitive("BRL"))
            put("grouping", JsonPrimitive(trend.grouping.name))
            trend.category?.let { put("category", JsonPrimitive(it)) }
            put(
                "periods",
                buildJsonArray {
                    trend.points.forEach { point ->
                        add(
                            buildJsonObject {
                                put("period", JsonPrimitive(point.periodStart.toString()))
                                put("total", insightMoney(point.total))
                            },
                        )
                    }
                },
            )
            putNullableDecimal("absoluteChange", trend.absoluteChange, money = true)
            putNullableDecimal("percentageChange", trend.percentageChange)
            trend.direction?.let { put("direction", JsonPrimitive(it.name)) }
            put("stableThresholdPct", JsonPrimitive(BigDecimal("5.00")))
        }
    }
}

class GetLargestExpensesTool(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
) : FinancialInsightLocalTool(financialService, insightsService) {
    override val definition = AiToolDefinition(
        name = "get_largest_expenses",
        description = "Lista as maiores despesas locais com limite obrigatório entre 1 e 20.",
        parameters = AiToolParameters(
            properties = mapOf(
                "from" to insightString("Data inicial YYYY-MM-DD."),
                "to" to insightString("Data final YYYY-MM-DD."),
                "limit" to insightInteger("Quantidade de despesas, entre 1 e 20."),
                "category" to insightString("Categoria de despesa opcional."),
            ),
            required = setOf("from", "to", "limit"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val period = arguments.insightPeriod()
        val category = arguments.insightOptionalString("category")?.let { name ->
            resolveCategory(context, name, FinancialEntryType.EXPENSE)
        }
        val limit = arguments.getValue("limit").jsonPrimitive.int
        val expenses = insightsService.getLargestExpenses(
            context.financialContext,
            GetLargestExpensesQuery(period, limit, category?.id),
        ).requiredInsightValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            putPeriod(period)
            put("currency", JsonPrimitive("BRL"))
            put("limit", JsonPrimitive(limit))
            put("count", JsonPrimitive(expenses.size))
            category?.let { put("category", JsonPrimitive(it.name.value)) }
            put("expenses", expenses.toLargestExpenseJson())
        }
    }
}

class GetRecurringExpensesTool(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
) : FinancialInsightLocalTool(financialService, insightsService) {
    override val definition = insightPeriodDefinition(
        name = "get_recurring_expenses",
        description = "Detecta gastos possivelmente mensais por heurística local e informa confiança.",
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val period = arguments.insightPeriod()
        val recurring = insightsService.getRecurringExpenses(
            context.financialContext,
            GetRecurringExpensesQuery(period),
        ).requiredInsightValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            putPeriod(period)
            put("currency", JsonPrimitive("BRL"))
            put("heuristic", JsonPrimitive("NORMALIZED_DESCRIPTION_CATEGORY_MONTHLY_INTERVAL_AMOUNT_VARIATION"))
            put("count", JsonPrimitive(recurring.size))
            put(
                "recurringExpenses",
                buildJsonArray {
                    recurring.forEach { expense ->
                        add(
                            buildJsonObject {
                                put("description", JsonPrimitive(expense.description))
                                put("category", JsonPrimitive(expense.category))
                                put("averageAmount", insightMoney(expense.averageAmount))
                                put("occurrenceCount", JsonPrimitive(expense.occurrenceCount))
                                put("estimatedFrequency", JsonPrimitive(expense.estimatedFrequency))
                                put("lastOccurrence", JsonPrimitive(expense.lastOccurrence.toString()))
                                put("confidence", JsonPrimitive(expense.confidence.name))
                            },
                        )
                    }
                },
            )
        }
    }
}

class GetBudgetDeviationsTool(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
) : FinancialInsightLocalTool(financialService, insightsService) {
    override val definition = AiToolDefinition(
        name = "get_budget_deviations",
        description = "Compara gastos atuais com a média dos 3 meses completos anteriores e pode calcular cortes seguros.",
        parameters = AiToolParameters(
            properties = mapOf(
                "from" to insightString("Início do período atual YYYY-MM-DD."),
                "to" to insightString("Fim do período atual YYYY-MM-DD."),
                "savingsTarget" to insightNumber("Meta positiva opcional de economia em BRL."),
            ),
            required = setOf("from", "to"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val snapshot = insightsService.getBudgetDeviations(
            context.financialContext,
            GetBudgetDeviationsQuery(
                currentPeriod = arguments.insightPeriod(),
                savingsTarget = arguments.insightOptionalDecimal("savingsTarget"),
            ),
        ).requiredInsightValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("currency", JsonPrimitive("BRL"))
            put("currentPeriod", snapshot.currentPeriod.toJson())
            put("baselineMethod", JsonPrimitive("AVERAGE_OF_3_PREVIOUS_COMPLETE_MONTHS"))
            put("baselinePeriods", JsonArray(snapshot.baselinePeriods.map(InsightPeriod::toJson)))
            put("deviations", JsonArray(snapshot.deviations.map(BudgetDeviation::toJson)))
            snapshot.savingsPlan?.let { put("savingsPlan", it.toJson()) }
        }
    }
}

class GetFinancialHealthSnapshotTool(
    financialService: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService,
) : FinancialInsightLocalTool(financialService, insightsService) {
    override val definition = insightPeriodDefinition(
        name = "get_financial_health_snapshot",
        description = "Retorna fatos locais de saúde financeira sem gerar score arbitrário.",
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val health = insightsService.getFinancialHealthSnapshot(
            context.financialContext,
            GetFinancialHealthSnapshotQuery(arguments.insightPeriod()),
        ).requiredInsightValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            putPeriod(health.period)
            put("currency", JsonPrimitive("BRL"))
            put("averageMonthlyIncome", insightMoney(health.averageMonthlyIncome))
            put("averageMonthlyExpenses", insightMoney(health.averageMonthlyExpenses))
            put("averageMonthlyNetCashFlow", insightMoney(health.averageMonthlyNetCashFlow))
            putNullableDecimal("savingsRate", health.savingsRate)
            putNullablePercentage("savingsRatePct", health.savingsRate)
            putNullableDecimal("fixedExpenseRatio", health.fixedExpenseRatio)
            putNullablePercentage("fixedExpenseRatioPct", health.fixedExpenseRatio)
            putNullableDecimal("discretionaryExpenseRatio", health.discretionaryExpenseRatio)
            putNullablePercentage("discretionaryExpenseRatioPct", health.discretionaryExpenseRatio)
            health.expenseTrend?.let { put("expenseTrend", JsonPrimitive(it.name)) }
            putNullableDecimal("cashReserveMonths", health.cashReserveMonths)
            put("currentAvailableBalance", insightMoney(health.currentAvailableBalance))
            put(
                "monthEndProjection",
                buildJsonObject {
                    put("asOf", JsonPrimitive(health.monthEndProjection.asOf.toString()))
                    put("actualExpensesSoFar", insightMoney(health.monthEndProjection.actualExpensesSoFar))
                    putNullableDecimal("projectedExpenses", health.monthEndProjection.projectedExpenses, money = true)
                    putNullableDecimal(
                        "projectedEndBalance",
                        health.monthEndProjection.projectedEndBalance,
                        money = true,
                    )
                    put("projectionMethod", JsonPrimitive(health.monthEndProjection.projectionMethod))
                    put("daysObserved", JsonPrimitive(health.monthEndProjection.daysObserved))
                    put("daysInPeriod", JsonPrimitive(health.monthEndProjection.daysInPeriod))
                    put("minimumObservedDays", JsonPrimitive(health.monthEndProjection.minimumObservedDays))
                },
            )
            put("score", JsonNull)
        }
    }
}

abstract class FinancialInsightLocalTool(
    financialService: FinancialApplicationService,
    protected val insightsService: FinancialInsightsApplicationService,
) : FinancialLocalTool(financialService)

private fun insightPeriodDefinition(name: String, description: String): AiToolDefinition = AiToolDefinition(
    name = name,
    description = description,
    parameters = AiToolParameters(
        properties = mapOf(
            "from" to insightString("Data inicial inclusiva YYYY-MM-DD."),
            "to" to insightString("Data final inclusiva YYYY-MM-DD."),
        ),
        required = setOf("from", "to"),
    ),
)

private fun insightString(description: String, vararg enumValues: String) = AiToolParameter(
    type = AiJsonType.STRING,
    description = description,
    enumValues = enumValues.toList(),
)

private fun insightNumber(description: String) = AiToolParameter(AiJsonType.NUMBER, description)

private fun insightInteger(description: String) = AiToolParameter(AiJsonType.INTEGER, description)

private fun JsonObject.insightPeriod(
    fromName: String = "from",
    toName: String = "to",
): InsightPeriod = InsightPeriod(
    LocalDate.parse(insightRequiredString(fromName)),
    LocalDate.parse(insightRequiredString(toName)),
)

private fun JsonObject.insightRequiredString(name: String): String =
    requireNotNull(insightOptionalString(name)) { "Missing argument: $name" }

private fun JsonObject.insightOptionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun JsonObject.insightOptionalDecimal(name: String): BigDecimal? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()

private fun InsightPeriod.toJson(): JsonObject = buildJsonObject {
    put("from", JsonPrimitive(from.toString()))
    put("to", JsonPrimitive(to.toString()))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putPeriod(period: InsightPeriod) {
    put("from", JsonPrimitive(period.from.toString()))
    put("to", JsonPrimitive(period.to.toString()))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putMetricChange(
    metricName: String,
    change: MetricChange,
) {
    val suffix = metricName.replaceFirstChar(Char::uppercase)
    val changeName = metricName.removeSuffix("s")
    put("current$suffix", insightMoney(change.current))
    put("comparison$suffix", insightMoney(change.comparison))
    put("${changeName}AbsoluteChange", insightMoney(change.absoluteChange))
    putNullableDecimal("${changeName}PercentageChange", change.percentageChange)
}

private fun List<CategoryMetric>.toCategoryJson(): JsonArray = buildJsonArray {
    this@toCategoryJson.forEach { metric ->
        add(
            buildJsonObject {
                put("category", JsonPrimitive(metric.category))
                put("nature", JsonPrimitive(metric.nature.name))
                put("total", insightMoney(metric.total))
                put("transactionCount", JsonPrimitive(metric.transactionCount))
            },
        )
    }
}

private fun List<LargestExpenseMetric>.toLargestExpenseJson(): JsonArray = buildJsonArray {
    this@toLargestExpenseJson.forEach { expense ->
        add(
            buildJsonObject {
                put("transactionId", JsonPrimitive(expense.transactionId.toString()))
                put("occurredOn", JsonPrimitive(expense.occurredOn.toString()))
                put("description", JsonPrimitive(expense.description))
                put("category", JsonPrimitive(expense.category))
                put("amount", insightMoney(expense.amount))
                put("currency", JsonPrimitive(expense.currency.name))
            },
        )
    }
}

private fun BudgetDeviation.toJson(): JsonObject = buildJsonObject {
    put("category", JsonPrimitive(category))
    put("nature", JsonPrimitive(nature.name))
    put("currentAmount", insightMoney(currentAmount))
    put("baselineAverage", insightMoney(baselineAverage))
    put("absoluteDeviation", insightMoney(absoluteDeviation))
    putNullableDecimal("percentageDeviation", percentageDeviation)
    put("severity", JsonPrimitive(severity.name))
}

private fun SavingsPlan.toJson(): JsonObject = buildJsonObject {
    put("targetAmount", insightMoney(targetAmount))
    put("plannedAmount", insightMoney(plannedAmount))
    put("remainingAmount", insightMoney(remainingAmount))
    put(
        "suggestions",
        buildJsonArray {
            suggestions.forEach { suggestion ->
                add(
                    buildJsonObject {
                        put("category", JsonPrimitive(suggestion.category))
                        put("availableExpense", insightMoney(suggestion.availableExpense))
                        put("suggestedCut", insightMoney(suggestion.suggestedCut))
                        put("maximumCutRatio", JsonPrimitive(suggestion.maximumCutRatio))
                        put("maximumCutPct", JsonPrimitive(suggestion.maximumCutRatio.movePointRight(2)))
                    },
                )
            }
        },
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableDecimal(
    name: String,
    value: BigDecimal?,
    money: Boolean = false,
) {
    put(name, value?.let { if (money) insightMoney(it) else JsonPrimitive(it) } ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullablePercentage(name: String, ratio: BigDecimal?) {
    put(name, ratio?.let { JsonPrimitive(it.movePointRight(2).setScale(2, RoundingMode.HALF_UP)) } ?: JsonNull)
}

private fun insightMoney(value: BigDecimal): JsonPrimitive =
    JsonPrimitive(value.setScale(2, RoundingMode.HALF_UP))

private fun <T> FinancialResult<T>.requiredInsightValue(): T = when (this) {
    is FinancialResult.Success -> value
    is FinancialResult.Rejected -> throw ToolRejectedException(
        "financial_rejection",
        buildJsonObject { put("reason", JsonPrimitive(reason.name.lowercase())) },
    )
}
