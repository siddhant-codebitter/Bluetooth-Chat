package com.example.bluetoothchat.mesh;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.bluetoothchat.R;
import com.example.bluetoothchat.bluetooth.BluetoothConnectionManager;
import com.example.bluetoothchat.bluetooth.DeviceScanner;
import com.example.bluetoothchat.crypto.CryptoManager;
import com.example.bluetoothchat.crypto.IdentityManager;
import com.example.bluetoothchat.db.AppDatabase;
import com.example.bluetoothchat.ui.ChatActivity;
import com.example.bluetoothchat.ui.MainActivity;

import java.util.Collections;
import java.util.List;

/**
 * Foreground service that keeps the mesh alive, even when the app is closed.
 * It creates and owns the connection manager, scanner and router.
 *
 * How the UI talks to it (no binding needed):
 *
 *   Start / stop:      MeshService.start(context)  /  MeshService.stop(context)
 *   Send a message:    MeshService.sendMessage(context, peerId, text)
 *   Scan right now:    MeshService.scanNow(context)
 *   Tell it which chat is open (so it does not notify for it):
 *                      MeshService.setActiveChat(peerId)   // null when none
 *
 *   Observe state:     MeshService.isRunning(), getReachablePeers(),
 *                      getConnectionCount(), isScanning()   (all LiveData)
 *
 * start() does not change the "mesh on" preference. Save it yourself with
 * IdentityManager.setMeshEnabled(...) when the user flips the switch.
 */
public class MeshService extends Service implements MeshRouter.Callbacks {

    private static final String TAG = "MeshService";

    private static final String ACTION_STOP = "com.example.bluetoothchat.action.STOP";
    private static final String ACTION_SEND_MESSAGE = "com.example.bluetoothchat.action.SEND_MESSAGE";
    private static final String ACTION_SCAN_NOW = "com.example.bluetoothchat.action.SCAN_NOW";
    private static final String EXTRA_DEST_ID = "dest_id";
    private static final String EXTRA_TEXT = "text";

    private static final String CHANNEL_SERVICE = "mesh_service";
    private static final String CHANNEL_MESSAGES = "messages";
    private static final int NOTIFICATION_ID_SERVICE = 1;

    // State for the UI. Static, so screens can observe it without binding to the service.
    private static final MutableLiveData<Boolean> runningLive = new MutableLiveData<>(false);
    private static final MutableLiveData<List<MeshRouter.ReachablePeer>> reachableLive =
            new MutableLiveData<>(Collections.<MeshRouter.ReachablePeer>emptyList());
    private static final MutableLiveData<Integer> connectionCountLive = new MutableLiveData<>(0);
    private static final MutableLiveData<Boolean> scanningLive = new MutableLiveData<>(false);
    private static volatile String activeChatId;

    private IdentityManager identity;
    private BluetoothConnectionManager connectionManager;
    private DeviceScanner scanner;
    private MeshRouter router;
    private boolean initialised;

    // ------------------------------------------------------------------
    // Static helpers for the UI
    // ------------------------------------------------------------------

    public static void start(@NonNull Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, MeshService.class));
    }

    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, MeshService.class));
    }

    /** Saves the message in the chat and sends it (starts the service if needed). */
    public static void sendMessage(@NonNull Context context, @NonNull String peerId,
                                   @NonNull String text) {
        Intent intent = new Intent(context, MeshService.class)
                .setAction(ACTION_SEND_MESSAGE)
                .putExtra(EXTRA_DEST_ID, peerId)
                .putExtra(EXTRA_TEXT, text);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Starts a device scan immediately (only if the service is running). */
    public static void scanNow(@NonNull Context context) {
        if (Boolean.TRUE.equals(runningLive.getValue())) {
            context.startService(new Intent(context, MeshService.class)
                    .setAction(ACTION_SCAN_NOW));
        }
    }

    public static void setActiveChat(@Nullable String peerId) {
        activeChatId = peerId;
    }

    @NonNull
    public static LiveData<Boolean> isRunning() {
        return runningLive;
    }

    /** Phones we can reach now, direct (hops = 1) or through relays (hops >= 2). */
    @NonNull
    public static LiveData<List<MeshRouter.ReachablePeer>> getReachablePeers() {
        return reachableLive;
    }

    /** Number of phones directly connected to this one. */
    @NonNull
    public static LiveData<Integer> getConnectionCount() {
        return connectionCountLive;
    }

    @NonNull
    public static LiveData<Boolean> isScanning() {
        return scanningLive;
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();

        // Must happen quickly after startForegroundService(), before anything slow.
        if (!enterForeground() || !hasBluetoothPermissions()) {
            Log.w(TAG, "Cannot run: missing permission");
            stopSelf();
            return;
        }

        identity = new IdentityManager(this);
        CryptoManager crypto = new CryptoManager(identity);
        AppDatabase database = AppDatabase.getInstance(this);

        connectionManager = new BluetoothConnectionManager(this);
        router = new MeshRouter(identity, crypto, connectionManager, database, this);
        connectionManager.setListener(router);

        scanner = new DeviceScanner(this, connectionManager);
        scanner.setCallback(scanningLive::postValue);

        connectionManager.start();
        router.start();
        scanner.start();

        initialised = true;
        runningLive.postValue(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!initialised) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            identity.setMeshEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SEND_MESSAGE.equals(action)) {
            String dest = intent.getStringExtra(EXTRA_DEST_ID);
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (dest != null && text != null) router.sendTextMessage(dest, text);
        } else if (ACTION_SCAN_NOW.equals(action)) {
            scanner.scanNow();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (scanner != null) scanner.stop();
        if (connectionManager != null) connectionManager.stop();
        if (router != null) router.shutdown();

        runningLive.postValue(false);
        reachableLive.postValue(Collections.<MeshRouter.ReachablePeer>emptyList());
        connectionCountLive.postValue(0);
        scanningLive.postValue(false);

        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // MeshRouter.Callbacks (router thread)
    // ------------------------------------------------------------------

    @Override
    public void onReachablePeersChanged(@NonNull List<MeshRouter.ReachablePeer> peers) {
        reachableLive.postValue(peers);
    }

    @Override
    public void onLinkCountChanged(int count) {
        connectionCountLive.postValue(count);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID_SERVICE, buildServiceNotification(count));
    }

    @Override
    public void onLinkClosed(@NonNull String address) {
        // The phone may only have stepped out of range briefly: reconnect soon.
        if (scanner != null) scanner.resetBackoff(address);
    }

    @Override
    public void onIncomingMessage(@NonNull String peerId, @NonNull String peerName,
                                  @NonNull String text) {
        if (peerId.equals(activeChatId)) return; // the user is looking at this chat
        showMessageNotification(peerId, peerName, text);
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_SERVICE,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_MESSAGES,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH));
    }

    private boolean enterForeground() {
        Notification notification = buildServiceNotification(0);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID_SERVICE, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification);
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground failed", e);
            return false;
        }
    }

    @NonNull
    private Notification buildServiceNotification(int linkCount) {
        String text = linkCount == 0
                ? getString(R.string.peer_count_none)
                : getResources().getQuantityString(R.plurals.peer_count, linkCount, linkCount);

        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, MeshService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.action_stop_mesh), stopIntent)
                .build();
    }

    private void showMessageNotification(String peerId, String peerName, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent open = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_PEER_ID, peerId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, peerName)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, peerId.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Shown on a locked screen: no message text.
        Notification publicVersion = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setContentTitle(peerName)
                .setContentText(getString(R.string.notification_new_message))
                .build();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setContentTitle(peerName)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build();

        nm.notify(peerId.hashCode(), notification);
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }
}