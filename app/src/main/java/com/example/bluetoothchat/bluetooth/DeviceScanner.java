package com.example.bluetoothchat.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Finds nearby phones and asks {@link BluetoothConnectionManager} to connect.
 *
 * Every cycle:
 *   1. Try paired phones and phones that connected before (no discovery needed).
 *   2. Run a Bluetooth discovery (about 12 seconds).
 *   3. Try every phone found.
 *
 * Phones that fail to connect (not running the app, out of range) are retried
 * later with a growing delay, so the scanner does not waste battery on them.
 *
 * IMPORTANT: classic Bluetooth discovery only finds phones that are currently
 * DISCOVERABLE. A phone that is paired with this one is reachable even when it
 * is not discoverable.
 *
 * Threading: start(), stop() and scanNow() must be called from the main thread.
 */
public class DeviceScanner {

    public interface Callback {
        /** Called on the main thread when discovery starts or stops. */
        void onScanStateChanged(boolean scanning);
    }

    private static final String TAG = "DeviceScanner";
    private static final String PREFS = "device_scanner";
    private static final String KEY_KNOWN = "known_app_devices";

    /** Scan more often while we have no links, less often once connected. */
    private static final long INTERVAL_NO_LINKS_MS = 20_000L;
    private static final long INTERVAL_WITH_LINKS_MS = 60_000L;

    /** Safety net in case the "discovery finished" broadcast never arrives. */
    private static final long DISCOVERY_TIMEOUT_MS = 20_000L;

    /** Random delay before each connect so two phones rarely connect at the same instant. */
    private static final int MAX_JITTER_MS = 1_500;

    private final Context appContext;
    @Nullable
    private final BluetoothAdapter adapter;
    private final BluetoothConnectionManager connectionManager;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private final Map<String, BluetoothDevice> foundDevices = new ConcurrentHashMap<>();
    private final Map<String, Long> nextAttemptAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> failureCount = new ConcurrentHashMap<>();
    private final Set<String> knownAppDevices = ConcurrentHashMap.newKeySet();

    private ExecutorService connectExecutor;
    private volatile Callback callback;

    private volatile boolean running;
    private boolean cycleInProgress;
    private boolean discoveryActive;
    private boolean receiverRegistered;
    private boolean scanning;

    private final Runnable cycleRunnable = this::beginCycle;
    private final Runnable discoveryTimeoutRunnable = () -> {
        cancelDiscoverySafely();
        onDiscoveryFinished();
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = extractDevice(intent);
                if (device != null) {
                    foundDevices.put(device.getAddress(), device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                onDiscoveryFinished();
            }
        }
    };

    public DeviceScanner(@NonNull Context context,
                         @NonNull BluetoothConnectionManager connectionManager) {
        this.appContext = context.getApplicationContext();
        this.connectionManager = connectionManager;
        BluetoothManager bm = appContext.getSystemService(BluetoothManager.class);
        this.adapter = bm != null ? bm.getAdapter() : null;
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Copy: the set returned by SharedPreferences must not be modified.
        knownAppDevices.addAll(new HashSet<>(
                prefs.getStringSet(KEY_KNOWN, Collections.<String>emptySet())));
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    public boolean isScanning() {
        return scanning;
    }

    // ------------------------------------------------------------------
    // Public control
    // ------------------------------------------------------------------

    public void start() {
        if (running || adapter == null) return;
        running = true;
        connectExecutor = Executors.newSingleThreadExecutor();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        // These are protected system broadcasts sent by the Bluetooth app, so the
        // receiver has to be registered as exported to receive them.
        ContextCompat.registerReceiver(appContext, receiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;

        cycleInProgress = false;
        handler.post(cycleRunnable);
    }

    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }
        cancelDiscoverySafely();
        discoveryActive = false;
        cycleInProgress = false;
        if (connectExecutor != null) {
            connectExecutor.shutdownNow();
            connectExecutor = null;
        }
        setScanning(false);
    }

    /** Starts a scan cycle right away (ignored if one is already running). */
    public void scanNow() {
        if (!running || cycleInProgress) return;
        handler.removeCallbacks(cycleRunnable);
        beginCycle();
    }

    /**
     * Forget past failures for this phone so the next cycle tries it immediately.
     * Call it when a link drops (the phone may only have moved briefly out of range).
     */
    public void resetBackoff(@NonNull String address) {
        failureCount.remove(address);
        nextAttemptAt.remove(address);
    }

    // ------------------------------------------------------------------
    // Scan cycle (main thread unless noted)
    // ------------------------------------------------------------------

    private void beginCycle() {
        if (!running) return;
        cycleInProgress = true;

        if (adapter == null || !adapter.isEnabled() || !hasConnectPermission()) {
            scheduleNextCycle();
            return;
        }
        try {
            connectExecutor.execute(() -> {
                // Step 1 (background): paired and previously known phones.
                connectToCandidates(bondedCandidates());
                handler.post(this::startDiscoveryPhase);
            });
        } catch (RejectedExecutionException e) {
            scheduleNextCycle();
        }
    }

