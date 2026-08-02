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
            new Preset("OPENROUTER", "OpenRouter", "OPENAI",
                    "https://openrouter.ai/api",
                    List.of("deepseek/deepseek-chat-v3.1:free",
                            "meta-llama/llama-3.3-70b-instruct:free",
                            "qwen/qwen-2.5-72b-instruct:free")),
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
