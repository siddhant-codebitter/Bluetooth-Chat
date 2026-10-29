package com.example.bluetoothchat.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Owns every Bluetooth Classic (RFCOMM) connection of the app.
 *
 * - Listens for incoming connections (AcceptThread).
 * - Opens outgoing connections on request (connectBlocking).
 * - Reads and writes length-prefixed binary frames on each connection.
 *
 * This class knows nothing about chat packets. It only moves byte[] frames and
 * reports events through {@link Listener}. Listener methods are called from
 * background threads, never from the main thread.
 */
public class BluetoothConnectionManager {

    public interface Listener {
        /** A new link is up. 'incoming' is true if the other phone connected to us. */
        void onConnected(@NonNull String address, @NonNull String deviceName, boolean incoming);

        /** A link went down (out of range, closed, Bluetooth off...). */
        void onDisconnected(@NonNull String address);

        /** One complete frame arrived from the phone with this Bluetooth address. */
        void onFrameReceived(@NonNull String address, @NonNull byte[] frame);
    }

    private static final String TAG = "BtConnManager";

    /** Shared by every phone running the app. Do not change it after release. */
    public static final UUID APP_UUID =
            UUID.fromString("6f1d4e0a-3b7c-4c2e-9a58-d1e0f7b2c9a3");
    private static final String SERVICE_NAME = "BluetoothChatMesh";

    /** Android supports roughly 7 simultaneous Bluetooth links; keep one spare. */
    public static final int MAX_CONNECTIONS = 6;

    /** Frames larger than this are treated as corrupt and the link is dropped. */
    private static final int MAX_FRAME_SIZE = 64 * 1024;

    private final Context appContext;
    @Nullable
    private final BluetoothAdapter adapter;

    private final Map<String, ConnectionThread> connections = new ConcurrentHashMap<>();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private final Object registerLock = new Object();

    private volatile Listener listener;
    private volatile boolean running;
    private volatile ExecutorService writeExecutor;
    private AcceptThread acceptThread;

