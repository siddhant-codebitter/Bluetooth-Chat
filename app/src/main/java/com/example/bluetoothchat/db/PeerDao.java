package com.example.bluetoothchat.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.bluetoothchat.model.Peer;

import java.util.List;

/**
 * Queries for phones we have met (their name and public key).
 *
 * LiveData methods can be called from the main thread. All other methods must be
 * called from a background thread, e.g. AppDatabase.databaseExecutor.
 */
@Dao
public interface PeerDao {

    /** Adds a peer, or replaces the stored one with the same peerId. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(Peer peer);

    @Query("UPDATE peers SET lastSeen = :time WHERE peerId = :peerId")
    int updateLastSeen(String peerId, long time);

    @Query("DELETE FROM peers")
    int deleteAll();

    // Observable

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    LiveData<Peer> observePeer(String peerId);

    @Query("SELECT * FROM peers ORDER BY name COLLATE NOCASE ASC")
    LiveData<List<Peer>> observeAllPeers();

    // Direct (background thread only)

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    Peer getPeer(String peerId);

    /** The stored public key (Base64) of a peer, or null if we do not know them yet. */
    @Query("SELECT publicKey FROM peers WHERE peerId = :peerId LIMIT 1")
    String getPublicKey(String peerId);

    @Query("SELECT * FROM peers")
    List<Peer> getAllPeers();
}