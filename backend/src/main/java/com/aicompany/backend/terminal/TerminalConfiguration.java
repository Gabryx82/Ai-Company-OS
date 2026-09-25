package com.aicompany.backend.terminal;

import com.aicompany.backend.user.service.SecurityLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
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
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class TerminalConfiguration implements WebSocketConfigurer {

    public static final String SOCKET_PATH = "/api/terminal/ws";

    private final TerminalTickets tickets;
    private final SecurityLog log;
    private final List<String> origins;

    public TerminalConfiguration(TerminalTickets tickets, SecurityLog log,
                                 @Value("${aicos.cors.allowed-origins:}") List<String> origins) {
        this.tickets = tickets;
        this.log = log;
        this.origins = origins == null ? List.of() : origins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    }

    @Bean
    TerminalSocketHandler terminalSocketHandler() {
        return new TerminalSocketHandler(tickets, log);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalSocketHandler(), SOCKET_PATH).setAllowedOrigins(origins.toArray(String[]::new));
    }
}
