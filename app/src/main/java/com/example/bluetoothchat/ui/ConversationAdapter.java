package com.example.bluetoothchat.ui;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluetoothchat.databinding.ItemConversationBinding;
import com.example.bluetoothchat.model.Conversation;
import com.example.bluetoothchat.util.TimeUtils;

import java.util.Objects;

/** The chat list on the main screen (item_conversation.xml). */
public class ConversationAdapter
        extends ListAdapter<Conversation, ConversationAdapter.ViewHolder> {

    public interface Listener {
        void onConversationClick(@NonNull Conversation conversation);

        void onConversationLongClick(@NonNull Conversation conversation);
    }

    private final Listener listener;

    public ConversationAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    /** The name to show: the peer's name, or the start of its ID if the name is unknown. */
    @NonNull
    public static String displayName(@NonNull Conversation c) {
        if (c.name == null || c.name.isEmpty() || c.name.equals(c.peerId)) {
            return c.peerId.length() > 8 ? c.peerId.substring(0, 8) : c.peerId;
        }
        return c.name;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemConversationBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemConversationBinding b;

        ViewHolder(@NonNull ItemConversationBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(@NonNull Conversation c) {
            String name = displayName(c);
            b.tvAvatar.setText(initialOf(name));
            b.tvName.setText(name);
            b.tvTime.setText(TimeUtils.formatListTime(c.lastTimestamp));
            b.tvLastMessage.setText(c.lastMessage);

            boolean unread = c.unreadCount > 0;
            b.tvLastMessage.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
            b.tvUnread.setVisibility(unread ? View.VISIBLE : View.GONE);
            if (unread) {
                b.tvUnread.setText(c.unreadCount > 99 ? "99+" : String.valueOf(c.unreadCount));
            }

            b.getRoot().setOnClickListener(v -> listener.onConversationClick(c));
            b.getRoot().setOnLongClickListener(v -> {
                listener.onConversationLongClick(c);
                return true;
            });
        }
    }

    @NonNull
    private static String initialOf(@NonNull String name) {
        if (name.isEmpty()) return "?";
        int end = name.offsetByCodePoints(0, 1);
        return name.substring(0, end).toUpperCase();
    }

    private static final DiffUtil.ItemCallback<Conversation> DIFF =
            new DiffUtil.ItemCallback<Conversation>() {
                @Override
                public boolean areItemsTheSame(@NonNull Conversation a, @NonNull Conversation b) {
                    return a.peerId.equals(b.peerId);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Conversation a, @NonNull Conversation b) {
                    return Objects.equals(a.name, b.name)
                            && Objects.equals(a.lastMessage, b.lastMessage)
                            && a.lastTimestamp == b.lastTimestamp
                            && a.unreadCount == b.unreadCount;
                }
            };
}