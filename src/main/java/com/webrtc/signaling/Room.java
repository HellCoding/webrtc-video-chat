package com.webrtc.signaling;

import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a signaling room that holds up to 2 WebRTC peers.
 *
 * WebRTC is designed for peer-to-peer communication, so each "room" is a 1-to-1 call.
 * The room exists only on the signaling server to pair two users together.
 * Once both users join and complete the WebRTC handshake, media flows directly
 * between their browsers — the room (and the server) is no longer in the media path.
 */
public class Room {

    private final String roomId;

    // CopyOnWriteArrayList is thread-safe for concurrent reads and infrequent writes.
    // Since rooms have at most 2 sessions and writes are rare (join/leave), this is ideal.
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    private static final int MAX_PARTICIPANTS = 2;

    public Room(String roomId) {
        this.roomId = roomId;
    }

    public String getRoomId() {
        return roomId;
    }

    /**
     * Add a peer to this room.
     *
     * @return true if successfully added, false if room is already full
     */
    public boolean addSession(WebSocketSession session) {
        if (sessions.size() >= MAX_PARTICIPANTS) {
            return false;
        }
        sessions.add(session);
        return true;
    }

    /**
     * Remove a peer from this room (e.g., when they hang up or disconnect).
     */
    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
    }

    /**
     * Get the OTHER peer in the room.
     *
     * This is the core of signaling: when Peer A sends an offer,
     * the server needs to find Peer B (the other session) to relay it to.
     *
     * @param session the current peer's session
     * @return the other peer's session, or null if alone in the room
     */
    public WebSocketSession getOtherSession(WebSocketSession session) {
        for (WebSocketSession s : sessions) {
            if (!s.getId().equals(session.getId())) {
                return s;
            }
        }
        return null;
    }

    public boolean isFull() {
        return sessions.size() >= MAX_PARTICIPANTS;
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    public int getParticipantCount() {
        return sessions.size();
    }

    public List<WebSocketSession> getSessions() {
        return sessions;
    }
}