    @SuppressLint("MissingPermission")
    private void startDiscoveryPhase() {
        if (!running) return;
        if (adapter == null
                || !connectionManager.hasCapacity()
                || !hasScanPermission()) {
            scheduleNextCycle();
            return;
        }

        foundDevices.clear();
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            discoveryActive = adapter.startDiscovery();
        } catch (SecurityException e) {
            discoveryActive = false;
        }

        if (!discoveryActive) {
            scheduleNextCycle();
            return;
        }
        setScanning(true);
        handler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_TIMEOUT_MS);
    }

    private void onDiscoveryFinished() {
        if (!discoveryActive) return; // duplicate or unrelated broadcast
        discoveryActive = false;
        handler.removeCallbacks(discoveryTimeoutRunnable);
        setScanning(false);
        if (!running) return;

        final List<BluetoothDevice> found = new ArrayList<>(foundDevices.values());
        try {
            connectExecutor.execute(() -> {
                // Step 3 (background): phones found by discovery.
                connectToCandidates(found);
                handler.post(this::scheduleNextCycle);
            });
        } catch (RejectedExecutionException e) {
            scheduleNextCycle();
        }
    }

    private void scheduleNextCycle() {
        if (!running) return;
        cycleInProgress = false;
        long delay = connectionManager.getConnectionCount() == 0
                ? INTERVAL_NO_LINKS_MS : INTERVAL_WITH_LINKS_MS;
        handler.removeCallbacks(cycleRunnable);
        handler.postDelayed(cycleRunnable, delay);
    }

    // ------------------------------------------------------------------
    // Connecting (background thread)
    // ------------------------------------------------------------------

    private void connectToCandidates(@NonNull List<BluetoothDevice> devices) {
        List<BluetoothDevice> sorted = new ArrayList<>(devices);
        // Phones that worked before go first.
        Collections.sort(sorted, (a, b) -> Boolean.compare(
                knownAppDevices.contains(b.getAddress()),
                knownAppDevices.contains(a.getAddress())));

        for (BluetoothDevice device : sorted) {
            if (!running || Thread.currentThread().isInterrupted()) return;
            if (!connectionManager.hasCapacity()) return;

            String address = device.getAddress();
            if (connectionManager.isConnected(address) || !shouldAttempt(device)) continue;

            try {
                Thread.sleep(random.nextInt(MAX_JITTER_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            boolean connected = connectionManager.connectBlocking(device);
            recordResult(address, connected);
        }
    }

    private boolean shouldAttempt(@NonNull BluetoothDevice device) {
        String address = device.getAddress();
        Long next = nextAttemptAt.get(address);
        if (next != null && System.currentTimeMillis() < next) return false;
        return knownAppDevices.contains(address) || looksLikePhone(device);
    }

    /** Skips headphones, watches, keyboards, cars... Unknown device types are allowed. */
    @SuppressLint("MissingPermission")
    private boolean looksLikePhone(@NonNull BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            if (cls == null) return true;
            int major = cls.getMajorDeviceClass();
            return major == BluetoothClass.Device.Major.PHONE
                    || major == BluetoothClass.Device.Major.COMPUTER
                    || major == BluetoothClass.Device.Major.UNCATEGORIZED;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void recordResult(@NonNull String address, boolean connected) {
        if (connected) {
            failureCount.remove(address);
            nextAttemptAt.remove(address);
            if (knownAppDevices.add(address)) {
                prefs.edit()
                        .putStringSet(KEY_KNOWN, new HashSet<>(knownAppDevices))
                        .apply();
            }
            return;
        }

        Integer previous = failureCount.get(address);
        int failures = (previous == null ? 0 : previous) + 1;
        failureCount.put(address, failures);

        long backoffMs;
        if (knownAppDevices.contains(address)) {
            // Runs the app: probably just out of range, check again soon (20 s .. 3 min).
            backoffMs = Math.min(20_000L * failures, 180_000L);
        } else {
            // Probably not running the app: 2 min, 4 min, 8 min ... up to 30 min.
            backoffMs = Math.min(120_000L << Math.min(failures - 1, 4), 1_800_000L);
        }
        nextAttemptAt.put(address, System.currentTimeMillis() + backoffMs);
    }

    @SuppressLint("MissingPermission")
    @NonNull
    private List<BluetoothDevice> bondedCandidates() {
        List<BluetoothDevice> result = new ArrayList<>();
        if (adapter == null) return result;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) result.addAll(bonded);
        } catch (SecurityException ignored) {
            // No permission yet.
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void setScanning(boolean value) {
        if (scanning == value) return;
        scanning = value;
        Callback cb = callback;
        if (cb != null) cb.onScanStateChanged(value);
    }

    @SuppressLint("MissingPermission")
    private void cancelDiscoverySafely() {
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (SecurityException e) {
            Log.d(TAG, "cancelDiscovery without permission");
        }
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(appContext,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(appContext,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static BluetoothDevice extractDevice(@NonNull Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }
}