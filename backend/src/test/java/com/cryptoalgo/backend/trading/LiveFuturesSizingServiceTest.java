package com.cryptoalgo.backend.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class LiveFuturesSizingServiceTest {

    private final LiveFuturesSizingService sizing = new LiveFuturesSizingService(
            new com.cryptoalgo.backend.config.AppProperties(
                    null, null, null, null, null, null, null,
                    new com.cryptoalgo.backend.config.AppProperties.Pipeline(
                            30, 0.6, 60, 30, BigDecimal.valueOf(1000), 3,
                            -0.02, 0.015, 3, 30, 10, 50, 0.5, 0, 0.95,
                            10, 0.5, 0.35, 0.25, 0.01, 120000, 3, 24
                    )
            )
    );

    @Test
    void failsClosedWhenInstrumentMissingMins() {
        var inst = new CoinDcxFuturesClient.Instrument("B-X_USDT", null, null,
                BigDecimal.valueOf(20), null, null, null, null);
        var r = sizing.size(new LiveFuturesSizingService.SizeRequest(
                BigDecimal.ONE, BigDecimal.valueOf(90), 3, BigDecimal.valueOf(500),
                BigDecimal.valueOf(-0.02), inst));
        assertFalse(r.ok());
        assertTrue(r.failReason().contains("missing required"));
    }

    @Test
    void sizesAtLeastExchangeMinimumsWhenFundsAllow() {
        var inst = new CoinDcxFuturesClient.Instrument(
                "B-X_USDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(6),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(100000),
                BigDecimal.ONE,
                null,
                null);
        var r = sizing.size(new LiveFuturesSizingService.SizeRequest(
                BigDecimal.ONE, BigDecimal.valueOf(90), 3, BigDecimal.valueOf(500),
                BigDecimal.valueOf(-0.02), inst));
        assertTrue(r.ok(), () -> String.valueOf(r.failReason()));
        assertTrue(r.qty().compareTo(BigDecimal.ONE) >= 0);
        assertTrue(r.notionalUsdt().compareTo(BigDecimal.valueOf(6)) >= 0);
    }

    @Test
    void neverRaisesLeverageAndSkipsWhenMarginInsufficient() {
        var inst = new CoinDcxFuturesClient.Instrument(
                "B-X_USDT", BigDecimal.ONE, BigDecimal.valueOf(6),
                BigDecimal.valueOf(20), BigDecimal.valueOf(100000), BigDecimal.ONE, null, null);
        var r = sizing.size(new LiveFuturesSizingService.SizeRequest(
                BigDecimal.valueOf(0.15), BigDecimal.valueOf(90), 3, BigDecimal.valueOf(10),
                BigDecimal.valueOf(-0.02), inst));
        assertFalse(r.ok());
        assertTrue(r.failReason().contains("need ₹"));
    }

    @Test
    void bumpsAbovePreferredStakeWithinWalletRoom() {
        var inst = new CoinDcxFuturesClient.Instrument(
                "B-X_USDT", BigDecimal.ONE, BigDecimal.valueOf(6),
                BigDecimal.valueOf(20), BigDecimal.valueOf(100000), BigDecimal.ONE, null, null);
        // Preferred ₹50 cannot clear min notional 6 USDT at 3x / ₹90 USDTINR
        // (needs ~₹180), but wallet room ₹500 can.
        var r = sizing.size(new LiveFuturesSizingService.SizeRequest(
                BigDecimal.ONE, BigDecimal.valueOf(90), 3,
                BigDecimal.valueOf(50), BigDecimal.valueOf(500),
                BigDecimal.valueOf(-0.02), inst));
        assertTrue(r.ok(), () -> String.valueOf(r.failReason()));
        assertTrue(r.bumped());
        assertTrue(r.notionalUsdt().compareTo(BigDecimal.valueOf(6)) >= 0);
        assertTrue(r.marginInr().compareTo(BigDecimal.valueOf(50)) > 0);
    }

    @Test
    void conservativeApproxRejectsUnsafeSl() {
        var inst = new CoinDcxFuturesClient.Instrument(
                "B-X_USDT", BigDecimal.ONE, BigDecimal.valueOf(6),
                BigDecimal.valueOf(20), BigDecimal.valueOf(100000), BigDecimal.ONE, null, null);
        // lev 3 => approx liq distance 0.333; SL 0.4 + buffer unsafe
        var r = sizing.liquidationSafety(BigDecimal.valueOf(-0.40), 3, inst);
        assertFalse(r.ok());
        assertTrue(r.failReason().contains("CONSERVATIVE_APPROX"));
    }
}
