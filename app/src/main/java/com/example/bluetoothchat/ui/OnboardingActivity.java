package com.example.bluetoothchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.bluetoothchat.R;
import com.example.bluetoothchat.crypto.IdentityManager;
import com.example.bluetoothchat.databinding.ActivityOnboardingBinding;
import com.example.bluetoothchat.util.PermissionHelper;

/**
 * Launcher screen. On the very first run it asks for a display name and the
 * Bluetooth permissions. Every later launch skips straight to MainActivity.
 */
public class OnboardingActivity extends AppCompatActivity {

    private ActivityOnboardingBinding binding;
    private IdentityManager identity;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (!PermissionHelper.hasBluetoothPermissions(this)) {
                            // Let the user in anyway, with the mesh off. They can turn it on
                            // later and will be asked again.
                            Toast.makeText(this, R.string.error_permissions_denied,
                                    Toast.LENGTH_LONG).show();
                            identity.setMeshEnabled(false);
                        }
                        goToMain();
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        identity = new IdentityManager(this);
        if (identity.hasDisplayName()) {
            goToMain(); // already set up
            return;
        }

        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyInsets();

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
                onContinueClicked();
                return true;
            }
            return false;
        });
        binding.btnContinue.setOnClickListener(v -> onContinueClicked());
    }

    private void onContinueClicked() {
        String name = binding.etName.getText() == null
                ? "" : binding.etName.getText().toString();
        identity.setDisplayName(name); // trims and limits the length
        if (!identity.hasDisplayName()) {
            binding.tilName.setError(getString(R.string.error_name_required));
            return;
        }

        if (PermissionHelper.hasBluetoothPermissions(this)) {
            goToMain();
        } else {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions());
        }
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** Keeps the content clear of the status bar, navigation bar and keyboard. */
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }
}