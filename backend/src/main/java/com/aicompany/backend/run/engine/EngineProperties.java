package com.aicompany.backend.run.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the AI Engine is and how to talk to it: {@code aicos.engine.*}.
 *
 * <p>The read timeout is long on purpose: a local model answers in tens of
 * seconds (TASK-018 measured 13 s for 107 tokens on a 3B model), and a run is
 * executed off the request thread, so nobody waits on it but the executor.
 */
@ConfigurationProperties(prefix = "aicos.engine")
public class EngineProperties {

    private String url = "http://127.0.0.1:8090";
    private String token;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofMinutes(10);
    private int maxTokens = 4096;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}
