package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.config.AppProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Fresh-state LIVE futures sizing from live CoinDCX instrument metadata.
 * Never invents min notional/qty/step; never raises leverage to clear mins.
 * May bump margin above preferred stake up to max usable wallet room.
 */
@Component
public class LiveFuturesSizingService {

    public record SizeRequest(
            BigDecimal markPriceUsdt,
            BigDecimal usdtInr,
            int leverage,
            /** Preferred margin (usually bot stake). */
            BigDecimal preferredMarginInr,
            /** Hard ceiling from wallet / risk caps — bump may use up to this. */
            BigDecimal maxUsableMarginInr,
            BigDecimal stoplossFraction,
            CoinDcxFuturesClient.Instrument instrument
    ) {
        /** Tests / callers where preferred == max. */
        public SizeRequest(BigDecimal markPriceUsdt, BigDecimal usdtInr, int leverage,
                           BigDecimal usableMarginInr, BigDecimal stoplossFraction,
                           CoinDcxFuturesClient.Instrument instrument) {
            this(markPriceUsdt, usdtInr, leverage, usableMarginInr, usableMarginInr,
                    stoplossFraction, instrument);
        }
    }

    public record SizeResult(
            boolean ok,
            String failReason,
            BigDecimal qty,
            BigDecimal notionalUsdt,
            BigDecimal marginInr,
            boolean bumped,
            String liquidationCheck
    ) {
        static SizeResult fail(String reason) {
            return new SizeResult(false, reason, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, false, null);
        }
    }

    private final AppProperties props;

    public LiveFuturesSizingService(AppProperties props) {
        this.props = props;
    }

    public SizeResult size(SizeRequest req) {
        if (req.markPriceUsdt() == null || req.markPriceUsdt().signum() <= 0) {
            return SizeResult.fail("mark price unavailable");
        }
        if (req.usdtInr() == null || req.usdtInr().signum() <= 0) {
            return SizeResult.fail("USDTINR rate unavailable");
        }
        if (req.instrument() == null || !req.instrument().hasRequiredSizingFields()) {
            return SizeResult.fail("live instrument missing required sizing fields (min_quantity/min_notional/max_leverage)");
        }
        if (req.maxUsableMarginInr() == null || req.maxUsableMarginInr().signum() <= 0) {
            return SizeResult.fail("no usable INR margin under risk caps");
        }
        if (req.leverage() <= 0) {
            return SizeResult.fail("invalid leverage");
        }

        int lev = req.leverage();
        BigDecimal maxLev = req.instrument().maxLeverage();
        if (maxLev != null && BigDecimal.valueOf(lev).compareTo(maxLev) > 0) {
            // Cap only — never raise leverage.
            lev = maxLev.intValue();
            if (lev <= 0) return SizeResult.fail("instrument max_leverage invalid");
        }

        SizeResult liq = liquidationSafety(req.stoplossFraction(), lev, req.instrument());
        if (!liq.ok()) return liq;

        BigDecimal maxUsable = req.maxUsableMarginInr();
        BigDecimal preferred = req.preferredMarginInr() == null || req.preferredMarginInr().signum() <= 0
                ? maxUsable
                : req.preferredMarginInr().min(maxUsable);

        BigDecimal price = req.markPriceUsdt();
        BigDecimal usdtInr = req.usdtInr();
        BigDecimal denom = price.multiply(usdtInr);
        BigDecimal qty = preferred
                .multiply(BigDecimal.valueOf(lev))
                .divide(denom, MathContext.DECIMAL64);
        qty = roundQty(qty, req.instrument(), RoundingMode.DOWN);
        boolean bumped = false;

        BigDecimal notional = qty.multiply(price);
        BigDecimal minQty = req.instrument().minQuantity();
        BigDecimal minNotional = req.instrument().minNotional();

        if (qty.signum() <= 0 || qty.compareTo(minQty) < 0 || notional.compareTo(minNotional) < 0) {
            BigDecimal minQtyFromNotional = minNotional.divide(price, MathContext.DECIMAL64);
            BigDecimal needQty = minQty.max(minQtyFromNotional);
            needQty = roundQty(needQty, req.instrument(), RoundingMode.UP);
            BigDecimal needNotional = needQty.multiply(price);
            BigDecimal needMargin = needNotional.multiply(usdtInr)
                    .divide(BigDecimal.valueOf(lev), MathContext.DECIMAL64);
            if (needMargin.compareTo(maxUsable) > 0) {
                return SizeResult.fail("need ₹" + needMargin.stripTrailingZeros().toPlainString()
                        + " margin for exchange min notional "
                        + minNotional.toPlainString() + " USDT (usable ₹"
                        + maxUsable.stripTrailingZeros().toPlainString()
                        + "; preferred stake ₹"
                        + preferred.stripTrailingZeros().toPlainString() + ")");
            }
            if (req.instrument().maxNotional() != null
                    && req.instrument().maxNotional().signum() > 0
                    && needNotional.compareTo(req.instrument().maxNotional()) > 0) {
                return SizeResult.fail("min viable notional exceeds exchange max_notional");
            }
            qty = needQty;
            notional = needNotional;
            bumped = true;
        }

        if (req.instrument().maxNotional() != null
                && req.instrument().maxNotional().signum() > 0
                && notional.compareTo(req.instrument().maxNotional()) > 0) {
            return SizeResult.fail("sized notional exceeds exchange max_notional");
        }

        BigDecimal marginInr = notional.multiply(usdtInr)
                .divide(BigDecimal.valueOf(lev), MathContext.DECIMAL64);
        if (marginInr.compareTo(maxUsable) > 0) {
            return SizeResult.fail("required margin exceeds usable INR after caps");
        }
        if (qty.compareTo(minQty) < 0 || notional.compareTo(minNotional) < 0) {
            return SizeResult.fail("qty/notional still below live exchange minimums after sizing");
        }

        return new SizeResult(true, null, qty, notional, marginInr, bumped, liq.liquidationCheck());
    }

