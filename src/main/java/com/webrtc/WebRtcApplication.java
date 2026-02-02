package com.webrtc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WebRTC Video Chat Application
 *
 * This Spring Boot application serves two roles:
 * 1. Static file server - delivers the HTML/JS/CSS frontend to browsers
 * 2. WebSocket signaling server - helps two browsers find each other and establish a direct P2P connection
 *
 * IMPORTANT: The server NEVER handles video or audio data.
 * Once WebRTC peers are connected, media flows directly between browsers (P2P).
 * The server is only needed during the initial "handshake" phase.
 *
 * To test: run this application, open two browser tabs to http://localhost:8080,
 * enter the same room ID in both, and click Join.
 */
@SpringBootApplication
public class WebRtcApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebRtcApplication.class, args);
    }
}