    public BluetoothConnectionManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        BluetoothManager bm = appContext.getSystemService(BluetoothManager.class);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Nullable
    public BluetoothAdapter getAdapter() {
        return adapter;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Starts listening for incoming connections. Safe to call more than once. */
    public synchronized void start() {
        if (running || adapter == null) return;
        running = true;
        writeExecutor = Executors.newSingleThreadExecutor();
        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    /** Closes every connection and stops listening. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        for (ConnectionThread t : new ArrayList<>(connections.values())) {
            t.close();
        }
        ExecutorService ex = writeExecutor;
        writeExecutor = null;
        if (ex != null) ex.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public boolean isConnected(@NonNull String address) {
        return connections.containsKey(address);
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public boolean hasCapacity() {
        return connections.size() < MAX_CONNECTIONS;
    }

    @NonNull
    public List<String> getConnectedAddresses() {
        return new ArrayList<>(connections.keySet());
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    /**
     * Opens an outgoing connection and waits for the result (can take several
     * seconds if the phone is out of range). Never call this on the main thread.
     *
     * @return true if a link to this device exists when the call returns.
     */
    @SuppressLint("MissingPermission")
    public boolean connectBlocking(@NonNull BluetoothDevice device) {
        if (!running || adapter == null || !adapter.isEnabled() || !hasConnectPermission()) {
            return false;
        }
        String address = device.getAddress();
        if (connections.containsKey(address)) return true;
        if (connections.size() >= MAX_CONNECTIONS) return false;
        if (!connecting.add(address)) return false; // another attempt already running

        BluetoothSocket socket = null;
        try {
            // Discovery slows down (and can break) connection attempts.
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
                // No scan permission: nothing to cancel.
            }
            // Insecure RFCOMM: no pairing dialog is shown.
            socket = device.createInsecureRfcommSocketToServiceRecord(APP_UUID);
            socket.connect();
            registerConnection(socket, false);
            // If we lost a simultaneous-connect race the other link is already registered.
            return connections.containsKey(address);
        } catch (IOException | SecurityException e) {
            Log.d(TAG, "Connect to " + address + " failed: " + e.getMessage());
            closeQuietly(socket);
            return false;
        } finally {
            connecting.remove(address);
        }
    }

    public void disconnect(@NonNull String address) {
        ConnectionThread t = connections.get(address);
        if (t != null) t.close();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Queues one frame for sending. Returns false if there is no link to that
     * address or the frame is invalid. Safe to call from any thread.
     */
    public boolean sendFrame(@NonNull String address, @NonNull byte[] data) {
        if (data.length == 0 || data.length > MAX_FRAME_SIZE) return false;
        final ConnectionThread target = connections.get(address);
        ExecutorService ex = writeExecutor;
        if (target == null || ex == null) return false;
        try {
            ex.execute(() -> {
                try {
                    target.write(data);
                } catch (IOException e) {
                    Log.d(TAG, "Write to " + address + " failed: " + e.getMessage());
                    target.close();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Sends a frame to every connected phone except 'exceptAddress' (pass null to
     * send to all). Used for flooding and relaying.
     *
     * @return number of links the frame was queued for.
     */
    public int broadcastFrame(@NonNull byte[] data, @Nullable String exceptAddress) {
        int count = 0;
        for (String address : connections.keySet()) {
            if (address.equals(exceptAddress)) continue;
            if (sendFrame(address, data)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Adds a freshly opened socket to the pool and starts its reader thread.
     * Duplicate links to the same phone are closed. If both phones connect to
     * each other at the same moment, both links may be closed; the scanner
     * simply retries on its next cycle.
     */
    private boolean registerConnection(@NonNull BluetoothSocket socket, boolean incoming) {
        BluetoothDevice device = socket.getRemoteDevice();
        String address = device.getAddress();
        ConnectionThread thread;
        synchronized (registerLock) {
            if (!running
                    || connections.containsKey(address)
                    || connections.size() >= MAX_CONNECTIONS) {
                closeQuietly(socket);
                return false;
            }
            try {
                thread = new ConnectionThread(socket, address, nameOf(device), incoming);
            } catch (IOException e) {
                closeQuietly(socket);
                return false;
            }
            connections.put(address, thread);
        }
        thread.start();
        return true;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(appContext,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    @NonNull
    private String nameOf(@NonNull BluetoothDevice device) {
        try {
            String name = hasConnectPermission() ? device.getName() : null;
            return (name != null && !name.isEmpty()) ? name : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    private void fireConnected(String address, String name, boolean incoming) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onConnected(address, name, incoming);
        } catch (RuntimeException e) {
            Log.e(TAG, "Listener.onConnected crashed", e);
        }
    }

    private void fireDisconnected(String address) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onDisconnected(address);
        } catch (RuntimeException e) {
            Log.e(TAG, "Listener.onDisconnected crashed", e);
        }
    }

    private void fireFrame(String address, byte[] frame) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onFrameReceived(address, frame);
        } catch (RuntimeException e) {
            Log.e(TAG, "Listener.onFrameReceived crashed", e);
        }
    }

    private static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }

    /** @return false if the thread was interrupted while sleeping. */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Threads
    // ------------------------------------------------------------------

    /** Waits for other phones to connect to us. */
    private class AcceptThread extends Thread {
        private volatile BluetoothServerSocket serverSocket;
        private volatile boolean cancelled;

        AcceptThread() {
            super("bt-accept");
            setDaemon(true);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            while (running && !cancelled) {
                BluetoothServerSocket ss = null;
                try {
                    if (adapter == null || !adapter.isEnabled() || !hasConnectPermission()) {
                        sleepQuietly(3000);
                        continue;
                    }
                    ss = adapter.listenUsingInsecureRfcommWithServiceRecord(
                            SERVICE_NAME, APP_UUID);
                    serverSocket = ss;
                    while (running && !cancelled) {
                        BluetoothSocket client = ss.accept(); // blocks
                        registerConnection(client, true);
                    }
                } catch (IOException | SecurityException e) {
                    if (running && !cancelled) {
                        Log.w(TAG, "Accept loop restarting: " + e.getMessage());
                        sleepQuietly(2000);
                    }
                } finally {
                    closeQuietly(ss);
                    serverSocket = null;
                }
            }
        }

        void cancel() {
            cancelled = true;
            closeQuietly(serverSocket);
            interrupt();
        }
    }

    /** Reads frames from one connected phone and writes frames to it. */
    private class ConnectionThread extends Thread {
        private final BluetoothSocket socket;
        private final String address;
        private final String deviceName;
        private final boolean incoming;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final Object writeLock = new Object();

        ConnectionThread(BluetoothSocket socket, String address, String deviceName,
                         boolean incoming) throws IOException {
            super("bt-conn-" + address);
            setDaemon(true);
            this.socket = socket;
            this.address = address;
            this.deviceName = deviceName;
            this.incoming = incoming;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        @Override
        public void run() {
            fireConnected(address, deviceName, incoming);
            try {
                while (running) {
                    int length = in.readInt(); // blocks until a frame starts
                    if (length <= 0 || length > MAX_FRAME_SIZE) {
                        throw new IOException("Invalid frame length: " + length);
                    }
                    byte[] frame = new byte[length];
                    in.readFully(frame);
                    fireFrame(address, frame);
                }
            } catch (IOException e) {
                Log.d(TAG, "Link to " + address + " closed: " + e.getMessage());
            } finally {
                close();
                // Only report once, and only for the link that is actually registered.
                if (connections.remove(address, this)) {
                    fireDisconnected(address);
                }
            }
        }

        void write(byte[] data) throws IOException {
            synchronized (writeLock) {
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            }
        }

        void close() {
            closeQuietly(socket);
        }
    }
}