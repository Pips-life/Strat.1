            StrategyId.STRATEGY_002 -> execute002(state.account, state.saved, state.symbol, price, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_003 -> execute003(state.account, state.saved, state.symbol, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_004 -> execute004(state.account, state.saved, state.symbol, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_005 -> execute005(state.account, state.saved, state.symbol, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
            StrategyId.STRATEGY_006 -> execute006(state.account, state.saved, state.symbol, tick, snapshot.positions.filter { it.symbol.equals(state.symbol, true) }, snapshot, state.onStatus)
        }
    }


    private suspend fun execute006(account: MetaAccount, saved: SavedConnection, symbol: String, tick: TickPrice, positions: List<MetaPosition>, snapshot: MetaSnapshot, onStatus: (String) -> Unit) {
        val map = strategy006.currentMap()
        if (map == null || !map.valid) { onStatus("S006 | WAIT | load both options files at London open"); return }
        val price = (tick.bid + tick.ask) / 2.0

        if (positions.isNotEmpty()) {
            val side = positionSide(positions.first()) ?: return
            val currentTarget = positions.mapNotNull { it.takeProfit.takeIf { v -> v.isFinite() && v > 0.0 } }.firstOrNull()
                ?: strategy006.oppositeTarget(side, positions.first().openPrice)?.zone
            if (currentTarget != null) {
                val management = strategy006.manageOpenPosition(side, currentTarget, tick.time)
                when (management.action) {
                    Strategy006Engine.PositionAction.CLOSE_REVERSE -> {
                        var allClosed = true
                        for (p in positions) {
                            meta.closePosition(saved.metaApiToken, account, p.id)
                                .onSuccess { recordClosedTrade(p.profit, snapshot.equity); onStatus("S006 | TARGET REJECTION | CLOSED | ${p.id}") }
                                .onFailure { allClosed = false; onStatus("S006 | EXIT FAILED | ${it.message ?: "unknown"}") }
                        }
                        if (allClosed) onStatus("S006 | TARGET REJECTION CONFIRMED | positions closed | next tick will evaluate opposite trade")
                    }
                    Strategy006Engine.PositionAction.RETARGET -> {
                        val next = management.nextTarget ?: return
                        for (p in positions) {
                            meta.modifyPosition(saved.metaApiToken, account, p.id, takeProfit = null)
                                .onSuccess { onStatus("S006 | BREAKOUT HOLD | ${p.id} | new soft target=${fmt(next.zone)}") }
                                .onFailure { onStatus("S006 | RETARGET FAILED | ${p.id} | ${it.message ?: "unknown"}") }
                        }
                    }
                    Strategy006Engine.PositionAction.HOLD -> onStatus("S006 | HOLD | ${management.reason}")
                }
            } else onStatus("S006 | HOLD | active position has no mapped target yet")
            return
        }

        if (riskPolicy.maxPositions > 0 && positions.size >= riskPolicy.maxPositions) return
        if (!safety.canEnter()) { onStatus("S006 | ENTRY BLOCKED | KILL SWITCH | ${safety.reason()}"); return }
        val plan = strategy006.plan(price, snapshot.balance, tick.bid, tick.ask, tickTime = tick.time)
        val side = plan.side ?: return
        val stop = plan.stop ?: return
        val takeProfit = plan.target ?: return
        if (plan.rewardRisk < riskPolicy.minRewardRisk) { onStatus("S006 | ENTRY BLOCKED | RR ${fmt(plan.rewardRisk)} < ${fmt(riskPolicy.minRewardRisk)}"); return }
        if (dailyLossFraction >= riskPolicy.maxDailyLoss || tradesToday >= riskPolicy.maxTradesPerDay || consecutiveLosses >= riskPolicy.maxConsecutiveLosses) { onStatus("S006 | ENTRY BLOCKED | GLOBAL DAILY RISK LIMIT"); return }
        val spec = snapshot.specifications[symbol] ?: return
        val entry = plan.entry ?: price
        val minBrokerVolume = spec.minVolume
        val brokerMarginAtMin = if (minBrokerVolume.isFinite() && minBrokerVolume > 0.0) meta.calculateMargin(saved.metaApiToken, account, side, symbol, minBrokerVolume, entry).getOrNull() else null
        val marginPerVolume = brokerMarginAtMin?.takeIf { it.isFinite() && it > 0.0 }?.let { it / minBrokerVolume }