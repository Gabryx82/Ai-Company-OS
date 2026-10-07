package com.aicompany.backend.terminal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

/**
 * The terminal's socket (ADR-029). The browser's WebSocket cannot carry an
 * Authorization header, so the handshake itself is public and the socket is
 * authenticated by its first message, a single-use ticket an admin obtained
 * over the authenticated API. The handshake is still restricted to the
 * declared console origins -- the same list as CORS (ADR-014).
 *
 * <p>The handler registered here is the same bean the API asks for running
 * shells (§5): one place knows which shells exist.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class TerminalConfiguration implements WebSocketConfigurer {

    public static final String SOCKET_PATH = "/api/terminal/ws";

    private final TerminalSocketHandler handler;
    private final List<String> origins;

    public TerminalConfiguration(TerminalSocketHandler handler,
                                 @Value("${aicos.cors.allowed-origins:}") List<String> origins) {
        this.handler = handler;
        this.origins = origins == null ? List.of() : origins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, SOCKET_PATH).setAllowedOrigins(origins.toArray(String[]::new));
    }
}
