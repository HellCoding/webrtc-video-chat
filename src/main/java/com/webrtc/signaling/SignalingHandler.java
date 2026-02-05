package com.webrtc.signaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebRTC Signaling Server
 *
 * =====================================================================
 * WHY DO WE NEED A SIGNALING SERVER?
 * =====================================================================
 *
 * WebRTC enables direct peer-to-peer video/audio between browsers,
 * but there's a chicken-and-egg problem:
 *
 *   "How do two browsers find each other on the internet?"
 *
 * Browsers don't have public addresses. They sit behind NATs, firewalls,
 * and routers. They need a way to exchange connection metadata BEFORE
 * they can talk directly. That's what this signaling server does.
 *
 * =====================================================================
 * WHAT DOES THE SIGNALING SERVER DO?
 * =====================================================================
 *
 * It relays three types of messages between peers:
 *
 * 1. SDP Offer/Answer — "Here's what media I can send/receive"
 *    Contains: supported video/audio codecs, encryption keys, network info
 *
 * 2. ICE Candidates — "Here are possible network paths to reach me"
 *    ICE (Interactive Connectivity Establishment) tries multiple paths:
 *    - Direct LAN connection (same network)
 *    - STUN: discovers public IP behind NAT
 *    - TURN: relay server as last resort (if direct fails)
 *
 * 3. Room management — joining, leaving, and peer notifications
 *
 * =====================================================================
 * WHAT DOES THE SIGNALING SERVER NOT DO?
 * =====================================================================
 *
 * It NEVER sees video or audio data. Once the WebRTC handshake completes,
 * media flows directly between the two browsers. The server could go
 * offline at that point and the call would continue.
 *
 * =====================================================================
 * THE COMPLETE SIGNALING FLOW
 * =====================================================================
 *
 *  Browser A                 Server                 Browser B
 *     |                        |                        |
 *     |--- join(room) -------->|                        |
 *     |<-- waiting ------------|                        |
 *     |                        |<------ join(room) -----|
 *     |<-- ready --------------|-------- ready -------->|
 *     |                        |                        |
 *     |--- offer (SDP) ------->|-------- offer -------->|
 *     |                        |<------ answer (SDP) ---|
 *     |<-- answer -------------|                        |
 *     |                        |                        |
 *     |--- ice-candidate ----->|--- ice-candidate ----->|
 *     |<-- ice-candidate ------|<-- ice-candidate ------|
 *     |                        |                        |
 *     |========== P2P connection established ===========|
 *     |<====== video/audio flows directly (no server) =>|
 */
