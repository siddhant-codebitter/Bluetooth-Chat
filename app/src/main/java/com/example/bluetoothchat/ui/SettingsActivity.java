package com.example.bluetoothchat.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.bluetoothchat.R;
import com.example.bluetoothchat.crypto.IdentityManager;
import com.example.bluetoothchat.databinding.ActivitySettingsBinding;
import com.example.bluetoothchat.db.AppDatabase;
import com.example.bluetoothchat.util.InsetsHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Change the display name, switch relaying on or off, and delete all chats.
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private IdentityManager identity;
    private AppDatabase db;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsHelper.applyToolbarInsets(binding.getRoot(), binding.toolbar);

        identity = new IdentityManager(this);
        db = AppDatabase.getInstance(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        setupName();
        setupRelay();
        setupClearChats();

        binding.tvDeviceId.setText(
                getString(R.string.device_id_format, identity.getShortDeviceId()));
    }

    // ------------------------------------------------------------------
    // Display name
    // ------------------------------------------------------------------

    private void setupName() {
        binding.etName.setText(identity.getDisplayName());

        binding.etName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.tilName.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        binding.etName.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveName();
                return true;
            }
            return false;
        });
        binding.btnSaveName.setOnClickListener(v -> saveName());
    }

    private void saveName() {
        String name = binding.etName.getText() == null
                ? "" : binding.etName.getText().toString().trim();
        if (name.isEmpty()) {
            // Never save an empty name: the app would send you back to the welcome screen.
            binding.tilName.setError(getString(R.string.error_name_required));
            return;
        }

        identity.setDisplayName(name); // also trims and limits the length
        binding.etName.setText(identity.getDisplayName());
        binding.etName.clearFocus();
        hideKeyboard();
        Toast.makeText(this, R.string.toast_name_saved, Toast.LENGTH_SHORT).show();
        // Other phones see the new name the next time the mesh announces this phone
        // (within about a minute).
    }

    // ------------------------------------------------------------------
    // Relay
    // ------------------------------------------------------------------

    private void setupRelay() {
        binding.switchRelay.setChecked(identity.isRelayEnabled());
        // The router reads this setting for every packet, so no restart is needed.
        binding.switchRelay.setOnCheckedChangeListener(
                (button, checked) -> identity.setRelayEnabled(checked));
    }

    // ------------------------------------------------------------------
    // Clear chats
    // ------------------------------------------------------------------

    private void setupClearChats() {
        binding.btnClearChats.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dialog_clear_title)
                        .setMessage(R.string.dialog_clear_message)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_clear, (dialog, which) -> clearChats())
                        .show());
    }

    private void clearChats() {
        AppDatabase.databaseExecutor.execute(() -> {
            db.messageDao().deleteAll();
            runOnUiThread(() -> Toast.makeText(this, R.string.toast_chats_cleared,
                    Toast.LENGTH_SHORT).show());
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.getRoot().getWindowToken(), 0);
        }
    }
}