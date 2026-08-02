package com.cryptoalgo.backend.ai;

import java.util.List;
import java.util.Optional;

/**
 * Built-in catalog of free-tier LLM providers. The admin only supplies an API
 * key; the base URL, API dialect and the ordered model fallback chain are
 * preset here. On a 429 the strategy engine walks the model chain within a
 * provider, then falls through to the next configured provider.
 */
public final class ProviderCatalog {

    /** dialect: GEMINI (Google native) or OPENAI (OpenAI-compatible chat API). */
    public record Preset(String type, String displayName, String dialect,
                         String baseUrl, List<String> models) {}

    public static final List<Preset> PRESETS = List.of(
            new Preset("GEMINI", "Google Gemini", "GEMINI",
                    "https://generativelanguage.googleapis.com",
                    // Prefer currently served aliases; retired model ids 404 for new keys.
                    List.of("gemini-flash-latest", "gemini-2.0-flash-lite", "gemini-2.0-flash")),
            new Preset("GROQ", "Groq", "OPENAI",
                    "https://api.groq.com/openai",
                    List.of("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant")),
            // Native OpenAI (sk-…). Do NOT paste this key into OpenRouter — that needs an OpenRouter key.
            new Preset("OPENAI", "OpenAI", "OPENAI",
                    "https://api.openai.com",
                    List.of("gpt-4.1-mini", "gpt-4o-mini", "gpt-4.1")),
            new Preset("OPENROUTER", "OpenRouter", "OPENAI",
                    "https://openrouter.ai/api",
                    // Free slugs rotate; prefer currently listed openrouter free endpoints.
                    List.of("openrouter/free",
                            "google/gemma-3-27b-it:free",
                            "mistralai/mistral-small-3.1-24b-instruct:free")),
            new Preset("MISTRAL", "Mistral AI", "OPENAI",
                    "https://api.mistral.ai",
                    List.of("mistral-large-latest", "mistral-medium-latest", "mistral-small-latest")),
            new Preset("CEREBRAS", "Cerebras", "OPENAI",
                    "https://api.cerebras.ai",
                    List.of("llama-3.3-70b", "gpt-oss-120b", "llama3.1-8b")));

    public static Optional<Preset> byType(String type) {
        return PRESETS.stream().filter(p -> p.type().equals(type)).findFirst();
    }

    private ProviderCatalog() {}
}
