package life.pips.strat1

import android.util.Log
import life.pips.strat1.data.TradeSide
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

data class CanonicalRiskPolicy(
    val riskPerTrade: Double,
    val riskBudgetUtilization: Double,
    val maxPositions: Int,
    val entriesPerSignal: Int,
    val maxDailyLoss: Double,
    val maxTradesPerDay: Int,
    val maxConsecutiveLosses: Int,
    val minRewardRisk: Double,
    val maxPositionNotionalPct: Double,
    val minConfidence: Double,
    val flattenMinutesBeforeClose: Int,
    val sessionStart: String,
    val sessionEnd: String,
    val allowOvernight: Boolean,
    val quantityStep: Double,
    val minQuantity: Double,
    val maxTickAgeMs: Long,
    val maxFlashAlphaAgeMs: Long,
    val orderVerifyAttempts: Int,
    val orderVerifyDelayMs: Long
) {
    companion object {
        fun load(): CanonicalRiskPolicy {
            val p = JSONObject(BuildConfig.RISK_POLICY_JSON)
            return CanonicalRiskPolicy(
                p.getDouble("risk_per_trade"), p.getDouble("risk_budget_utilization"), p.getInt("max_positions").coerceAtLeast(0), p.getInt("entries_per_signal").coerceAtLeast(0),
                p.getDouble("max_daily_loss"), p.getInt("max_trades_per_day"), p.getInt("max_consecutive_losses"),
                p.getDouble("min_reward_risk"), p.getDouble("max_position_notional_pct"), p.getDouble("min_confidence"),
                p.getInt("flatten_minutes_before_close"), p.getString("session_start"), p.getString("session_end"),
                p.getBoolean("allow_overnight"), p.getDouble("quantity_step"), p.getDouble("min_quantity"),
                p.getLong("max_tick_age_ms"), p.getLong("max_flashalpha_age_ms"), p.getInt("order_verify_attempts"), p.getLong("order_verify_delay_ms")
            )
        }
    }
}

data class CanonicalRiskDecision(
    val approved: Boolean, val quantity: Double = 0.0, val rewardRisk: Double = 0.0,
    val reason: String = "", val riskAmount: Double = 0.0, val marginRequired: Double = 0.0,
    val riskPercent: Double = 0.0
)

