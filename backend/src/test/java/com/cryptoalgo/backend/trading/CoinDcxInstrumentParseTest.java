package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures CoinDCX payloads with null max_leverage_* still size via dynamic tiers.
 */
class CoinDcxInstrumentParseTest {

    @Test
    void derivesMaxLeverageFromDynamicTiers() throws Exception {
        String json = """
                {
                  "instrument": {
                    "pair": "B-TAG_USDT",
                    "min_quantity": 1.0,
                    "min_notional": 6.0,
                    "max_leverage_long": null,
                    "max_leverage_short": null,
                    "quantity_increment": 1.0,
                    "max_notional": 0.0,
                    "dynamic_position_leverage_details": {
                      "2": 292500,
                      "3": 130000,
                      "30": 32500
                    }
                  }
                }
                """;
        var mapper = new ObjectMapper();
        var node = mapper.readTree(json).path("instrument");
        BigDecimal maxLev = null;
        var dyn = node.path("dynamic_position_leverage_details");
        var names = dyn.fieldNames();
        while (names.hasNext()) {
            BigDecimal lev = new BigDecimal(names.next());
            if (maxLev == null || lev.compareTo(maxLev) > 0) maxLev = lev;
        }
        assertEquals(0, new BigDecimal("30").compareTo(maxLev));
        var inst = new CoinDcxFuturesClient.Instrument(
                "B-TAG_USDT",
                new BigDecimal("1.0"),
                new BigDecimal("6.0"),
                maxLev,
                new BigDecimal("0.0"),
                new BigDecimal("1.0"),
                null,
                null);
        assertTrue(inst.hasRequiredSizingFields());
    }

    @Test
    void compactClientOrderIdFitsCoinDcxMax36() {
        var signal = java.util.UUID.fromString("469f4cbb-eb54-4a0e-b90f-74144c8ed331");
        var bot = java.util.UUID.fromString("4c72c784-4ac4-43a4-9e8d-da2a064dcf52");
        String id = ExecutionService.compactClientOrderId(signal, bot);
        assertTrue(id.length() <= 36, id + " len=" + id.length());
        assertEquals(34, id.length());
        assertFalse(id.contains("-fail-"));
    }

    @Test
    void extractExchangeOrderIdFromFuturesArrayResponse() throws Exception {
        // CoinDCX futures create returns a bare array (docs Create Order response).
        String json = """
                [{
                  "id":"c87ca633-6218-44ea-900b-e86981358cbd",
                  "pair":"B-TAG_USDT",
                  "side":"buy",
                  "status":"initial",
                  "order_type":"market_order",
                  "total_quantity":100.0
                }]
                """;
        var node = new ObjectMapper().readTree(json);
        assertEquals("c87ca633-6218-44ea-900b-e86981358cbd",
                ExecutionService.extractExchangeOrderId(node));
    }
}
