package com.example.bluetoothchat.model;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * What actually travels between phones. Immutable.
 *
 * Wire format (one packet = one frame handed to BluetoothConnectionManager):
 *
 *   [1 byte  version]
 *   [1 byte  type]
 *   [1 byte  ttl]
 *   [UTF     packetId]
 *   [UTF     srcId]
 *   [UTF     destId]
 *   [4 bytes payload length][payload bytes]
 *
 * The routing fields (type, ttl, ids) are readable by relay phones. The payload of
 * MESSAGE and ACK packets is encrypted end to end.
 */
public final class Packet {

    /** First packet on a new link: "this is who I am". Never forwarded (ttl 1). */
    public static final int TYPE_HELLO = 1;
    /** "I exist, here is my name and public key". Flooded through the mesh. */
    public static final int TYPE_ANNOUNCE = 2;
    /** An encrypted chat message. */
    public static final int TYPE_MESSAGE = 3;
    /** An encrypted delivery receipt. */
    public static final int TYPE_ACK = 4;

    /** Destination used by packets that are for everyone (HELLO, ANNOUNCE). */
    public static final String BROADCAST = "*";

    /** Hops a new packet may travel. */
    public static final int DEFAULT_TTL = 5;
    /** Largest TTL accepted from the network. */
    public static final int MAX_TTL = 8;

    private static final int WIRE_VERSION = 1;
    private static final int MAX_PAYLOAD_SIZE = 60 * 1024;

    public final String packetId;
    public final int type;
    public final String srcId;
    public final String destId;
    /** Hops this packet may still travel, including the one it is about to make. */
    public final int ttl;
    public final byte[] payload;

    public Packet(@NonNull String packetId, int type, @NonNull String srcId,
                  @NonNull String destId, int ttl, @NonNull byte[] payload) {
        this.packetId = packetId;
        this.type = type;
        this.srcId = srcId;
        this.destId = destId;
        this.ttl = ttl;
        this.payload = payload;
    }

    /** New packet with a random ID and the default TTL. */
    @NonNull
    public static Packet create(int type, @NonNull String srcId, @NonNull String destId,
                                @NonNull byte[] payload) {
        return new Packet(UUID.randomUUID().toString(), type, srcId, destId,
                DEFAULT_TTL, payload);
    }

    /** Copy of this packet with a different TTL (used when forwarding). */
    @NonNull
    public Packet withTtl(int newTtl) {
        return new Packet(packetId, type, srcId, destId, newTtl, payload);
    }

    @NonNull
    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 128);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(WIRE_VERSION);
            out.writeByte(type);
            out.writeByte(ttl);
            out.writeUTF(packetId);
            out.writeUTF(srcId);
            out.writeUTF(destId);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            // Cannot happen when writing to memory.
            throw new IllegalStateException("Could not encode packet", e);
        }
    }

    /** @throws IOException if the data is not a valid packet */
    @NonNull
    public static Packet fromBytes(@NonNull byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

        int version = in.readUnsignedByte();
        if (version != WIRE_VERSION) {
            throw new IOException("Unsupported packet version " + version);
        }
        int type = in.readUnsignedByte();
        int ttl = in.readUnsignedByte();
        String packetId = in.readUTF();
        String srcId = in.readUTF();
        String destId = in.readUTF();

        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD_SIZE || length != in.available()) {
            throw new IOException("Bad payload length " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);

        return new Packet(packetId, type, srcId, destId, ttl, payload);
    }
}