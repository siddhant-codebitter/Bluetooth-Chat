package com.example.bluetoothchat.ui;

import android.app.NotificationManager;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluetoothchat.R;
import com.example.bluetoothchat.crypto.IdentityManager;
import com.example.bluetoothchat.databinding.ActivityChatBinding;
import com.example.bluetoothchat.db.AppDatabase;
import com.example.bluetoothchat.mesh.MeshRouter;
import com.example.bluetoothchat.mesh.MeshService;
import com.example.bluetoothchat.model.Message;
import com.example.bluetoothchat.util.InsetsHelper;
import com.example.bluetoothchat.util.PermissionHelper;

import java.util.List;

/**
 * One-to-one conversation. Open it with EXTRA_PEER_ID (required) and EXTRA_PEER_NAME.
 */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_PEER_NAME = "peer_name";

    private ActivityChatBinding binding;
    private IdentityManager identity;
    private AppDatabase db;
    private MessageAdapter adapter;
    private LinearLayoutManager layoutManager;

    private String peerId;
    private String peerName;
    private boolean resumed;
    private boolean firstLoadDone;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        if (peerId == null || peerId.isEmpty()) {
            finish();
            return;
        }
        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);
        if (peerName == null || peerName.isEmpty()) {
            peerName = peerId.length() > 8 ? peerId.substring(0, 8) : peerId;
        }

        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsHelper.applyToolbarInsets(binding.getRoot(), binding.toolbar);

        identity = new IdentityManager(this);
        db = AppDatabase.getInstance(this);

        binding.toolbar.setTitle(peerName);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        setupMessageList();
        setupInput();
        observeData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding == null) return;
        resumed = true;
        MeshService.setActiveChat(peerId);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(peerId.hashCode()); // same ID MeshService uses
        markChatRead();
    }

    @Override
    protected void onPause() {
        resumed = false;
        MeshService.setActiveChat(null);
        super.onPause();
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private void setupMessageList() {
        adapter = new MessageAdapter(identity.getDeviceId());
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // short chats sit at the bottom, like most chat apps
        binding.rvMessages.setLayoutManager(layoutManager);
        binding.rvMessages.setAdapter(adapter);
    }

    private void setupInput() {
        binding.etMessage.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(MeshRouter.MAX_TEXT_LENGTH)});
        binding.btnSend.setOnClickListener(v -> sendCurrentMessage());
    }

    private void observeData() {
        db.messageDao().observeMessages(peerId).observe(this, this::onMessagesChanged);

        db.peerDao().observePeer(peerId).observe(this, peer -> {
            if (peer != null && !peer.name.isEmpty()) {
                peerName = peer.name;
                binding.toolbar.setTitle(peerName);
            }
        });

        MeshService.getReachablePeers().observe(this, this::updateRouteInfo);
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    private void onMessagesChanged(@Nullable List<Message> messages) {
        final List<Message> list = messages == null ? java.util.Collections.<Message>emptyList()
                : messages;
        boolean empty = list.isEmpty();
        binding.tvEmptyMessages.setVisibility(empty ? View.VISIBLE : View.GONE);

        // Follow new messages only if the user is already at the bottom (or sent the message).
        final boolean stickToBottom = !firstLoadDone || isNearBottom() || lastIsMine(list);
        firstLoadDone = true;

        adapter.submitList(list, () -> {
            if (stickToBottom && adapter.getItemCount() > 0) {
                binding.rvMessages.scrollToPosition(adapter.getItemCount() - 1);
            }
        });

        if (resumed && !empty) markChatRead();
    }

    private boolean isNearBottom() {
        int last = layoutManager.findLastVisibleItemPosition();
        return last == RecyclerView.NO_POSITION || last >= adapter.getItemCount() - 2;
    }

    private boolean lastIsMine(@NonNull List<Message> list) {
        return !list.isEmpty()
                && list.get(list.size() - 1).isSentBy(identity.getDeviceId());
    }

    private void markChatRead() {
        AppDatabase.databaseExecutor.execute(() -> db.messageDao().markChatRead(peerId));
    }

    private void sendCurrentMessage() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;

        if (!identity.isMeshEnabled()) {
            Toast.makeText(this, R.string.toast_mesh_off, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            Toast.makeText(this, R.string.error_permissions_denied, Toast.LENGTH_LONG).show();
            return;
        }

        MeshService.sendMessage(this, peerId, text);
        binding.etMessage.setText("");
    }

    // ------------------------------------------------------------------
    // Connection info
    // ------------------------------------------------------------------

    /** Shows whether this person is directly connected, reachable through relays, or away. */
    private void updateRouteInfo(@Nullable List<MeshRouter.ReachablePeer> peers) {
        MeshRouter.ReachablePeer found = null;
        if (peers != null) {
            for (MeshRouter.ReachablePeer p : peers) {
                if (p.peerId.equals(peerId)) {
                    found = p;
                    break;
                }
            }
        }

        if (found != null && found.isDirect()) {
            binding.toolbar.setSubtitle(R.string.chat_subtitle_direct);
            binding.tvRouteInfo.setVisibility(View.GONE);
        } else if (found != null) {
            binding.toolbar.setSubtitle(R.string.chat_subtitle_relay);
            binding.tvRouteInfo.setText(R.string.route_via_mesh);
            binding.tvRouteInfo.setVisibility(View.VISIBLE);
        } else {
            binding.toolbar.setSubtitle(R.string.chat_subtitle_offline);
            binding.tvRouteInfo.setText(R.string.route_offline);
            binding.tvRouteInfo.setVisibility(View.VISIBLE);
        }
    }
}