    /**
     * Prefer maintenance_margin_rate from CoinDCX when present.
     * Otherwise CONSERVATIVE_APPROX using 1/leverage — never represented as exchange liq price.
     */
    SizeResult liquidationSafety(BigDecimal stoplossFraction, int leverage,
                                 CoinDcxFuturesClient.Instrument instrument) {
        if (stoplossFraction == null || stoplossFraction.signum() == 0) {
            return SizeResult.fail("stoploss missing — cannot establish liquidation safety");
        }
        BigDecimal slDist = stoplossFraction.abs();
        BigDecimal buffer = BigDecimal.valueOf(props.pipeline().liquidationSafetyBuffer());

        if (instrument.maintenanceMarginRate() != null
                && instrument.maintenanceMarginRate().signum() > 0) {
            // Real exchange maintenance margin: SL must leave room above maint margin.
            BigDecimal safe = BigDecimal.ONE
                    .subtract(instrument.maintenanceMarginRate())
                    .subtract(buffer);
            if (safe.signum() <= 0 || slDist.compareTo(safe) >= 0) {
                return SizeResult.fail("SL distance unsafe vs CoinDCX maintenance_margin_rate");
            }
            return new SizeResult(true, null, null, null, null, false,
                    "EXCHANGE_MAINTENANCE_MARGIN");
        }

        // CONSERVATIVE_APPROX only — not an exchange-derived liquidation price.
        BigDecimal approxLiqDistance = BigDecimal.ONE.divide(
                BigDecimal.valueOf(leverage), MathContext.DECIMAL64);
        if (slDist.add(buffer).compareTo(approxLiqDistance) >= 0) {
            return SizeResult.fail("SL distance unsafe under CONSERVATIVE_APPROX (1/leverage); "
                    + "exchange liquidation metadata unavailable");
        }
        return new SizeResult(true, null, null, null, null, false, "CONSERVATIVE_APPROX");
    }

    static BigDecimal roundQty(BigDecimal qty, CoinDcxFuturesClient.Instrument inst, RoundingMode mode) {
        if (qty == null) return BigDecimal.ZERO;
        BigDecimal step = inst.quantityStep();
        if (step != null && step.signum() > 0) {
            BigDecimal steps = qty.divide(step, 0, mode);
            return steps.multiply(step);
        }
        // No step in live meta: only whole units if min_quantity itself is whole; else fail upstream.
        if (inst.minQuantity() != null && inst.minQuantity().stripTrailingZeros().scale() <= 0) {
            return qty.setScale(0, mode);
        }
        return qty;
    }
}
