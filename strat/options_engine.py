"""Provider-independent Gold options positioning engine."""

from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any, Optional
import math

import numpy as np
import pandas as pd


REQUIRED = {
    "strike", "expiry", "option_type", "bid", "ask", "last",
    "volume", "open_interest", "delta", "gamma", "vega", "iv"
}


@dataclass(frozen=True)
class EngineConfig:
    default_multiplier: float = 100.0
    expiry_tau_days: float = 7.0
    active_atr_distance: float = 1.5
    max_atr_distance: float = 3.5
    stabilization_min_atr: float = 1.5
    stabilization_max_atr: float = 3.0
    sweep_min_atr: float = 2.0
    sweep_max_atr: float = 3.5
    pivot_max_atr: float = 0.75
    min_options: int = 10
    epsilon: float = 1e-12
    # Master strike score weights.
    gex_weight: float = 0.30
    gamma_flow_weight: float = 0.20
    oi_weight: float = 0.15
    flow_weight: float = 0.15
    vega_weight: float = 0.10
    volume_weight: float = 0.10


@dataclass
class Level:
    name: str
    price: Optional[float]
    confidence: float = 0.0
    score: float = 0.0
    distance_atr: Optional[float] = None
    reason: str = ""


@dataclass
class GoldOptionsMap:
    timestamp: str
    futures_price: float
    atr: float
    gamma_regime: str
    total_dealer_gex: float
    zero_gamma: Optional[float]
    levels: dict[str, Level]
    strike_map: pd.DataFrame

    def as_dict(self) -> dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "futures_price": self.futures_price,
            "atr": self.atr,
            "gamma_regime": self.gamma_regime,
            "total_dealer_gex": self.total_dealer_gex,
            "zero_gamma": self.zero_gamma,
            "levels": {k: asdict(v) for k, v in self.levels.items()},
            "strike_map": self.strike_map.to_dict(orient="records"),
        }


def robust_z(s: pd.Series, eps: float = 1e-12) -> pd.Series:
    s = pd.to_numeric(s, errors="coerce").fillna(0.0)
    med = float(s.median())
    mad = float(np.median(np.abs(s - med)))
    if mad < eps:
        std = float(s.std())
        if std < eps or not np.isfinite(std):
            return pd.Series(0.0, index=s.index)
        return (s - med) / (std + eps)
    return 0.6745 * (s - med) / (mad + eps)


def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-float(np.clip(x, -30, 30))))


def black76_d1(F: float, K: float, sigma: float, T: float) -> float:
    if F <= 0 or K <= 0 or sigma <= 0 or T <= 0:
        return float("nan")
    return (math.log(F / K) + 0.5 * sigma * sigma * T) / (sigma * math.sqrt(T))


def black76_gamma(F: float, K: float, sigma: float, T: float) -> float:
    """Gamma of a Black-76 option on futures, per unit of futures price."""
    if sigma <= 0 or T <= 0 or F <= 0 or K <= 0:
        return 0.0
    d1 = black76_d1(F, K, sigma, T)
    phi = math.exp(-0.5 * d1 * d1) / math.sqrt(2.0 * math.pi)
    return phi / (F * sigma * math.sqrt(T))


def _normalize_option_type(s: pd.Series) -> pd.Series:
    return (s.astype(str).str.upper().str.strip().replace({
        "CALL": "C", "PUT": "P"
    }))


