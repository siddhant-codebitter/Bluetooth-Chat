package com.example.bluetoothchat.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One chat message, stored in the phone's database.
 *
 * Both sent and received messages live in the same table. 'chatId' is the device ID of
 * the OTHER phone in the conversation; 'senderId' tells who wrote the message.
 *
 * packetId is unique: if the same message arrives twice (through different relays) the
 * second insert is ignored.
 */
@Entity(
        tableName = "messages",
        indices = {
                @Index(value = "packetId", unique = true),
                @Index(value = "chatId")
        }
)
public class Message {

    /** Waiting to be sent (nobody in range, or the recipient's key is not known yet). */
    public static final int STATUS_SENDING = 0;
    /** Handed to at least one nearby phone, no receipt yet. */
    public static final int STATUS_SENT = 1;
    /** The recipient confirmed that it arrived. Also used for every received message. */
    public static final int STATUS_DELIVERED = 2;
    /** Gave up after 24 hours. */
    public static final int STATUS_FAILED = 3;

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Unique ID of the message, also used as the network packet ID. */
    @NonNull
    public String packetId = "";

    /** Device ID of the other person in this chat. */
    @NonNull
    public String chatId = "";

    /** Device ID of whoever wrote the message (us or the other person). */
    @NonNull
    public String senderId = "";

    @NonNull
    public String text = "";

    /** Milliseconds since 1970, taken on this phone. */
    public long timestamp;

    /** One of the STATUS_ constants. Only meaningful for messages we sent. */
    public int status;

    /** False for incoming messages the user has not opened yet. Sent messages are always true. */
    public boolean isRead;

    /** True if this message was written by the phone with the given device ID. */
    public boolean isSentBy(@NonNull String ownId) {
        return senderId.equals(ownId);
    }
}