package com.webrtc.signaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for the WebRTC Signaling Handler.
 *
 * These tests verify the signaling server correctly:
 * - Manages room join/leave
 * - Relays SDP offers/answers between peers
 * - Relays ICE candidates between peers
 * - Handles edge cases (full rooms, missing peers)
 */
class SignalingHandlerTest {

    private SignalingHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = new SignalingHandler();
        objectMapper = new ObjectMapper();
    }

    // --- Helper methods ---

    private WebSocketSession createMockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private TextMessage createJoinMessage(String roomId) throws Exception {
        return new TextMessage(
                objectMapper.writeValueAsString(
                        objectMapper.createObjectNode()
                                .put("type", "join")
                                .put("roomId", roomId)
                )
        );
    }

    private TextMessage createOfferMessage() throws Exception {
        return new TextMessage(
                objectMapper.writeValueAsString(
                        objectMapper.createObjectNode()
                                .put("type", "offer")
                                .put("sdp", "v=0\r\n...")
                )
        );
    }

    private TextMessage createIceCandidateMessage() throws Exception {
        return new TextMessage(
                objectMapper.writeValueAsString(
                        objectMapper.createObjectNode()
                                .put("type", "ice-candidate")
                                .put("candidate", "candidate:1 1 UDP 2122252543 192.168.1.1 12345 typ host")
                                .put("sdpMid", "0")
                                .put("sdpMLineIndex", 0)
                )
        );
    }

    private String getLastSentMessageType(WebSocketSession session) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        String lastPayload = captor.getValue().getPayload();
        JsonNode node = objectMapper.readTree(lastPayload);
        return node.get("type").asText();
    }

    // --- Tests ---

    @Test
    @DisplayName("First user joins a room and receives 'waiting' message")
    void testJoinRoom_firstUser_shouldWait() throws Exception {
        WebSocketSession session1 = createMockSession("session-1");

        handler.handleMessage(session1, createJoinMessage("test-room"));

        // Verify the user received a "waiting" message
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(captor.capture());

        JsonNode response = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(response.get("type").asText()).isEqualTo("waiting");

        // Verify the room was created with 1 participant
        Room room = handler.getRooms().get("test-room");
        assertThat(room).isNotNull();
        assertThat(room.getParticipantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Second user joins and both receive 'ready' message")
    void testJoinRoom_secondUser_shouldTriggerReady() throws Exception {
        WebSocketSession session1 = createMockSession("session-1");
        WebSocketSession session2 = createMockSession("session-2");

        // First user joins
        handler.handleMessage(session1, createJoinMessage("test-room"));
        reset(session1); // Clear the "waiting" message verification
        when(session1.getId()).thenReturn("session-1");
        when(session1.isOpen()).thenReturn(true);

        // Second user joins
        handler.handleMessage(session2, createJoinMessage("test-room"));

        // Both sessions should receive a "ready" message
        var captor1 = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(captor1.capture());
        JsonNode response1 = objectMapper.readTree(captor1.getValue().getPayload());
        assertThat(response1.get("type").asText()).isEqualTo("ready");
        // First user (session1) should be the initiator
        assertThat(response1.get("isInitiator").asBoolean()).isTrue();

        var captor2 = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session2).sendMessage(captor2.capture());
        JsonNode response2 = objectMapper.readTree(captor2.getValue().getPayload());
        assertThat(response2.get("type").asText()).isEqualTo("ready");
        // Second user (session2) should NOT be the initiator
        assertThat(response2.get("isInitiator").asBoolean()).isFalse();

        // Room should have 2 participants
        Room room = handler.getRooms().get("test-room");
        assertThat(room.getParticipantCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Third user gets 'full' when room already has 2 users")
    void testJoinRoom_thirdUser_shouldGetFull() throws Exception {
        WebSocketSession session1 = createMockSession("session-1");
        WebSocketSession session2 = createMockSession("session-2");
        WebSocketSession session3 = createMockSession("session-3");

        // Fill the room
        handler.handleMessage(session1, createJoinMessage("test-room"));
        handler.handleMessage(session2, createJoinMessage("test-room"));

        // Third user tries to join
        handler.handleMessage(session3, createJoinMessage("test-room"));

        // session3 should get "full"
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session3).sendMessage(captor.capture());
        JsonNode response = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(response.get("type").asText()).isEqualTo("full");
    }

    @Test
    @DisplayName("Offer message is relayed to the other peer in the room")
    void testRelayOffer_shouldSendToOtherPeer() throws Exception {
        WebSocketSession session1 = createMockSession("session-1");
        WebSocketSession session2 = createMockSession("session-2");

        // Both join the room
        handler.handleMessage(session1, createJoinMessage("test-room"));
        handler.handleMessage(session2, createJoinMessage("test-room"));

        // Clear previous interactions
        reset(session1, session2);
        when(session1.isOpen()).thenReturn(true);
        when(session1.getId()).thenReturn("session-1");
        when(session2.isOpen()).thenReturn(true);
        when(session2.getId()).thenReturn("session-2");

        // Session 1 sends an offer
        handler.handleMessage(session1, createOfferMessage());

        // Session 2 should receive the offer (relayed by server)
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session2).sendMessage(captor.capture());
        JsonNode relayed = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(relayed.get("type").asText()).isEqualTo("offer");
        assertThat(relayed.get("sdp").asText()).isEqualTo("v=0\r\n...");

        // Session 1 should NOT receive its own offer back
        verify(session1, never()).sendMessage(any());
    }

    @Test
    @DisplayName("ICE candidate is relayed to the other peer")
    void testRelayIceCandidate_shouldSendToOtherPeer() throws Exception {
        WebSocketSession session1 = createMockSession("session-1");
        WebSocketSession session2 = createMockSession("session-2");

        // Both join
        handler.handleMessage(session1, createJoinMessage("test-room"));
        handler.handleMessage(session2, createJoinMessage("test-room"));

        reset(session1, session2);
        when(session1.isOpen()).thenReturn(true);
        when(session1.getId()).thenReturn("session-1");
        when(session2.isOpen()).thenReturn(true);
        when(session2.getId()).thenReturn("session-2");

        // Session 1 sends ICE candidate
        handler.handleMessage(session1, createIceCandidateMessage());

        // Session 2 should receive it
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session2).sendMessage(captor.capture());
        JsonNode relayed = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(relayed.get("type").asText()).isEqualTo("ice-candidate");
    }

    @Test
    @DisplayName("Peer disconnect notifies the other peer and cleans up room")
    void testDisconnect_shouldNotifyPeerAndCleanup() throws Exception {
        WebSocketSession session1 = createMockSession("session-1");
        WebSocketSession session2 = createMockSession("session-2");

        // Both join
        handler.handleMessage(session1, createJoinMessage("test-room"));
        handler.handleMessage(session2, createJoinMessage("test-room"));

        reset(session1, session2);
        when(session1.isOpen()).thenReturn(true);
        when(session1.getId()).thenReturn("session-1");
        when(session2.isOpen()).thenReturn(true);
        when(session2.getId()).thenReturn("session-2");

        // Session 1 sends leave
        handler.handleMessage(session1, new TextMessage("{\"type\":\"leave\"}"));

        // Session 2 should be notified
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session2).sendMessage(captor.capture());
        JsonNode response = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(response.get("type").asText()).isEqualTo("peer-left");
    }
}
