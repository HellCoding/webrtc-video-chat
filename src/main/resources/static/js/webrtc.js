/**
 * WebRTC Video Chat — Client-Side Logic
 *
 * =======================================================================
 * WebRTC Connection Flow (the complete picture)
 * =======================================================================
 *
 * 1. getUserMedia()          → Get camera/microphone access from the browser
 * 2. WebSocket connect       → Connect to our signaling server
 * 3. Join room               → Tell the server which room to join
 * 4. createOffer()           → Generate SDP (Session Description Protocol)
 *    SDP contains: supported video/audio codecs, encryption params, network info
 * 5. setLocalDescription()   → Set our own SDP on the RTCPeerConnection
 * 6. Send offer via signaling→ Server relays the offer to the other peer
 * 7. Peer creates answer     → Their SDP describing their capabilities
 * 8. setRemoteDescription()  → Set the peer's SDP on our RTCPeerConnection
 * 9. ICE candidate exchange  → Find the best network path between peers
 *    ICE = Interactive Connectivity Establishment
 *    It tries multiple paths in order:
 *      a) Direct LAN (same network, fastest)
 *      b) STUN server (discovers public IP behind NAT)
 *      c) TURN server (relay through a server, last resort)
 * 10. ontrack event          → Receive the peer's video/audio stream
 * 11. P2P established!       → Media flows directly, server no longer needed
 *
 * =======================================================================
 */

// --- State ---
let localStream = null;       // Our camera/microphone stream
let peerConnection = null;    // The RTCPeerConnection (the WebRTC magic)
let webSocket = null;         // WebSocket to the signaling server
let currentRoomId = null;     // The room we joined

// --- DOM Elements ---
const localVideo = document.getElementById('localVideo');
const remoteVideo = document.getElementById('remoteVideo');
const roomInput = document.getElementById('roomInput');
const joinBtn = document.getElementById('joinBtn');
const hangupBtn = document.getElementById('hangupBtn');
const statusPanel = document.getElementById('statusPanel');

// --- STUN Server Configuration ---
// STUN (Session Traversal Utilities for NAT) helps discover our public IP address.
// When you're behind a router/NAT, your browser doesn't know its public IP.
// The STUN server tells the browser: "From the outside, you look like IP:port X."
// This information is included in ICE candidates so the peer knows how to reach us.
//
// Google provides free public STUN servers. In production, you'd also want a TURN
// server for cases where direct P2P fails (e.g., symmetric NATs, strict firewalls).
const rtcConfig = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
    ]
};


// ===================================================================
// STEP 1: Join a Room
// ===================================================================
// This is the entry point. When the user clicks "Join Room":
// 1. Get camera/mic access
// 2. Connect to the signaling WebSocket
// 3. Send a "join" message to enter the specified room
// ===================================================================

async function joinRoom() {
    const roomId = roomInput.value.trim();
    if (!roomId) {
        logStatus('Please enter a Room ID.', 'warn');
        return;
    }

    currentRoomId = roomId;
    joinBtn.disabled = true;
    hangupBtn.disabled = false;

    try {
        // STEP 1a: Get camera and microphone access.
        // getUserMedia() prompts the user for permission.
        // The returned MediaStream contains video and audio tracks.
        logStatus('Requesting camera and microphone access...', 'info');
        localStream = await navigator.mediaDevices.getUserMedia({
            video: true,
            audio: true
        });
        localVideo.srcObject = localStream;
        logStatus('Camera and microphone access granted.', 'success');

        // STEP 1b: Connect to the signaling server via WebSocket.
        // The signaling server relays messages between peers to help them
        // establish a direct P2P connection.
        connectWebSocket(roomId);

    } catch (error) {
        logStatus('Failed to access camera/microphone: ' + error.message, 'error');
        resetUI();
    }
}


// ===================================================================
// STEP 2: Connect to Signaling Server (WebSocket)
// ===================================================================
// The WebSocket connection to our Spring Boot server.
// This is NOT for video/audio — only for exchanging connection metadata.
// ===================================================================