class GoldOptionsEngine:
    """Calculate a six-level Gold options positioning map.

    The engine is deliberately API-independent. A provider adapter should
    normalize its response into the dataframe schema documented in README.md.
    """

    def __init__(self, config: EngineConfig | None = None):
        self.config = config or EngineConfig()

    def _prepare(self, chain: pd.DataFrame, futures_price: float) -> pd.DataFrame:
        missing = sorted(REQUIRED - set(chain.columns))
        if missing:
            raise ValueError(f"Missing required columns: {missing}")
        if futures_price <= 0:
            raise ValueError("futures_price must be positive")

        x = chain.copy()
        x["option_type"] = _normalize_option_type(x["option_type"])
        x = x[x["option_type"].isin(["C", "P"])].copy()
        x["expiry"] = pd.to_datetime(x["expiry"], utc=True, errors="coerce")
        now = pd.Timestamp.now(tz="UTC")
        x["dte"] = ((x["expiry"] - now).dt.total_seconds() / 86400.0).clip(lower=0)

        numeric = ["strike", "bid", "ask", "last", "volume", "open_interest",
                   "delta", "gamma", "vega", "iv"]
        for c in numeric:
            x[c] = pd.to_numeric(x[c], errors="coerce")
        x["volume"] = x["volume"].fillna(0)
        x["open_interest"] = x["open_interest"].fillna(0)
        x["multiplier"] = pd.to_numeric(
            x["multiplier"], errors="coerce"
        ).fillna(self.config.default_multiplier) if "multiplier" in x else self.config.default_multiplier

        valid = (x["strike"] > 0) & x["expiry"].notna()
        x = x[valid].copy()
        if len(x) < self.config.min_options:
            raise ValueError(f"Only {len(x)} valid options; need at least {self.config.min_options}")

        valid_ba = x["bid"].notna() & x["ask"].notna() & (x["ask"] >= x["bid"])
        x["mid"] = np.where(valid_ba, (x["bid"] + x["ask"]) / 2.0, x["last"])
        x["mid"] = pd.to_numeric(x["mid"], errors="coerce").fillna(0)
        return x

    def _flow(self, x: pd.DataFrame) -> pd.DataFrame:
        x = x.copy()
        x["trade_price"] = pd.to_numeric(
            x["trade_price"], errors="coerce"
        ) if "trade_price" in x else x["last"]
        x["trade_size"] = pd.to_numeric(
            x["trade_size"], errors="coerce"
        ).fillna(x["volume"]) if "trade_size" in x else x["volume"]

        spread = (x["ask"] - x["bid"]).replace(0, np.nan)
        pressure = ((x["trade_price"] - x["mid"]) / spread).replace(
            [np.inf, -np.inf], np.nan
        ).fillna(0).clip(-1, 1)

        if "aggressor" in x:
            a = x["aggressor"].astype(str).str.lower()
            pressure = pressure.copy()
            pressure[a.isin(["buy", "buyer", "b", "ask"])] = 1.0
            pressure[a.isin(["sell", "seller", "s", "bid"])] = -1.0

        x["flow_sign"] = pressure
        x["signed_volume"] = x["trade_size"] * pressure
        return x

    def _exposure(self, x: pd.DataFrame, F: float) -> pd.DataFrame:
        x = x.copy()
        T = np.maximum(x["dte"] / 365.0, 0.0)
        # Use provider gamma where present; Black-76 provides a consistent fallback.
        fallback_gamma = []
        for K, sig, t in zip(x["strike"], x["iv"], T):
            sigma = float(sig) if np.isfinite(sig) else 0.0
            fallback_gamma.append(black76_gamma(F, float(K), sigma, float(t)))
        fallback_gamma = pd.Series(fallback_gamma, index=x.index)
        x["model_gamma"] = np.where(
            x["gamma"].notna() & (x["gamma"] > 0), x["gamma"], fallback_gamma
        )

        # Structural baseline: customer-held OI is treated as dealer-short.
        # This is an assumption, not direct observation of dealer books.
        x["dealer_gex"] = -(
            x["open_interest"] * x["model_gamma"] * x["multiplier"] * F * F * 0.01
        )
        x["dealer_gamma_flow"] = -(
            x["signed_volume"] * x["model_gamma"] * x["multiplier"] * F * F * 0.01
        )
        x["delta_flow"] = x["signed_volume"] * x["delta"].fillna(0) * x["multiplier"]
        x["vega_oi"] = x["vega"].abs().fillna(0) * x["open_interest"] * x["multiplier"]
        x["expiry_weight"] = np.exp(-x["dte"] / self.config.expiry_tau_days)
        return x

    def _aggregate(self, x: pd.DataFrame) -> pd.DataFrame:
        g = x.groupby("strike", as_index=False).agg({
            "dealer_gex": "sum", "dealer_gamma_flow": "sum", "delta_flow": "sum",
            "vega_oi": "sum", "open_interest": "sum", "volume": "sum",
            "expiry_weight": "mean"
        })
        g["weighted_gex"] = g["dealer_gex"] * g["expiry_weight"]
        g["weighted_gamma_flow"] = g["dealer_gamma_flow"] * g["expiry_weight"]
        g["weighted_oi"] = g["open_interest"] * g["expiry_weight"]
        g["weighted_volume"] = g["volume"] * g["expiry_weight"]
        g["gex_z"] = robust_z(g["weighted_gex"])
        g["gamma_flow_z"] = robust_z(g["weighted_gamma_flow"])
        g["oi_z"] = robust_z(g["weighted_oi"])
        g["flow_z"] = robust_z(g["delta_flow"])
        g["vega_z"] = robust_z(g["vega_oi"])
        g["volume_z"] = robust_z(g["weighted_volume"])
        c = self.config
        g["level_score"] = (
            c.gex_weight * g["gex_z"]
            + c.gamma_flow_weight * g["gamma_flow_z"]
            + c.oi_weight * g["oi_z"]
            + c.flow_weight * g["flow_z"]
            + c.vega_weight * g["vega_z"]
            + c.volume_weight * g["volume_z"]
        )
        g = g.sort_values("strike").reset_index(drop=True)
        prev_ = g["level_score"].shift(1)
        next_ = g["level_score"].shift(-1)
        g["local_max"] = (g["level_score"] >= prev_) & (g["level_score"] >= next_)
        return g

    @staticmethod
    def _zero_gamma(g: pd.DataFrame) -> Optional[float]:
        y = g.sort_values("strike")[["strike", "weighted_gex"]].dropna()
        for i in range(len(y) - 1):
            k1, z1 = float(y.iloc[i, 0]), float(y.iloc[i, 1])
            k2, z2 = float(y.iloc[i + 1, 0]), float(y.iloc[i + 1, 1])
            if z1 == 0:
                return k1
            if z1 * z2 < 0:
                return k1 + (-z1) * (k2 - k1) / (z2 - z1)
        return None

    @staticmethod
    def _confidence(row: pd.Series) -> float:
        raw = (
            0.35 * abs(float(row.get("gex_z", 0)))
            + 0.25 * abs(float(row.get("flow_z", 0)))
            + 0.15 * abs(float(row.get("oi_z", 0)))
            + 0.15 * abs(float(row.get("gamma_flow_z", 0)))
            + 0.10 * abs(float(row.get("vega_z", 0)))
        )
        return round(100 * sigmoid(raw), 1)

    def _level(self, name: str, row: Optional[pd.Series], F: float, atr: float, reason: str) -> Level:
        if row is None:
            return Level(name=name, price=None, reason="No qualifying strike")
        d = abs(float(row["strike"]) - F) / atr
        return Level(
            name=name,
            price=round(float(row["strike"]), 2),
            confidence=self._confidence(row),
            score=round(float(row.get("level_score", 0)), 4),
            distance_atr=round(d, 3),
            reason=reason,
        )

    @staticmethod
    def _best(x: pd.DataFrame, score: str = "selection_score") -> Optional[pd.Series]:
        if x.empty:
            return None
        return x.sort_values(score, ascending=False).iloc[0]

    def calculate(self, chain: pd.DataFrame, futures_price: float, atr: float) -> GoldOptionsMap:
        if atr <= 0:
            raise ValueError("atr must be positive")
        x = self._prepare(chain, futures_price)
        x = self._flow(x)
        x = self._exposure(x, futures_price)
        g = self._aggregate(x)
        g["distance_atr"] = (g["strike"] - futures_price) / atr
        g["abs_distance_atr"] = g["distance_atr"].abs()

        # Active resistance/support: nearby local concentration.
        r = g[(g["strike"] > futures_price) & (g["distance_atr"] <= self.config.active_atr_distance) & g["local_max"]].copy()
        r["selection_score"] = 0.40*r.gex_z.abs() + 0.25*r.gamma_flow_z.abs() + 0.20*r.flow_z.abs() + 0.15*r.oi_z.abs()
        s = g[(g["strike"] < futures_price) & (g["abs_distance_atr"] <= self.config.active_atr_distance) & g["local_max"]].copy()
        s["selection_score"] = 0.40*s.gex_z.abs() + 0.25*s.gamma_flow_z.abs() + 0.20*s.flow_z.abs() + 0.15*s.oi_z.abs()

        # Ceiling: strongest concentration above spot, with proximity preference.
        c = g[(g["strike"] > futures_price) & (g["abs_distance_atr"] <= self.config.max_atr_distance) & g["local_max"]].copy()
        if not c.empty:
            dw = (1 - c["abs_distance_atr"] / self.config.max_atr_distance).clip(0, 1)
            c["selection_score"] = c["level_score"].abs() * (0.5 + 0.5*dw)

        # Stabilization: second support band.
        st = g[(g["strike"] < futures_price) & (g["abs_distance_atr"] > self.config.stabilization_min_atr) & (g["abs_distance_atr"] <= self.config.stabilization_max_atr) & g["local_max"]].copy()
        if not st.empty:
            st["selection_score"] = 0.45*st.gex_z.abs() + 0.25*st.vega_z.abs() + 0.20*st.oi_z.abs() + 0.10*st.gamma_flow_z.abs()

        # Sweep zone: farther downside concentration, weighted by flow/OI/GEX.
        sw = g[(g["strike"] < futures_price) & (g["abs_distance_atr"] >= self.config.sweep_min_atr) & (g["abs_distance_atr"] <= self.config.sweep_max_atr) & g["local_max"]].copy()
        if not sw.empty:
            dist = (sw["abs_distance_atr"] / self.config.sweep_max_atr).clip(0, 1)
            sw["selection_score"] = 0.30*sw.oi_z.abs() + 0.25*sw.gex_z.abs() + 0.20*sw.gamma_flow_z.abs() + 0.15*dist + 0.10*sw.vega_z.abs()

        zg = self._zero_gamma(g)
        p = pd.DataFrame()
        if zg is not None:
            p = g[(g["strike"] - zg).abs() / atr <= self.config.pivot_max_atr].copy()
            if not p.empty:
                p["selection_score"] = (
                    0.50 / (1 + ((p["strike"] - zg).abs() / atr))
                    + 0.30*p.flow_z.abs() + 0.20*p.gamma_flow_z.abs()
                )
        if p.empty:
            p = g[g["abs_distance_atr"] <= self.config.pivot_max_atr].copy()
            if not p.empty:
                p["selection_score"] = p.level_score.abs()

        total_gex = float(g["weighted_gex"].sum())
        scale = float(np.median(np.abs(g["weighted_gex"])))
        if scale < self.config.epsilon:
            regime = "NEUTRAL"
        elif total_gex > scale:
            regime = "POSITIVE_GAMMA"
        elif total_gex < -scale:
            regime = "NEGATIVE_GAMMA"
        else:
            regime = "NEUTRAL"

        levels = {
            "dealer_ceiling": self._level("Dealer Ceiling", self._best(c), futures_price, atr, "Highest nearby upside dealer-risk concentration."),
            "main_reclaim_pivot": self._level("Main Reclaim Pivot", self._best(p), futures_price, atr, "Nearest strong strike to the estimated zero-gamma transition."),
            "active_dealer_resistance": self._level("Active Dealer Resistance", self._best(r), futures_price, atr, "Nearby upside local maximum in dealer GEX/flow concentration."),
            "active_dealer_support": self._level("Active Dealer Support", self._best(s), futures_price, atr, "Nearby downside local maximum in dealer GEX/flow concentration."),
            "stabilization_support": self._level("Stabilization Support", self._best(st), futures_price, atr, "Secondary downside gamma/vega/OI concentration."),
            "sweep_trap_zone": self._level("Sweep-Trap Zone", self._best(sw), futures_price, atr, "Farther downside concentration with elevated positioning/flow; potential sweep zone, not a guaranteed reversal."),
        }

        return GoldOptionsMap(
            timestamp=pd.Timestamp.now(tz="UTC").isoformat(),
            futures_price=float(futures_price),
            atr=float(atr),
            gamma_regime=regime,
            total_dealer_gex=total_gex,
            zero_gamma=zg,
            levels=levels,
            strike_map=g,
        )
