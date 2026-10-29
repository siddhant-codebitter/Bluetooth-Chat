package com.example.bluetoothchat.mesh;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.bluetoothchat.bluetooth.BluetoothConnectionManager;
import com.example.bluetoothchat.crypto.CryptoManager;
import com.example.bluetoothchat.crypto.IdentityManager;
import com.example.bluetoothchat.db.AppDatabase;
import com.example.bluetoothchat.db.MessageDao;
import com.example.bluetoothchat.db.PeerDao;
import com.example.bluetoothchat.model.Message;
import com.example.bluetoothchat.model.Packet;
import com.example.bluetoothchat.model.Peer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The brain of the mesh. It decides what happens to every packet:
 *
 *  - HELLO:    first packet on a new link. Tells the neighbour who we are.
 *  - ANNOUNCE: "I exist, this is my name and public key". Flooded through the mesh
 *              so far-away phones learn our key and can send us encrypted messages.
 *  - MESSAGE:  an end-to-end encrypted chat message. Delivered if it is for us,
 *              otherwise forwarded (if relaying is on). Relays only see ciphertext.
 *  - ACK:      encrypted delivery receipt sent back to the sender.
 *
 * Routing: if the destination is a direct neighbour the packet goes straight to it.
 * Otherwise it is flooded to all neighbours (except where it came from) with a TTL
 * that drops by one per hop, and SeenPacketCache stops loops.
 *
 * Messages that cannot be sent yet (nobody in range, key unknown) stay in the database
 * with status SENDING and are retried automatically.
 *
 * Threading: every piece of router logic runs on ONE background thread, so the
 * internal maps need no locking. Public methods are safe to call from any thread.
 */
public class MeshRouter implements BluetoothConnectionManager.Listener {

    /** Events for the service. All of them are called on the router thread. */
    public interface Callbacks {
        void onReachablePeersChanged(@NonNull List<ReachablePeer> peers);

        void onLinkCountChanged(int count);

        void onLinkClosed(@NonNull String address);

        void onIncomingMessage(@NonNull String peerId, @NonNull String peerName,
                               @NonNull String text);
    }

    /** A phone we can currently reach, directly or through relays. */
    public static class ReachablePeer {
        public final String peerId;
        public final String name;
        /** 1 = directly connected, 2 or more = through relays. */
        public final int hops;

        ReachablePeer(String peerId, String name, int hops) {
            this.peerId = peerId;
            this.name = name;
            this.hops = hops;
        }

        public boolean isDirect() {
            return hops == 1;
        }
    }

    private static class Route {
        String name;
        int hops;
        String viaAddress;
        long lastSeen;

        Route(String name, int hops, String viaAddress, long lastSeen) {
            this.name = name;
            this.hops = hops;
            this.viaAddress = viaAddress;
            this.lastSeen = lastSeen;
        }
    }

    private static class IdentityInfo {
        final String id;
        final String name;
        final String key;

        IdentityInfo(String id, String name, String key) {
            this.id = id;
            this.name = name;
            this.key = key;
        }
    }

    private static final String TAG = "MeshRouter";

    public static final int MAX_TEXT_LENGTH = 2000;

    private static final long ANNOUNCE_INTERVAL_MS = 45_000L;
    private static final long TICK_INTERVAL_MS = 30_000L;
    private static final long ROUTE_EXPIRY_MS = 150_000L;
    private static final long HELLO_TIMEOUT_MS = 15_000L;
    private static final long PEER_PERSIST_INTERVAL_MS = 60_000L;

    /** Re-send an unacknowledged message every 3 minutes, at most 5 times. */
    private static final long RESEND_AFTER_MS = 3 * 60_000L;
    private static final int MAX_SEND_ATTEMPTS = 5;
    /** Give up (status FAILED) after 24 hours. */
    private static final long MESSAGE_EXPIRY_MS = 24L * 60 * 60 * 1000;

    private static final Pattern ID_PATTERN = Pattern.compile("[0-9a-f]{32}");