@Component
public class SignalingHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Room management: roomId -> Room
    // ConcurrentHashMap ensures thread-safety since multiple WebSocket sessions
    // may join/leave rooms simultaneously from different threads.
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    // Reverse lookup: sessionId -> roomId (to find a user's room when they disconnect)
    private final ConcurrentHashMap<String, String> sessionRoomMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connected: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode jsonMessage = objectMapper.readTree(message.getPayload());
        String type = jsonMessage.get("type").asText();

        log.info("Received message: type={}, sessionId={}", type, session.getId());

        switch (type) {
            case "join" -> handleJoin(session, jsonMessage);
            case "offer" -> relayMessage(session, jsonMessage);
            case "answer" -> relayMessage(session, jsonMessage);
            case "ice-candidate" -> relayMessage(session, jsonMessage);
            case "leave" -> handleLeave(session);
            default -> log.warn("Unknown message type: {}", type);
        }
    }

    /**
     * Handle a "join" request.
     *
     * When a user wants to start or join a video call, they send:
     *   {"type": "join", "roomId": "some-room-name"}
     *
     * Possible outcomes:
     * - Room is empty/new: user waits alone (gets "waiting" message)
     * - Room has 1 person: user joins, both get "ready" (can start WebRTC handshake)
     * - Room is full (2 people): user gets "full" error
     */
    private void handleJoin(WebSocketSession session, JsonNode message) throws IOException {
        String roomId = message.get("roomId").asText();

        Room room = rooms.computeIfAbsent(roomId, Room::new);

        if (room.isFull()) {
            // Room already has 2 participants — can't join
            sendMessage(session, createMessage("full", null));
            log.info("Room '{}' is full, rejecting sessionId={}", roomId, session.getId());
            return;
        }

        room.addSession(session);
        sessionRoomMap.put(session.getId(), roomId);

        log.info("Session {} joined room '{}' (participants: {})",
                session.getId(), roomId, room.getParticipantCount());

        if (room.getParticipantCount() == 1) {
            // First person in room — wait for a peer
            sendMessage(session, createMessage("waiting", null));
        } else {
            // Second person joined — notify BOTH peers they can start the WebRTC handshake.
            // The "ready" message with isInitiator=true tells one peer to create the offer.
            // Only one side should create the offer to avoid a "glare" condition
            // (both sides creating offers simultaneously).
            for (WebSocketSession s : room.getSessions()) {
                boolean isInitiator = !s.getId().equals(session.getId());
                ObjectNode readyMessage = createMessage("ready", null);
                readyMessage.put("isInitiator", isInitiator);
                sendMessage(s, readyMessage);
            }
        }
    }

    /**
     * Relay a message to the OTHER peer in the room.
     *
     * This is the core signaling function. When Peer A sends an SDP offer,
     * this method finds Peer B (the other session in the same room)
     * and forwards the message unchanged.
     *
     * The server doesn't need to understand the content of SDP or ICE messages —
     * it's just a relay. The browsers handle all the WebRTC logic.
     */
    private void relayMessage(WebSocketSession session, JsonNode message) throws IOException {
        String roomId = sessionRoomMap.get(session.getId());
        if (roomId == null) {
            log.warn("Session {} tried to send a message but is not in any room", session.getId());
            return;
        }

        Room room = rooms.get(roomId);
        if (room == null) {
            log.warn("Room '{}' not found for session {}", roomId, session.getId());
            return;
        }

        WebSocketSession otherSession = room.getOtherSession(session);
        if (otherSession != null && otherSession.isOpen()) {
            otherSession.sendMessage(new TextMessage(message.toString()));
            log.debug("Relayed '{}' from session {} to session {}",
                    message.get("type").asText(), session.getId(), otherSession.getId());
        } else {
            log.warn("No peer to relay message to in room '{}'", roomId);
        }
    }

    /**
     * Handle explicit leave or disconnect.
     *
     * When a peer leaves, we notify the other peer so they can clean up
     * their RTCPeerConnection and UI.
     */
    private void handleLeave(WebSocketSession session) throws IOException {
        String roomId = sessionRoomMap.remove(session.getId());
        if (roomId == null) return;

        Room room = rooms.get(roomId);
        if (room == null) return;

        // Notify the other peer that this user left
        WebSocketSession otherSession = room.getOtherSession(session);
        room.removeSession(session);

        if (otherSession != null && otherSession.isOpen()) {
            sendMessage(otherSession, createMessage("peer-left", null));
        }

        // Clean up empty rooms to prevent memory leaks
        if (room.isEmpty()) {
            rooms.remove(roomId);
            log.info("Room '{}' is empty, removed", roomId);
        }

        log.info("Session {} left room '{}'", session.getId(), roomId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket disconnected: sessionId={}, status={}", session.getId(), status);
        try {
            handleLeave(session);
        } catch (IOException e) {
            log.error("Error handling disconnect for session {}", session.getId(), e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error: sessionId={}", session.getId(), exception);
        try {
            handleLeave(session);
        } catch (IOException e) {
            log.error("Error handling transport error for session {}", session.getId(), e);
        }
    }

    // --- Helper methods ---

    private ObjectNode createMessage(String type, JsonNode payload) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", type);
        if (payload != null) {
            message.set("payload", payload);
        }
        return message;
    }

    private void sendMessage(WebSocketSession session, JsonNode message) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(message.toString()));
        }
    }

    // Visible for testing
    ConcurrentHashMap<String, Room> getRooms() {
        return rooms;
    }

    ConcurrentHashMap<String, String> getSessionRoomMap() {
        return sessionRoomMap;
    }
}
