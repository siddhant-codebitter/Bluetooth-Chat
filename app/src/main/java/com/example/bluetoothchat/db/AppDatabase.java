package com.example.bluetoothchat.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.example.bluetoothchat.model.Message;
import com.example.bluetoothchat.model.Peer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's SQLite database (stored privately on the phone's internal storage).
 *
 * Get it with AppDatabase.getInstance(context). Use databaseExecutor for any
 * query that is not LiveData:
 *
 *   AppDatabase.databaseExecutor.execute(() -> db.messageDao().insert(message));
 */
@Database(entities = {Message.class, Peer.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DB_NAME = "bluetooth_chat.db";

    /** Shared background threads for database work. */
    public static final ExecutorService databaseExecutor = Executors.newFixedThreadPool(2);

    private static volatile AppDatabase instance;

    public abstract MessageDao messageDao();

    public abstract PeerDao peerDao();

    @NonNull
    public static AppDatabase getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DB_NAME)
                            // While developing: if you change an entity, the database is
                            // wiped instead of crashing. REMOVE this before releasing and
                            // write a proper Migration, or users lose their chats on update.
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}