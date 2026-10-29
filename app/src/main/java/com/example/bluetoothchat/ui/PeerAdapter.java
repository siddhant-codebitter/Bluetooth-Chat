package com.example.bluetoothchat.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluetoothchat.R;
import com.example.bluetoothchat.databinding.ItemPeerBinding;
import com.example.bluetoothchat.mesh.MeshRouter;

/** The "Nearby devices" list on the main screen (item_peer.xml). */
public class PeerAdapter
        extends ListAdapter<MeshRouter.ReachablePeer, PeerAdapter.ViewHolder> {

    public interface Listener {
        void onPeerClick(@NonNull MeshRouter.ReachablePeer peer);
    }

    private final Listener listener;

    public PeerAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemPeerBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemPeerBinding b;

        ViewHolder(@NonNull ItemPeerBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(@NonNull MeshRouter.ReachablePeer peer) {
            Context context = b.getRoot().getContext();

            b.tvAvatar.setText(initialOf(peer.name));
            b.tvName.setText(peer.name);

            if (peer.isDirect()) {
                b.tvStatus.setText(R.string.peer_status_direct);
            } else {
                b.tvStatus.setText(context.getResources().getQuantityString(
                        R.plurals.peer_status_relay, peer.hops, peer.hops));
            }

            // Green dot: directly connected. Amber dot: reachable through relays.
            int color = peer.isDirect() ? R.color.status_online : R.color.banner_text;
            b.viewStatusDot.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(context, color)));

            b.getRoot().setOnClickListener(v -> listener.onPeerClick(peer));
        }
    }

    @NonNull
    private static String initialOf(@NonNull String name) {
        if (name.isEmpty()) return "?";
        int end = name.offsetByCodePoints(0, 1);
        return name.substring(0, end).toUpperCase();
    }

    private static final DiffUtil.ItemCallback<MeshRouter.ReachablePeer> DIFF =
            new DiffUtil.ItemCallback<MeshRouter.ReachablePeer>() {
                @Override
                public boolean areItemsTheSame(@NonNull MeshRouter.ReachablePeer a,
                                               @NonNull MeshRouter.ReachablePeer b) {
                    return a.peerId.equals(b.peerId);
                }

                @Override
                public boolean areContentsTheSame(@NonNull MeshRouter.ReachablePeer a,
                                                  @NonNull MeshRouter.ReachablePeer b) {
                    return a.name.equals(b.name) && a.hops == b.hops;
                }
            };
}