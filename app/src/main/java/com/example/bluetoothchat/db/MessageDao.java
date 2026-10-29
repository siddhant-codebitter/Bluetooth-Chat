package com.example.bluetoothchat.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.bluetoothchat.model.Conversation;
import com.example.bluetoothchat.model.Message;

import java.util.List;

/**
 * Queries for chat messages.
 *
 * Methods that return LiveData can be called from the main thread (Room runs them
 * in the background). Every other method touches the database directly and must be
 * called from a background thread, e.g. AppDatabase.databaseExecutor.
 *
 * 'chatId' is always the device ID of the OTHER phone in the conversation.
 */
@Dao
public interface MessageDao {

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    /**
     * Inserts a message. If a message with the same packetId already exists (the same
     * packet arrived twice through different relays) nothing is inserted and -1 is returned.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(Message message);

    /** Sets the delivery status (SENDING / SENT / DELIVERED / FAILED) of one message. */
    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId")
    int updateStatus(String packetId, int status);

    /** Marks every incoming message of a chat as read. Call when the chat screen opens. */
    @Query("UPDATE messages SET isRead = 1 WHERE chatId = :chatId AND isRead = 0")
    int markChatRead(String chatId);

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    int deleteChat(String chatId);

    @Query("DELETE FROM messages")
    int deleteAll();

    // ------------------------------------------------------------------
    // Read (observable, for the UI)
    // ------------------------------------------------------------------

    /** All messages of one chat, oldest first. Updates automatically. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC")
    LiveData<List<Message>> observeMessages(String chatId);

    /**
     * One row per chat for the chat list on the main screen, newest chat first.
     * The name comes from the peers table (falls back to the device ID if unknown).
     */
    @Query("SELECT m.chatId AS peerId, "
            + "COALESCE(p.name, m.chatId) AS name, "
            + "m.text AS lastMessage, "
            + "m.timestamp AS lastTimestamp, "
            + "(SELECT COUNT(*) FROM messages u "
            + "   WHERE u.chatId = m.chatId AND u.isRead = 0) AS unreadCount "
            + "FROM messages m "
            + "LEFT JOIN peers p ON p.peerId = m.chatId "
            + "WHERE m.id = (SELECT id FROM messages "
            + "              WHERE chatId = m.chatId "
            + "              ORDER BY timestamp DESC, id DESC LIMIT 1) "
            + "ORDER BY m.timestamp DESC")
    LiveData<List<Conversation>> observeConversations();

    // ------------------------------------------------------------------
    // Read (direct, background thread only)
    // ------------------------------------------------------------------

    @Query("SELECT * FROM messages WHERE packetId = :packetId LIMIT 1")
    Message findByPacketId(String packetId);

    /**
     * Messages WE sent that are still in a given state. The router uses this to
     * retry messages that could not be sent yet (store-and-forward).
     */
    @Query("SELECT * FROM messages WHERE senderId = :ownId AND status = :status "
            + "ORDER BY timestamp ASC, id ASC")
    List<Message> getOutgoingByStatus(String ownId, int status);
}