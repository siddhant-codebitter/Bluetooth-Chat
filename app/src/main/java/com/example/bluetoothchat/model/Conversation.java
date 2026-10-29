package com.example.bluetoothchat.model;

/**
 * One row of the chat list. Not a table: it is filled by the query
 * MessageDao.observeConversations(), so the field names must match the column
 * names in that query.
 */
public class Conversation {

    /** Device ID of the other phone. */
    public String peerId;

    /** Peer's display name (or the device ID if we do not know the name). */
    public String name;

    /** Text of the newest message in this chat. */
    public String lastMessage;

    /** Time of the newest message, milliseconds since 1970. */
    public long lastTimestamp;

    /** Number of incoming messages not read yet. */
    public int unreadCount;
}