class CanonicalRiskEngine(private val policy: CanonicalRiskPolicy = CanonicalRiskPolicy.load()) {
    fun decide(
        side: TradeSide, entry: Double, stop: Double, target: Double, equity: Double, positions: Int,
        confidence: Double, dailyLossFraction: Double, tradesToday: Int, consecutiveLosses: Int,
        tickValue: Double, tickSize: Double, accountBalance: Double = equity,
        riskFractionOverride: Double? = null,
        freeMargin: Double = Double.POSITIVE_INFINITY, leverage: Double = Double.NaN,
        contractSize: Double = Double.NaN, brokerMinVolume: Double = policy.minQuantity,
        brokerMaxVolume: Double = Double.POSITIVE_INFINITY, brokerVolumeStep: Double = policy.quantityStep,
        marginPerVolume: Double = Double.NaN
    ): CanonicalRiskDecision {
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() || equity <= 0.0 || accountBalance <= 0.0) return CanonicalRiskDecision(false, reason = "invalid account/risk inputs")
        if (confidence < policy.minConfidence) return CanonicalRiskDecision(false, reason = "confidence below risk threshold")
        if (policy.maxPositions > 0 && positions >= policy.maxPositions) return CanonicalRiskDecision(false, reason = "maximum simultaneous positions reached")
        if (abs(dailyLossFraction) >= policy.maxDailyLoss) return CanonicalRiskDecision(false, reason = "maximum daily loss reached")
        if (tradesToday >= policy.maxTradesPerDay) return CanonicalRiskDecision(false, reason = "maximum daily trades reached")
        if (consecutiveLosses >= policy.maxConsecutiveLosses) return CanonicalRiskDecision(false, reason = "consecutive-loss limit reached")
        val validGeometry = if (side == TradeSide.BUY) stop < entry && entry < target else target < entry && entry < stop
        if (!validGeometry) return CanonicalRiskDecision(false, reason = "invalid stop/target geometry")
        val risk = abs(entry - stop); val reward = if (side == TradeSide.BUY) target - entry else entry - target
        val rr = if (risk > 0.0) reward / risk else 0.0
        if (!rr.isFinite() || rr < policy.minRewardRisk) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "reward/risk below minimum")
        if (!tickValue.isFinite() || tickValue <= 0.0 || !tickSize.isFinite() || tickSize <= 0.0) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "broker tick size/value unavailable")
        val riskCapital = minOf(accountBalance, equity); val effectiveRiskFraction = riskFractionOverride?.coerceAtLeast(0.0) ?: policy.riskPerTrade; val riskCash = riskCapital * effectiveRiskFraction * policy.riskBudgetUtilization
        val ticksToStop = risk / tickSize; val riskPerVolume = ticksToStop * tickValue
        if (!riskPerVolume.isFinite() || riskPerVolume <= 0.0) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "invalid broker risk geometry")
        val rawRiskQty = riskCash / riskPerVolume
        val step = if (brokerVolumeStep.isFinite() && brokerVolumeStep > 0.0) brokerVolumeStep else policy.quantityStep
        val minVolume = if (brokerMinVolume.isFinite() && brokerMinVolume > 0.0) brokerMinVolume else policy.minQuantity
        val maxVolume = if (brokerMaxVolume.isFinite() && brokerMaxVolume > 0.0) brokerMaxVolume else Double.POSITIVE_INFINITY
        var qty = floor(rawRiskQty / step + 1e-9) * step
        if (!qty.isFinite() || qty < minVolume) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "broker minimum volume exceeds risk budget")
        if (qty > maxVolume) qty = floor(maxVolume / step + 1e-9) * step
        if (qty < minVolume) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "broker volume limits prevent executable size")
        val marginUnit = when {
            marginPerVolume.isFinite() && marginPerVolume > 0.0 -> marginPerVolume
            leverage.isFinite() && leverage > 0.0 && contractSize.isFinite() && contractSize > 0.0 -> entry * contractSize / leverage
            else -> Double.NaN
        }
        if (!marginUnit.isFinite() || marginUnit <= 0.0) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "broker margin/leverage unavailable")
        if (!freeMargin.isFinite() || freeMargin <= 0.0) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "free margin unavailable")
        val marginCapQty = floor((freeMargin / marginUnit) / step + 1e-9) * step
        if (!marginCapQty.isFinite() || marginCapQty < minVolume) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "broker free margin cannot support minimum volume")
        qty = minOf(qty, marginCapQty, maxVolume); qty = floor(qty / step + 1e-9) * step
        if (qty < minVolume) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "margin constraint leaves no executable volume")
        val actualRisk = riskPerVolume * qty; val actualMargin = marginUnit * qty; val actualRiskPct = actualRisk / riskCapital * 100.0
        return CanonicalRiskDecision(true, qty, rr, "approved | balance=" + fmtRisk(accountBalance) + " | equity=" + fmtRisk(equity) + " | freeMargin=" + fmtRisk(freeMargin) + " | leverage=" + fmtRisk(leverage) + " | tick=" + fmtRisk(tickSize) + " | risk=" + fmtRisk(actualRisk) + " (" + fmtRisk(actualRiskPct) + "%) | margin=" + fmtRisk(actualMargin), actualRisk, actualMargin, actualRiskPct)
    }
    private fun fmtRisk(v: Double): String = if (v.isFinite()) "%.4f".format(v) else "—"
}

enum class RecoveryStage { SUBMIT, ACK, VERIFY, PROTECTED, ACTIVE, FAILED }

class ExecutionRecoveryStateMachine {
    private var stage: RecoveryStage = RecoveryStage.SUBMIT
    fun transition(next: RecoveryStage, detail: String = "") {
        stage = next
        Log.i("PipsLife.Execution", "RECOVERY ${next.name}${if (detail.isBlank()) "" else " | $detail"}")
    }
    fun current(): RecoveryStage = stage
}

class TradingSafetyGate {
    @Volatile private var halted = false
    @Volatile private var reason = ""
    fun halt(reason: String) { halted = true; this.reason = reason; Log.e("PipsLife.Safety", "KILL SWITCH ON | $reason") }
    fun clear() { if (halted) Log.i("PipsLife.Safety", "KILL SWITCH CLEARED"); halted = false; reason = "" }
    fun canEnter(): Boolean = !halted
    fun reason(): String = reason
}

fun logTradingDecision(message: String) { Log.i("PipsLife.Decision", message) }
