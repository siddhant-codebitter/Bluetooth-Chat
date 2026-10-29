package com.example.bluetoothchat.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.bluetoothchat.R;
import com.example.bluetoothchat.crypto.IdentityManager;
import com.example.bluetoothchat.databinding.ActivityMainBinding;
import com.example.bluetoothchat.db.AppDatabase;
import com.example.bluetoothchat.mesh.MeshRouter;
import com.example.bluetoothchat.mesh.MeshService;
import com.example.bluetoothchat.model.Conversation;
import com.example.bluetoothchat.util.InsetsHelper;
import com.example.bluetoothchat.util.PermissionHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Main screen: mesh on/off switch, phones in range, and the list of chats.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private IdentityManager identity;
    private AppDatabase db;
    private PeerAdapter peerAdapter;
    private ConversationAdapter conversationAdapter;

    /** True while we change the switch from code, so its listener ignores it. */
    private boolean updatingSwitch;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (PermissionHelper.hasBluetoothPermissions(this)) {
                            tryStartMesh();
                        } else {
                            Toast.makeText(this, R.string.error_permissions_denied,
                                    Toast.LENGTH_LONG).show();
                            setMeshPreference(false);
                        }
                    });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            tryStartMesh();
                        } else {
                            Toast.makeText(this, R.string.error_bluetooth_off,
                                    Toast.LENGTH_LONG).show();
                            setMeshPreference(false);
                        }
                    });

    private final ActivityResultLauncher<Intent> discoverableLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // Nothing to do: the system shows its own confirmation.
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        identity = new IdentityManager(this);
        if (!identity.hasDisplayName()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        InsetsHelper.applyToolbarInsets(binding.getRoot(), binding.toolbar);

        db = AppDatabase.getInstance(this);

        setupLists();
        setupSwitch();
        observeData();

        // On a fresh launch, make sure the mesh is running (may ask for permissions).
        if (savedInstanceState == null && identity.isMeshEnabled()) {
            tryStartMesh();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (binding == null) return;
        updateSwitch(identity.isMeshEnabled()); // the notification's Stop button may have changed it
        autoStartMeshQuietly();
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private void setupLists() {
        peerAdapter = new PeerAdapter(peer -> openChat(peer.peerId, peer.name));
        binding.rvPeers.setAdapter(peerAdapter);

        conversationAdapter = new ConversationAdapter(new ConversationAdapter.Listener() {
            @Override
            public void onConversationClick(@NonNull Conversation c) {
                openChat(c.peerId, ConversationAdapter.displayName(c));
            }

            @Override
            public void onConversationLongClick(@NonNull Conversation c) {
                confirmDeleteChat(c);
            }
        });
        binding.rvConversations.setAdapter(conversationAdapter);
    }

    private void setupSwitch() {
        updateSwitch(identity.isMeshEnabled());
        binding.switchMesh.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) return;
            identity.setMeshEnabled(checked);
            if (checked) {
                tryStartMesh();
            } else {
                MeshService.stop(this);
            }
        });
    }

    private void observeData() {
        MeshService.isRunning().observe(this, running -> updateStatus());
        MeshService.getReachablePeers().observe(this, peers -> updateStatus());

        db.messageDao().observeConversations().observe(this, conversations -> {
            boolean empty = conversations == null || conversations.isEmpty();
            binding.tvEmptyChats.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.rvConversations.setVisibility(empty ? View.GONE : View.VISIBLE);
            conversationAdapter.submitList(conversations);
        });
    }

    // ------------------------------------------------------------------
    // Status card and nearby list
    // ------------------------------------------------------------------

    private void updateStatus() {
        boolean running = Boolean.TRUE.equals(MeshService.isRunning().getValue());
        List<MeshRouter.ReachablePeer> peers = MeshService.getReachablePeers().getValue();
        int count = (running && peers != null) ? peers.size() : 0;

        binding.tvMeshStatus.setText(running ? R.string.mesh_status_on : R.string.mesh_status_off);
        binding.viewStatusDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this,
                        running ? R.color.status_online : R.color.status_offline)));
        binding.tvPeerCount.setText(count == 0
                ? getString(R.string.peer_count_none)
                : getResources().getQuantityString(R.plurals.peer_count, count, count));

        boolean hasPeers = count > 0;
        binding.rvPeers.setVisibility(hasPeers ? View.VISIBLE : View.GONE);
        binding.tvNoPeers.setVisibility(hasPeers ? View.GONE : View.VISIBLE);
        binding.tvNoPeers.setText(running ? R.string.no_peers_nearby : R.string.peers_mesh_off);
        peerAdapter.submitList(hasPeers ? new ArrayList<>(peers) : new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // Mesh on/off
    // ------------------------------------------------------------------

    /** Checks permissions and Bluetooth, asking the user where needed, then starts the mesh. */
    private void tryStartMesh() {
        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions());
            return;
        }
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_unsupported, Toast.LENGTH_LONG).show();
            setMeshPreference(false);
            return;
        }
        if (!adapter.isEnabled()) {
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        MeshService.start(this);
    }

    /** Restarts the mesh if it should be on but is not, without showing any dialogs. */
    private void autoStartMeshQuietly() {
        if (!identity.isMeshEnabled()) return;
        if (Boolean.TRUE.equals(MeshService.isRunning().getValue())) return;
        if (!PermissionHelper.hasBluetoothPermissions(this)) return;
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        MeshService.start(this);
    }

    private void setMeshPreference(boolean enabled) {
        identity.setMeshEnabled(enabled);
        updateSwitch(enabled);
    }

    private void updateSwitch(boolean checked) {
        updatingSwitch = true;
        binding.switchMesh.setChecked(checked);
        updatingSwitch = false;
    }

    @Nullable
    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager != null ? manager.getAdapter() : null;
    }

    private boolean isMeshRunning() {
        return Boolean.TRUE.equals(MeshService.isRunning().getValue());
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_scan_now) {
            if (isMeshRunning()) {
                MeshService.scanNow(this);
                Toast.makeText(this, R.string.toast_scanning, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_mesh_off, Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_make_discoverable) {
            requestDiscoverable();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Phones that are not paired can only find each other while at least one of them is
     * discoverable. Android shows its own confirmation dialog (5 minutes).
     */
    private void requestDiscoverable() {
        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions());
            return;
        }
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, R.string.error_bluetooth_off, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        discoverableLauncher.launch(intent);
    }

    // ------------------------------------------------------------------
    // Navigation and actions
    // ------------------------------------------------------------------

    private void openChat(@NonNull String peerId, @Nullable String peerName) {
        startActivity(new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_PEER_ID, peerId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, peerName));
    }

    private void confirmDeleteChat(@NonNull Conversation c) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_chat_title)
                .setMessage(R.string.dialog_delete_chat_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        AppDatabase.databaseExecutor.execute(
                                () -> db.messageDao().deleteChat(c.peerId)))
                .show();
    }
}