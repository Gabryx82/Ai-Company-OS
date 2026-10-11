package com.aicompany.backend.usage;

import com.aicompany.backend.usage.local.AntigravityQuota;
import com.aicompany.backend.usage.local.ClaudeCodeUsage;
import com.aicompany.backend.usage.local.CodexRateLimits;
import com.aicompany.backend.usage.remote.KimiCodeUsage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Where the local usage records live. Defaults are the tools' own homes; tests
 * point them at fixture folders.
 */
@Configuration(proxyBeanMethods = false)
public class UsageConfiguration {

    @Bean
    CodexRateLimits codexRateLimits(@Value("${aicos.usage.codex-home:${user.home}/.codex}") String home) {
        return new CodexRateLimits(Path.of(home));
    }

    @Bean
    ClaudeCodeUsage claudeCodeUsage(@Value("${aicos.usage.claude-home:${user.home}/.claude}") String home) {
        return new ClaudeCodeUsage(Path.of(home));
    }

    /** PHASE 33 (ADR-038): the agent manager, plan subject {@code antigravity}. */
    @Bean
    AntigravityQuota antigravityQuota(
            @Value("${aicos.usage.antigravity-home:${APPDATA:${user.home}/AppData/Roaming}/Antigravity}") String home) {
        return new AntigravityQuota("antigravity", Path.of(home));
    }

    /** PHASE 33 (ADR-038): the IDE, plan subject {@code antigravity-ide}. */
    @Bean
    AntigravityQuota antigravityIdeQuota(
            @Value("${aicos.usage.antigravity-ide-home:${APPDATA:${user.home}/AppData/Roaming}/Antigravity IDE}") String home) {
        return new AntigravityQuota("antigravity-ide", Path.of(home));
    }

    /** PHASE 33 (ADR-038): {@code AICOS_KIMI_CODE_API_KEY} from local.env; empty, Kimi Code is not asked. */
    @Bean
    KimiCodeUsage kimiCodeUsage(@Value("${aicos.usage.kimi-code-base-url:https://api.kimi.com/coding/v1}") String baseUrl,
                                @Value("${aicos.usage.kimi-code-api-key:}") String apiKey, Clock usageClock) {
        return new KimiCodeUsage(baseUrl, apiKey, usageClock);
    }

    @Bean
    Clock usageClock() {
        return Clock.systemUTC();
    }
}
