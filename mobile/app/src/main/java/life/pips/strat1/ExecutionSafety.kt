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
                p.getDouble("risk_per_trade"), p.getDouble("risk_budget_utilization"), p.getInt("max_positions"), p.getInt("entries_per_signal").coerceAtLeast(1),
                p.getDouble("max_daily_loss"), p.getInt("max_trades_per_day"), p.getInt("max_consecutive_losses"),
                p.getDouble("min_reward_risk"), p.getDouble("max_position_notional_pct"), p.getDouble("min_confidence"),
                p.getInt("flatten_minutes_before_close"), p.getString("session_start"), p.getString("session_end"),
                p.getBoolean("allow_overnight"), p.getDouble("quantity_step"), p.getDouble("min_quantity"),
                p.getLong("max_tick_age_ms"), p.getLong("max_flashalpha_age_ms"), p.getInt("order_verify_attempts"), p.getLong("order_verify_delay_ms")
            )
        }
    }
}

data class CanonicalRiskDecision(val approved: Boolean, val quantity: Double = 0.0, val rewardRisk: Double = 0.0, val reason: String = "")

class CanonicalRiskEngine(private val policy: CanonicalRiskPolicy = CanonicalRiskPolicy.load()) {
    fun decide(side: TradeSide, entry: Double, stop: Double, target: Double, equity: Double, positions: Int, confidence: Double, dailyLossFraction: Double, tradesToday: Int, consecutiveLosses: Int, tickValue: Double, tickSize: Double): CanonicalRiskDecision {
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() || equity <= 0.0) return CanonicalRiskDecision(false, reason = "invalid risk inputs")
        if (confidence < policy.minConfidence) return CanonicalRiskDecision(false, reason = "confidence below risk threshold")
        if (positions >= policy.maxPositions) return CanonicalRiskDecision(false, reason = "maximum simultaneous positions reached")
        if (abs(dailyLossFraction) >= policy.maxDailyLoss) return CanonicalRiskDecision(false, reason = "maximum daily loss reached")
        if (tradesToday >= policy.maxTradesPerDay) return CanonicalRiskDecision(false, reason = "maximum daily trades reached")
        if (consecutiveLosses >= policy.maxConsecutiveLosses) return CanonicalRiskDecision(false, reason = "consecutive-loss limit reached")
        val validGeometry = if (side == TradeSide.BUY) stop < entry && entry < target else target < entry && entry < stop
        if (!validGeometry) return CanonicalRiskDecision(false, reason = "invalid stop/target geometry")
        val risk = abs(entry - stop)
        val reward = if (side == TradeSide.BUY) target - entry else entry - target
        val rr = if (risk > 0.0) reward / risk else 0.0
        if (!rr.isFinite() || rr < policy.minRewardRisk) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "reward/risk below minimum")
        if (!tickValue.isFinite() || tickValue <= 0.0 || !tickSize.isFinite() || tickSize <= 0.0) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "broker tick value unavailable")
        val riskCash = equity * policy.riskPerTrade * policy.riskBudgetUtilization
        val ticksToStop = risk / tickSize
        val riskPerLot = ticksToStop * tickValue
        if (!riskPerLot.isFinite() || riskPerLot <= 0.0) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "invalid broker risk geometry")
        val raw = riskCash / riskPerLot
        val qty = floor(raw / policy.quantityStep + 1e-9) * policy.quantityStep
        if (!qty.isFinite() || qty < policy.minQuantity) return CanonicalRiskDecision(false, rewardRisk = rr, reason = "minimum executable quantity exceeds risk budget")
        return CanonicalRiskDecision(true, qty, rr, "approved")
    }
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
