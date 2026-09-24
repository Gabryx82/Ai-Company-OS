package com.aicompany.backend.usage;

import com.aicompany.backend.usage.local.ClaudeCodeUsage;
import com.aicompany.backend.usage.local.CodexRateLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Where the local usage records live. Defaults are the tools' own homes; tests
 * point both at fixture folders.
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

    @Bean
    Clock usageClock() {
        return Clock.systemUTC();
    }
}
