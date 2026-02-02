package com.webrtc.config;

import com.webrtc.signaling.SignalingHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket Configuration for the Signaling Server
 *
 * WebRTC requires a signaling mechanism for two peers to exchange connection metadata
 * BEFORE they can talk directly. WebSocket is ideal for this because:
 *
 * - It provides full-duplex communication (both sides can send messages anytime)
 * - Low latency compared to HTTP polling
 * - The signaling phase is short-lived — once P2P is established, the WebSocket
 *   is only needed for optional coordination (e.g., hang-up notification)
 *
 * The endpoint "/ws/signaling" is where browsers connect to exchange:
 * - SDP offers/answers (media capabilities and network info)
 * - ICE candidates (possible network paths for the P2P connection)
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingHandler signalingHandler;

    public WebSocketConfig(SignalingHandler signalingHandler) {
        this.signalingHandler = signalingHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalingHandler, "/ws/signaling")
                .setAllowedOrigins("*"); // Allow all origins for demo purposes
    }
}