    private final IdentityManager identity;
    private final CryptoManager crypto;
    private final BluetoothConnectionManager manager;
    private final MessageDao messageDao;
    private final PeerDao peerDao;
    private final Callbacks callbacks;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final SeenPacketCache seen = new SeenPacketCache();

    // Router-thread state:
    private final Map<String, String> addressToPeer = new HashMap<>(); // established links
    private final Map<String, String> peerToAddress = new HashMap<>();
    private final Map<String, Route> routes = new HashMap<>();
    private final Map<String, String> peerNames = new HashMap<>();
    private final Map<String, Long> lastPersist = new HashMap<>();
    private final Map<String, Integer> sendAttempts = new HashMap<>();
    private final Map<String, Long> lastAttempt = new HashMap<>();

    public MeshRouter(@NonNull IdentityManager identity,
                      @NonNull CryptoManager crypto,
                      @NonNull BluetoothConnectionManager manager,
                      @NonNull AppDatabase database,
                      @NonNull Callbacks callbacks) {
        this.identity = identity;
        this.crypto = crypto;
        this.manager = manager;
        this.messageDao = database.messageDao();
        this.peerDao = database.peerDao();
        this.callbacks = callbacks;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void start() {
        try {
            executor.scheduleWithFixedDelay(() -> safe(() -> {
                pruneRoutes();
                retryPending();
            }), 10_000L, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);

            executor.scheduleWithFixedDelay(() -> safe(() -> {
                if (!addressToPeer.isEmpty()) sendOwnAnnounce();
            }), 20_000L, ANNOUNCE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Already shut down.
        }
        // Messages saved while the mesh was off are picked up straight away.
        post(this::retryPending);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Saves a new outgoing message and tries to send it. The message shows up in
     * the chat immediately (status SENDING) and is updated to SENT / DELIVERED.
     */
    public void sendTextMessage(@NonNull String destId, @NonNull String text) {
        post(() -> {
            String ownId = identity.getDeviceId();
            if (!ID_PATTERN.matcher(destId).matches() || destId.equals(ownId)) return;

            String clean = text.trim();
            if (clean.isEmpty()) return;
            if (clean.length() > MAX_TEXT_LENGTH) clean = clean.substring(0, MAX_TEXT_LENGTH);

            Message m = new Message();
            m.packetId = UUID.randomUUID().toString();
            m.chatId = destId;
            m.senderId = ownId;
            m.text = clean;
            m.timestamp = System.currentTimeMillis();
            m.status = Message.STATUS_SENDING;
            m.isRead = true;
            if (messageDao.insert(m) == -1) return;

            if (trySend(m)) {
                messageDao.updateStatus(m.packetId, Message.STATUS_SENT);
            }
        });
    }

    // ------------------------------------------------------------------
    // BluetoothConnectionManager.Listener (called on connection threads)
    // ------------------------------------------------------------------

    @Override
    public void onConnected(@NonNull String address, @NonNull String deviceName,
                            boolean incoming) {
        post(() -> {
            sendHello(address);
            // A link that never says hello is not one of ours: drop it.
            try {
                executor.schedule(() -> safe(() -> {
                    if (!addressToPeer.containsKey(address) && manager.isConnected(address)) {
                        manager.disconnect(address);
                    }
                }), HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Shutting down.
            }
        });
    }

    @Override
    public void onDisconnected(@NonNull String address) {
        post(() -> handleDisconnected(address));
    }

    @Override
    public void onFrameReceived(@NonNull String address, @NonNull byte[] frame) {
        post(() -> handleFrame(address, frame));
    }

    // ------------------------------------------------------------------
    // Receiving
    // ------------------------------------------------------------------

    private void handleFrame(String address, byte[] frame) {
        Packet p;
        try {
            p = Packet.fromBytes(frame);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Dropped malformed packet from " + address);
            return;
        }
        if (!isSane(p)) return;
        if (p.srcId.equals(identity.getDeviceId())) return; // our own packet came back

        if (p.type == Packet.TYPE_HELLO) {
            handleHello(address, p);
            return;
        }
        if (!addressToPeer.containsKey(address)) return; // no hello yet
        if (!seen.markIfNew(p.packetId)) return;         // already handled

        switch (p.type) {
            case Packet.TYPE_ANNOUNCE:
                handleAnnounce(address, p);
                break;
            case Packet.TYPE_MESSAGE:
                if (p.destId.equals(identity.getDeviceId())) {
                    deliverMessage(p);
                } else if (!p.destId.equals(Packet.BROADCAST)) {
                    forward(p, address);
                }
                break;
            case Packet.TYPE_ACK:
                if (p.destId.equals(identity.getDeviceId())) {
                    handleAck(p);
                } else if (!p.destId.equals(Packet.BROADCAST)) {
                    forward(p, address);
                }
                break;
            default:
                break;
        }
    }

    private boolean isSane(Packet p) {
        return p.ttl >= 1 && p.ttl <= Packet.MAX_TTL
                && p.packetId != null && !p.packetId.isEmpty() && p.packetId.length() <= 64
                && p.srcId != null && ID_PATTERN.matcher(p.srcId).matches()
                && p.destId != null
                && (p.destId.equals(Packet.BROADCAST) || ID_PATTERN.matcher(p.destId).matches())
                && p.payload != null;
    }

    private void handleHello(String address, Packet p) {
        IdentityInfo info = parseIdentity(p.payload);
        if (info == null || !info.id.equals(p.srcId)
                || !CryptoManager.isPeerKeyValid(info.id, info.key)) {
            Log.w(TAG, "Invalid hello from " + address + ", closing link");
            manager.disconnect(address);
            return;
        }
        String name = sanitizeName(info.name, info.id);

        String previousPeer = addressToPeer.put(address, info.id);
        String oldAddress = peerToAddress.put(info.id, address);
        if (oldAddress != null && !oldAddress.equals(address)) {
            // Two links to the same phone: keep the newest one.
            addressToPeer.remove(oldAddress);
            manager.disconnect(oldAddress);
        }

        persistPeer(info.id, name, info.key);
        updateRoute(info.id, name, 1, address);

        if (previousPeer == null) {
            callbacks.onLinkCountChanged(addressToPeer.size());
            onNeighborReady(address, info.id);
        }
    }

    private void handleAnnounce(String address, Packet p) {
        IdentityInfo info = parseIdentity(p.payload);
        if (info == null || !info.id.equals(p.srcId)
                || !CryptoManager.isPeerKeyValid(info.id, info.key)) {
            return;
        }
        String name = sanitizeName(info.name, info.id);
        boolean isNewOrChanged = persistPeer(info.id, name, info.key);
        updateRoute(info.id, name, hopsFor(p.ttl), address);
        if (isNewOrChanged) retryPending(); // we may have just learned a key we were waiting for
        forward(p, address);
    }

    private void deliverMessage(Packet p) {
        String ownId = identity.getDeviceId();
        String senderKey = peerDao.getPublicKey(p.srcId);
        if (senderKey == null) {
            Log.d(TAG, "Message from unknown sender, waiting for their announce");
            return; // the sender will retry later
        }

        String text;
        try {
            byte[] plain = crypto.decrypt(p.payload, senderKey, aad(p.srcId, ownId, p.packetId));
            text = new JSONObject(new String(plain, StandardCharsets.UTF_8)).getString("m");
        } catch (GeneralSecurityException | JSONException e) {
            Log.w(TAG, "Could not decrypt message: " + e.getMessage());
            return;
        }
        if (text.length() > MAX_TEXT_LENGTH) text = text.substring(0, MAX_TEXT_LENGTH);

        Message m = new Message();
        m.packetId = p.packetId;
        m.chatId = p.srcId;
        m.senderId = p.srcId;
        m.text = text;
        m.timestamp = System.currentTimeMillis();
        m.status = Message.STATUS_DELIVERED;
        m.isRead = false;
        boolean isNew = messageDao.insert(m) != -1;

        // Always acknowledge, even duplicates: the earlier ACK may have been lost.
        sendAck(p.srcId, p.packetId, senderKey);

        if (isNew) {
            Peer peer = peerDao.getPeer(p.srcId);
            String name = peer != null && peer.name != null && !peer.name.isEmpty()
                    ? peer.name : p.srcId.substring(0, 8);
            callbacks.onIncomingMessage(p.srcId, name, text);
        }
    }

    private void handleAck(Packet p) {
        String ownId = identity.getDeviceId();
        String senderKey = peerDao.getPublicKey(p.srcId);
        if (senderKey == null) return;
        try {
            byte[] plain = crypto.decrypt(p.payload, senderKey, aad(p.srcId, ownId, p.packetId));
            String ackedId = new String(plain, StandardCharsets.UTF_8);
            Message m = messageDao.findByPacketId(ackedId);
            // Only accept receipts for our own messages, from the person we sent them to.
            if (m != null && ownId.equals(m.senderId) && p.srcId.equals(m.chatId)) {
                messageDao.updateStatus(ackedId, Message.STATUS_DELIVERED);
                sendAttempts.remove(ackedId);
                lastAttempt.remove(ackedId);
            }
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "Invalid ACK ignored");
        }
    }

    private void handleDisconnected(String address) {
        String peerId = addressToPeer.remove(address);
        if (peerId != null) peerToAddress.remove(peerId, address);

        boolean changed = false;
        Iterator<Map.Entry<String, Route>> it = routes.entrySet().iterator();
        while (it.hasNext()) {
            if (address.equals(it.next().getValue().viaAddress)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) publishReachable();
        if (peerId != null) callbacks.onLinkCountChanged(addressToPeer.size());
        callbacks.onLinkClosed(address);
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private void sendHello(String address) {
        byte[] payload = buildIdentityPayload(identity.getDeviceId(), ownName(),
                identity.getPublicKeyBase64());
        Packet hello = new Packet(UUID.randomUUID().toString(), Packet.TYPE_HELLO,
                identity.getDeviceId(), Packet.BROADCAST, 1, payload);
        manager.sendFrame(address, hello.toBytes());
    }

    /** Called once a new neighbour has said hello. */
    private void onNeighborReady(String address, String peerId) {
        sendOwnAnnounce();
        if (identity.isRelayEnabled()) sendKnownPeersTo(address, peerId);
        retryPending();
    }

    /** Floods "this is me" to every neighbour. */
    private void sendOwnAnnounce() {
        String ownId = identity.getDeviceId();
        Packet a = Packet.create(Packet.TYPE_ANNOUNCE, ownId, Packet.BROADCAST,
                buildIdentityPayload(ownId, ownName(), identity.getPublicKeyBase64()));
        seen.markIfNew(a.packetId);
        routePacket(a);
    }

    /** Tells a new neighbour about the phones we can already reach. */
    private void sendKnownPeersTo(String address, String neighborId) {
        for (Map.Entry<String, Route> e : routes.entrySet()) {
            String peerId = e.getKey();
            Route r = e.getValue();
            if (peerId.equals(neighborId) || address.equals(r.viaAddress)) continue;
            if (r.hops >= Packet.DEFAULT_TTL) continue;
            String key = peerDao.getPublicKey(peerId);
            if (key == null) continue;

            // The neighbour computes hops = DEFAULT_TTL - ttl + 1, so this gives our hops + 1.
            Packet a = new Packet(UUID.randomUUID().toString(), Packet.TYPE_ANNOUNCE, peerId,
                    Packet.BROADCAST, Packet.DEFAULT_TTL - r.hops,
                    buildIdentityPayload(peerId, r.name, key));
            manager.sendFrame(address, a.toBytes());
        }
    }

    private void sendAck(String destId, String ackedPacketId, String destKey) {
        String ownId = identity.getDeviceId();
        String ackId = UUID.randomUUID().toString();
        try {
            byte[] enc = crypto.encrypt(ackedPacketId.getBytes(StandardCharsets.UTF_8),
                    destKey, aad(ownId, destId, ackId));
            Packet ack = new Packet(ackId, Packet.TYPE_ACK, ownId, destId,
                    Packet.DEFAULT_TTL, enc);
            seen.markIfNew(ackId);
            routePacket(ack);
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "Could not create ACK: " + e.getMessage());
        }
    }

    /**
     * Encrypts and sends one of our messages.
     *
     * @return true if it was handed to at least one neighbour.
     */
    private boolean trySend(Message m) {
        String ownId = identity.getDeviceId();
        String destKey = peerDao.getPublicKey(m.chatId);
        if (destKey == null) return false; // key unknown until their announce arrives

        try {
            byte[] plain = new JSONObject().put("m", m.text).toString()
                    .getBytes(StandardCharsets.UTF_8);
            byte[] enc = crypto.encrypt(plain, destKey, aad(ownId, m.chatId, m.packetId));
            Packet p = new Packet(m.packetId, Packet.TYPE_MESSAGE, ownId, m.chatId,
                    Packet.DEFAULT_TTL, enc);
            seen.markIfNew(p.packetId);
            if (routePacket(p) > 0) {
                Integer attempts = sendAttempts.get(m.packetId);
                sendAttempts.put(m.packetId, attempts == null ? 1 : attempts + 1);
                lastAttempt.put(m.packetId, System.currentTimeMillis());
                return true;
            }
        } catch (GeneralSecurityException | JSONException e) {
            Log.w(TAG, "Could not send message: " + e.getMessage());
        }
        return false;
    }

    /** Retries messages that were never sent, re-sends unacknowledged ones, expires old ones. */
    private void retryPending() {
        String ownId = identity.getDeviceId();
        long now = System.currentTimeMillis();

        for (Message m : messageDao.getOutgoingByStatus(ownId, Message.STATUS_SENDING)) {
            if (now - m.timestamp > MESSAGE_EXPIRY_MS) {
                messageDao.updateStatus(m.packetId, Message.STATUS_FAILED);
            } else if (trySend(m)) {
                messageDao.updateStatus(m.packetId, Message.STATUS_SENT);
            }
        }

        for (Message m : messageDao.getOutgoingByStatus(ownId, Message.STATUS_SENT)) {
            if (now - m.timestamp > MESSAGE_EXPIRY_MS) {
                messageDao.updateStatus(m.packetId, Message.STATUS_FAILED);
                continue;
            }
            Long last = lastAttempt.get(m.packetId);
            Integer attempts = sendAttempts.get(m.packetId);
            boolean due = last == null || now - last >= RESEND_AFTER_MS;
            if (due && (attempts == null || attempts < MAX_SEND_ATTEMPTS)) {
                trySend(m);
            }
        }
    }

    /** Passes on a packet that is not for us (if relaying is switched on). */
    private void forward(Packet p, String fromAddress) {
        if (!identity.isRelayEnabled() || p.ttl <= 1) return;
        String sourceAddress = peerToAddress.get(p.srcId);
        routePacket(p.withTtl(p.ttl - 1), fromAddress, sourceAddress);
    }

    /**
     * Direct neighbour -> send only to it. Otherwise flood to every neighbour except
     * the excluded addresses.
     *
     * @return number of neighbours the packet was queued for.
     */
    private int routePacket(Packet p, String... excluded) {
        byte[] bytes = p.toBytes();

        if (!p.destId.equals(Packet.BROADCAST)) {
            String direct = peerToAddress.get(p.destId);
            if (direct != null && manager.isConnected(direct)) {
                return manager.sendFrame(direct, bytes) ? 1 : 0;
            }
        }

        int count = 0;
        for (String address : addressToPeer.keySet()) {
            if (isExcluded(address, excluded)) continue;
            if (manager.sendFrame(address, bytes)) count++;
        }
        return count;
    }

    private static boolean isExcluded(String address, String[] excluded) {
        if (excluded == null) return false;
        for (String e : excluded) {
            if (address.equals(e)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Peers and routes
    // ------------------------------------------------------------------

    /** @return true if this peer was new this session or its name changed. */
    private boolean persistPeer(String id, String name, String key) {
        long now = System.currentTimeMillis();
        String cached = peerNames.get(id);
        if (cached == null || !cached.equals(name)) {
            Peer peer = new Peer();
            peer.peerId = id;
            peer.name = name;
            peer.publicKey = key;
            peer.lastSeen = now;
            peerDao.upsert(peer);
            peerNames.put(id, name);
            lastPersist.put(id, now);
            return true;
        }
        Long last = lastPersist.get(id);
        if (last == null || now - last >= PEER_PERSIST_INTERVAL_MS) {
            peerDao.updateLastSeen(id, now);
            lastPersist.put(id, now);
        }
        return false;
    }

    private void updateRoute(String peerId, String name, int hops, String via) {
        long now = System.currentTimeMillis();
        Route r = routes.get(peerId);
        boolean changed = false;

        if (r == null) {
            routes.put(peerId, new Route(name, hops, via, now));
            changed = true;
        } else if (hops < r.hops || via.equals(r.viaAddress)
                || now - r.lastSeen > ROUTE_EXPIRY_MS) {
            changed = hops != r.hops || !name.equals(r.name);
            r.name = name;
            r.hops = hops;
            r.viaAddress = via;
            r.lastSeen = now;
        } else if (hops == r.hops) {
            r.lastSeen = now; // a second path of the same length
        }
        if (changed) publishReachable();
    }

    /** Drops phones that stopped announcing (moved away) unless we are directly linked. */
    private void pruneRoutes() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, Route>> it = routes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Route> e = it.next();
            String direct = peerToAddress.get(e.getKey());
            boolean linked = direct != null && manager.isConnected(direct);
            if (!linked && now - e.getValue().lastSeen > ROUTE_EXPIRY_MS) {
                it.remove();
                changed = true;
            }
        }
        if (changed) publishReachable();
    }

    private void publishReachable() {
        List<ReachablePeer> list = new ArrayList<>();
        for (Map.Entry<String, Route> e : routes.entrySet()) {
            list.add(new ReachablePeer(e.getKey(), e.getValue().name, e.getValue().hops));
        }
        Collections.sort(list, (a, b) -> a.hops != b.hops
                ? Integer.compare(a.hops, b.hops)
                : a.name.compareToIgnoreCase(b.name));
        callbacks.onReachablePeersChanged(list);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** hops = 1 for a packet that came straight from its origin. */
    private static int hopsFor(int receivedTtl) {
        return Packet.DEFAULT_TTL - receivedTtl + 1;
    }

    private String ownName() {
        String name = identity.getDisplayName();
        return name.isEmpty() ? identity.getShortDeviceId() : name;
    }

    /** Ties an encrypted payload to its routing header so relays cannot re-address it. */
    private static byte[] aad(String srcId, String destId, String packetId) {
        return (srcId + ">" + destId + ">" + packetId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildIdentityPayload(String id, String name, String key) {
        try {
            return new JSONObject().put("id", id).put("name", name).put("key", key)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    private static IdentityInfo parseIdentity(byte[] payload) {
        try {
            JSONObject o = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            String id = o.optString("id", "");
            String key = o.optString("key", "");
            if (id.isEmpty() || key.isEmpty()) return null;
            return new IdentityInfo(id, o.optString("name", ""), key);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Names come from other phones: strip control characters and limit the length. */
    @NonNull
    private static String sanitizeName(@Nullable String raw, @NonNull String fallbackId) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").trim().replaceAll("\\s+", " ");
        if (s.length() > IdentityManager.MAX_NAME_LENGTH) {
            s = s.substring(0, IdentityManager.MAX_NAME_LENGTH).trim();
        }
        return s.isEmpty() ? fallbackId.substring(0, 8) : s;
    }

    private void post(Runnable r) {
        try {
            executor.execute(() -> safe(r));
        } catch (RejectedExecutionException ignored) {
            // Shut down.
        }
    }

    /** A scheduled task that throws is cancelled for good, so never let one escape. */
    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            Log.e(TAG, "Router task failed", e);
        }
    }
}