function connectWebSocket(roomId) {
    logStatus('Connecting to signaling server...', 'info');

    // Determine WebSocket URL based on current page protocol (ws:// or wss://)
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/signaling`;

    webSocket = new WebSocket(wsUrl);

    webSocket.onopen = () => {
        logStatus('Connected to signaling server.', 'success');

        // Send join request to enter the room
        logStatus(`Joining room: "${roomId}"...`, 'info');
        sendSignal({ type: 'join', roomId: roomId });
    };

    // Handle incoming signaling messages
    webSocket.onmessage = (event) => {
        const message = JSON.parse(event.data);
        handleSignalingMessage(message);
    };

    webSocket.onclose = () => {
        logStatus('Disconnected from signaling server.', 'warn');
    };

    webSocket.onerror = (error) => {
        logStatus('WebSocket error occurred.', 'error');
        console.error('WebSocket error:', error);
    };
}


// ===================================================================
// Handle Messages from the Signaling Server
// ===================================================================
// The signaling server sends us messages from the other peer.
// Each message type triggers a different step in the WebRTC handshake.
// ===================================================================

function handleSignalingMessage(message) {
    switch (message.type) {
        // "waiting" — We joined the room but no peer is here yet.
        case 'waiting':
            logStatus('Joined room. Waiting for a peer to connect...', 'info');
            break;

        // "ready" — Both peers are in the room. Time to start the WebRTC handshake!
        // The peer with isInitiator=true creates the offer.
        case 'ready':
            logStatus('Peer joined! Starting WebRTC handshake...', 'success');
            createPeerConnection();
            if (message.isInitiator) {
                // We are the initiator — we create the SDP offer
                createOffer();
            }
            break;

        // "offer" — The other peer sent us their SDP offer.
        // We need to respond with an SDP answer.
        case 'offer':
            logStatus('Received SDP offer from peer.', 'info');
            if (!peerConnection) {
                createPeerConnection();
            }
            handleOffer(message);
            break;

        // "answer" — The other peer responded to our offer with their SDP answer.
        case 'answer':
            logStatus('Received SDP answer from peer.', 'info');
            handleAnswer(message);
            break;

        // "ice-candidate" — The other peer found a possible network path.
        // We add it to our connection so ICE can try it.
        case 'ice-candidate':
            logStatus('Received ICE candidate from peer.', 'info');
            handleIceCandidate(message);
            break;

        // "peer-left" — The other peer disconnected.
        case 'peer-left':
            logStatus('Peer has left the room.', 'warn');
            closePeerConnection();
            break;

        // "full" — Room is full, can't join.
        case 'full':
            logStatus('Room is full! Try a different Room ID.', 'error');
            resetUI();
            break;

        default:
            console.warn('Unknown message type:', message.type);
    }
}


// ===================================================================
// STEP 3: Create RTCPeerConnection
// ===================================================================
// RTCPeerConnection is THE core WebRTC API. It handles:
// - SDP negotiation (offer/answer)
// - ICE candidate gathering (finding network paths)
// - DTLS encryption (securing the media)
// - Sending/receiving media tracks (video + audio)
//
// We configure it with STUN servers so it can discover our public IP.
// ===================================================================

function createPeerConnection() {
    logStatus('Creating RTCPeerConnection...', 'info');

    peerConnection = new RTCPeerConnection(rtcConfig);

    // Add our local video/audio tracks to the connection.
    // These tracks will be sent to the peer once connected.
    localStream.getTracks().forEach(track => {
        peerConnection.addTrack(track, localStream);
    });

    // --- ICE Candidate Event ---
    // As ICE gathers possible network paths, this event fires for each candidate.
    // We send each candidate to the peer via the signaling server.
    // The peer will try these candidates to find one that works.
    peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
            logStatus('Sending ICE candidate to peer...', 'info');
            sendSignal({
                type: 'ice-candidate',
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex
            });
        }
    };

    // --- Track Event ---
    // When the peer's media track arrives, display it in the remote video element.
    // This is the moment the actual video/audio starts flowing P2P!
    peerConnection.ontrack = (event) => {
        logStatus('Receiving remote video/audio stream!', 'success');
        if (remoteVideo.srcObject !== event.streams[0]) {
            remoteVideo.srcObject = event.streams[0];
        }
    };

    // --- Connection State Changes ---
    // Monitor the ICE connection state to show progress to the user.
    peerConnection.oniceconnectionstatechange = () => {
        const state = peerConnection.iceConnectionState;
        switch (state) {
            case 'checking':
                logStatus('ICE: Checking candidate pairs...', 'info');
                break;
            case 'connected':
                logStatus('P2P connection established! Video is now flowing directly.', 'success');
                break;
            case 'completed':
                logStatus('ICE negotiation completed. Optimal path found.', 'success');
                break;
            case 'disconnected':
                logStatus('P2P connection interrupted. Attempting to reconnect...', 'warn');
                break;
            case 'failed':
                logStatus('P2P connection failed. Could not find a network path.', 'error');
                break;
            case 'closed':
                logStatus('P2P connection closed.', 'info');
                break;
        }
    };

    logStatus('RTCPeerConnection created with STUN server config.', 'success');
}


// ===================================================================
// STEP 4: Create and Send SDP Offer
// ===================================================================
// The initiator (first peer in the room) creates an SDP offer.
//
// SDP (Session Description Protocol) is a text format that describes:
// - What media we can send (video H.264, audio Opus, etc.)
// - Our network information (IP, ports)
// - Encryption parameters (DTLS fingerprint)
//
// The offer says: "Here's what I can do. What can you do?"
// ===================================================================

async function createOffer() {
    try {
        logStatus('Creating SDP offer...', 'info');

        // createOffer() generates our SDP based on our media capabilities
        const offer = await peerConnection.createOffer();

        // setLocalDescription() applies the SDP to our connection AND
        // triggers ICE candidate gathering (finding network paths)
        await peerConnection.setLocalDescription(offer);

        logStatus('SDP offer created and set as local description.', 'success');
        logStatus('Sending offer to peer via signaling server...', 'info');

        // Send the offer through the signaling server to the peer
        sendSignal({
            type: 'offer',
            sdp: offer.sdp
        });
    } catch (error) {
        logStatus('Failed to create offer: ' + error.message, 'error');
    }
}


// ===================================================================
// STEP 5: Handle Incoming SDP Offer (create answer)
// ===================================================================
// When we receive an offer from the peer, we:
// 1. Set it as our remote description (what the peer can do)
// 2. Create an answer (what WE can do in response)
// 3. Send the answer back
//
// The answer says: "Based on your offer, here's what we'll use."
// ===================================================================

async function handleOffer(message) {
    try {
        // Set the peer's SDP as our remote description
        const offerDesc = new RTCSessionDescription({
            type: 'offer',
            sdp: message.sdp
        });
        await peerConnection.setRemoteDescription(offerDesc);
        logStatus('Remote description (offer) set.', 'success');

        // Create our SDP answer
        logStatus('Creating SDP answer...', 'info');
        const answer = await peerConnection.createAnswer();
        await peerConnection.setLocalDescription(answer);

        logStatus('SDP answer created. Sending to peer...', 'success');

        // Send the answer back through the signaling server
        sendSignal({
            type: 'answer',
            sdp: answer.sdp
        });
    } catch (error) {
        logStatus('Failed to handle offer: ' + error.message, 'error');
    }
}


// ===================================================================
// STEP 6: Handle Incoming SDP Answer
// ===================================================================
// The peer responded to our offer with an answer.
// Set it as our remote description to complete the SDP exchange.
// After this, both sides know each other's media capabilities.
// Now ICE just needs to find a working network path.
// ===================================================================

async function handleAnswer(message) {
    try {
        const answerDesc = new RTCSessionDescription({
            type: 'answer',
            sdp: message.sdp
        });
        await peerConnection.setRemoteDescription(answerDesc);
        logStatus('Remote description (answer) set. SDP exchange complete!', 'success');
    } catch (error) {
        logStatus('Failed to handle answer: ' + error.message, 'error');
    }
}


// ===================================================================
// STEP 7: Handle ICE Candidates
// ===================================================================
// ICE candidates represent possible network paths between peers.
// Each candidate is a potential IP:port combination that might work.
//
// ICE tries paths in order of preference:
// 1. host      — Direct LAN (same WiFi network, fastest)
// 2. srflx     — Server Reflexive (public IP discovered via STUN)
// 3. relay     — TURN relay (goes through a server, slowest but most reliable)
//
// Both peers exchange candidates simultaneously. ICE picks the best
// working pair (one candidate from each side).
// ===================================================================

async function handleIceCandidate(message) {
    try {
        if (message.candidate) {
            const candidate = new RTCIceCandidate({
                candidate: message.candidate,
                sdpMid: message.sdpMid,
                sdpMLineIndex: message.sdpMLineIndex
            });
            await peerConnection.addIceCandidate(candidate);
        }
    } catch (error) {
        // ICE candidate errors are often non-fatal (e.g., duplicate candidates)
        console.warn('ICE candidate error:', error);
    }
}


// ===================================================================
// Hang Up — End the Call
// ===================================================================

function hangUp() {
    logStatus('Hanging up...', 'info');

    // Notify the signaling server so it can tell the peer
    if (webSocket && webSocket.readyState === WebSocket.OPEN) {
        sendSignal({ type: 'leave' });
    }

    closePeerConnection();
    closeWebSocket();
    resetUI();

    logStatus('Call ended.', 'info');
}


// ===================================================================
// Cleanup Helpers
// ===================================================================

function closePeerConnection() {
    if (peerConnection) {
        peerConnection.close();
        peerConnection = null;
    }
    remoteVideo.srcObject = null;
}

function closeWebSocket() {
    if (webSocket) {
        webSocket.close();
        webSocket = null;
    }
}

function resetUI() {
    // Stop camera/mic
    if (localStream) {
        localStream.getTracks().forEach(track => track.stop());
        localStream = null;
    }
    localVideo.srcObject = null;
    remoteVideo.srcObject = null;

    joinBtn.disabled = false;
    hangupBtn.disabled = true;
    currentRoomId = null;
}


// ===================================================================
// Signaling Helper — Send JSON to WebSocket
// ===================================================================

function sendSignal(message) {
    if (webSocket && webSocket.readyState === WebSocket.OPEN) {
        webSocket.send(JSON.stringify(message));
    }
}


// ===================================================================
// Status Panel Logger
// ===================================================================
// Logs each WebRTC step to the UI so users can follow the handshake
// process in real time. This is the educational component of the demo.
// ===================================================================

function logStatus(text, level = 'info') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${level}`;

    const now = new Date();
    const time = now.toTimeString().split(' ')[0]; // HH:MM:SS

    const timestamp = document.createElement('span');
    timestamp.className = 'timestamp';
    timestamp.textContent = `[${time}]`;

    entry.appendChild(timestamp);
    entry.appendChild(document.createTextNode(' ' + text));

    statusPanel.appendChild(entry);
    statusPanel.scrollTop = statusPanel.scrollHeight;

    // Also log to console for debugging
    console.log(`[${level.toUpperCase()}] ${text}`);
}
