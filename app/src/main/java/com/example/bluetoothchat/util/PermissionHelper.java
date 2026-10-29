package com.example.bluetoothchat.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Which runtime permissions the app needs, which depends on the Android version:
 *
 *  - Android 12 and above: "Nearby devices" (BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
 *    BLUETOOTH_ADVERTISE). Android 13 and above also ask for notifications.
 *  - Android 11 and below: Bluetooth discovery needs the location permission.
 *    (The plain BLUETOOTH permissions are granted automatically at install.)
 *
 * Note for Android 11 and below: scan results only appear if Location is also turned
 * on in the phone's quick settings. The app cannot switch that on by itself.
 */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    /**
     * Everything worth asking for, to pass to the permission launcher. Includes the
     * notification permission on Android 13+, which is optional.
     */
    @NonNull
    public static String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    /**
     * True if the permissions the mesh cannot work without are granted.
     * The notification permission is NOT checked: without it the mesh still runs.
     */
    public static boolean hasBluetoothPermissions(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return granted(context, Manifest.permission.BLUETOOTH_SCAN)
                    && granted(context, Manifest.permission.BLUETOOTH_CONNECT)
                    && granted(context, Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        return granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private static boolean granted(@NonNull Context context, @NonNull String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}