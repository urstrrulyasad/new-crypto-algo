package com.cryptoalgo.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches recent crypto headlines (Google News RSS) and derives a light
 * sentiment hint for strategy generation prompts. No API key required.
 */
@Service
public class MarketNewsService {

    private static final Logger log = LoggerFactory.getLogger(MarketNewsService.class);
    private static final Pattern ITEM = Pattern.compile(
            "<item>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<title><!\\[CDATA\\[(.*?)]]></title>|<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Map<String, String> SEARCH_ALIAS = Map.ofEntries(
            Map.entry("BTC", "Bitcoin OR BTC"),
            Map.entry("ETH", "Ethereum OR ETH"),
            Map.entry("SOL", "Solana OR SOL"),
            Map.entry("XRP", "XRP OR Ripple"),
            Map.entry("DOGE", "Dogecoin OR DOGE"),
            Map.entry("BNB", "BNB OR Binance Coin"),
            Map.entry("ADA", "Cardano OR ADA"),
            Map.entry("AVAX", "Avalanche OR AVAX"),
            Map.entry("LINK", "Chainlink OR LINK"),
            Map.entry("DOT", "Polkadot OR DOT"),
            Map.entry("MATIC", "Polygon OR MATIC"),
            Map.entry("SUI", "Sui crypto OR SUI"),
            Map.entry("NEAR", "NEAR Protocol"),
            Map.entry("APT", "Aptos OR APT"),
            Map.entry("OP", "Optimism crypto OR OP token")
    );

    private final WebClient http;

    public MarketNewsService(WebClient.Builder builder) {
        this.http = builder.clone().build();
    }

    public record NewsBrief(String sentiment, List<String> headlines, String promptBlock) {}

    /** CoinDCX pair like B-BTC_USDT → BTC. */
    public static String baseSymbol(String instrument) {
        if (instrument == null || instrument.isBlank()) return "";
        String p = instrument.toUpperCase(Locale.ROOT);
        if (p.startsWith("B-")) p = p.substring(2);
        int usdt = p.indexOf("_USDT");
        if (usdt > 0) return p.substring(0, usdt);
        int dash = p.indexOf('-');
        if (dash > 0) return p.substring(0, dash);
        return p.replace("_USDT", "").replace("USDT", "");
    }

    public Mono<NewsBrief> briefForInstrument(String instrument) {
        String symbol = baseSymbol(instrument);
        String q = SEARCH_ALIAS.getOrDefault(symbol, symbol.isBlank() ? "cryptocurrency" : symbol + " crypto");
        URI uri = UriComponentsBuilder
                .fromUriString("https://news.google.com/rss/search")
                .queryParam("q", q)
                .queryParam("hl", "en-US")
                .queryParam("gl", "US")
                .queryParam("ceid", "US:en")
                .build()
                .encode()
                .toUri();
        return http.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(12))
                .map(xml -> summarize(symbol, xml))
                .onErrorResume(e -> {
                    log.warn("News fetch failed for {}: {}", instrument, e.getMessage());
                    return Mono.just(new NewsBrief("unknown", List.of(),
                            "News/sentiment unavailable (API error); rely on price action only."));
                });
    }

    private NewsBrief summarize(String symbol, String xml) {
        List<String> headlines = new ArrayList<>();
        int bull = 0, bear = 0;
        if (xml != null && !xml.isBlank()) {
            Matcher items = ITEM.matcher(xml);
            while (items.find() && headlines.size() < 8) {
                String item = items.group(1);
                Matcher tm = TITLE.matcher(item);
                if (!tm.find()) continue;
                String title = tm.group(1) != null ? tm.group(1) : tm.group(2);
                if (title == null) continue;
                title = title.replace("&amp;", "&").replace("&#39;", "'").trim();
                if (title.isEmpty() || title.equalsIgnoreCase("Google News")) continue;
                headlines.add(title.length() > 160 ? title.substring(0, 157) + "..." : title);
                String blob = title.toUpperCase(Locale.ROOT);
                bull += scoreBull(blob);
                bear += scoreBear(blob);
            }
        }
        String sentiment;
        if (headlines.isEmpty()) {
            sentiment = "unknown";
        } else if (bull > bear + 1) {
            sentiment = "bullish";
        } else if (bear > bull + 1) {
            sentiment = "bearish";
        } else {
            sentiment = "mixed/neutral";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Latest crypto news sentiment for ").append(symbol.isBlank() ? "market" : symbol)
                .append(": ").append(sentiment).append(".\n");
        if (!headlines.isEmpty()) {
            sb.append("Recent headlines:\n");
            for (int i = 0; i < headlines.size(); i++) {
                sb.append(i + 1).append(". ").append(headlines.get(i)).append('\n');
            }
        } else {
            sb.append("No recent headlines matched; rely on candles only.\n");
        }
        sb.append("Bias entries with this sentiment (favor longs if bullish, shorts if bearish, ")
                .append("stay selective if mixed) but still require technical confirmation — ")
                .append("do not trade news alone.\n");
        return new NewsBrief(sentiment, headlines, sb.toString());
    }

    private static int scoreBull(String blob) {
        int n = 0;
        for (String w : List.of("SURGE", "RALLY", "BULL", "ATH", "APPROVAL", "ETF",
                "PARTNERSHIP", "ADOPTION", "RECORD HIGH", "BREAKOUT", "SOARS", "JUMPS", "GAINS")) {
            if (blob.contains(w)) n++;
        }
        return n;
    }

    private static int scoreBear(String blob) {
        int n = 0;
        for (String w : List.of("CRASH", "PLUNGE", "BEAR", "HACK", "BAN", "SEC", "LAWSUIT",
                "LIQUIDAT", "SELL-OFF", "SELLOFF", "FRAUD", "OUTAGE", "EXPLOIT", "DUMP", "DROPS")) {
            if (blob.contains(w)) n++;
        }
        return n;
    }
}
