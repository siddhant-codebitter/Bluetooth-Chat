package com.example.bluetoothchat.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Another phone we have met. Saved so we can still open its chat (and encrypt
 * messages to it) when it is out of range.
 */
@Entity(tableName = "peers")
public class Peer {

    /** The other phone's device ID (derived from its public key). */
    @PrimaryKey
    @NonNull
    public String peerId = "";

    /** Display name the other person chose. */
    @NonNull
    public String name = "";

    /** Public key, Base64 (X.509 encoding). Used to encrypt messages to this phone. */
    @NonNull
    public String publicKey = "";

    /** Last time we heard from this phone, milliseconds since 1970. */
    public long lastSeen;
}