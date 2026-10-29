package com.example.bluetoothchat.util;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * On newer Android versions apps are drawn behind the status bar and navigation bar.
 * This keeps the toolbar's content below the status bar (the blue background still
 * extends behind it) and keeps the rest of the screen clear of the navigation bar
 * and the on-screen keyboard.
 *
 * Call once in onCreate, after setContentView().
 */
public final class InsetsHelper {

    private InsetsHelper() {
    }

    public static void applyToolbarInsets(@NonNull View root, @NonNull Toolbar toolbar) {
        final int baseHeight = actionBarSizePx(root.getContext());

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            toolbar.setPadding(toolbar.getPaddingLeft(), bars.top,
                    toolbar.getPaddingRight(), 0);
            ViewGroup.LayoutParams params = toolbar.getLayoutParams();
            int wantedHeight = baseHeight + bars.top;
            if (params.height != wantedHeight) {
                params.height = wantedHeight;
                toolbar.setLayoutParams(params);
            }

            view.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private static int actionBarSizePx(@NonNull Context context) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.actionBarSize, value, true)) {
            return TypedValue.complexToDimensionPixelSize(
                    value.data, context.getResources().getDisplayMetrics());
        }
        return (int) (56 * context.getResources().getDisplayMetrics().density);
    }
}