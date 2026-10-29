package com.example.bluetoothchat.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluetoothchat.R;
import com.example.bluetoothchat.databinding.ItemMessageReceivedBinding;
import com.example.bluetoothchat.databinding.ItemMessageSentBinding;
import com.example.bluetoothchat.model.Message;
import com.example.bluetoothchat.util.TimeUtils;

/**
 * Chat bubbles: our messages on the right (item_message_sent.xml), the other
 * person's on the left (item_message_received.xml).
 */
public class MessageAdapter extends ListAdapter<Message, RecyclerView.ViewHolder> {

    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;

    private final String ownId;

    /** @param ownId this phone's device ID, used to tell sent from received messages */
    public MessageAdapter(@NonNull String ownId) {
        super(DIFF);
        this.ownId = ownId;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).isSentBy(ownId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SENT) {
            return new SentHolder(ItemMessageSentBinding.inflate(inflater, parent, false));
        }
        return new ReceivedHolder(ItemMessageReceivedBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message m = getItem(position);
        if (holder instanceof SentHolder) {
            ((SentHolder) holder).bind(m);
        } else {
            ((ReceivedHolder) holder).bind(m);
        }
    }

    @StringRes
    private static int statusLabel(int status) {
        switch (status) {
            case Message.STATUS_SENT:
                return R.string.message_status_sent;
            case Message.STATUS_DELIVERED:
                return R.string.message_status_delivered;
            case Message.STATUS_FAILED:
                return R.string.message_status_failed;
            case Message.STATUS_SENDING:
            default:
                return R.string.message_status_sending;
        }
    }

    static class SentHolder extends RecyclerView.ViewHolder {
        private final ItemMessageSentBinding b;

        SentHolder(@NonNull ItemMessageSentBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(@NonNull Message m) {
            b.tvMessage.setText(m.text);
            b.tvTime.setText(TimeUtils.formatMessageTime(m.timestamp));
            b.tvStatus.setText(statusLabel(m.status));
        }
    }

    static class ReceivedHolder extends RecyclerView.ViewHolder {
        private final ItemMessageReceivedBinding b;

        ReceivedHolder(@NonNull ItemMessageReceivedBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(@NonNull Message m) {
            b.tvMessage.setText(m.text);
            b.tvTime.setText(TimeUtils.formatMessageTime(m.timestamp));
        }
    }

    private static final DiffUtil.ItemCallback<Message> DIFF =
            new DiffUtil.ItemCallback<Message>() {
                @Override
                public boolean areItemsTheSame(@NonNull Message a, @NonNull Message b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Message a, @NonNull Message b) {
                    return a.text.equals(b.text)
                            && a.timestamp == b.timestamp
                            && a.status == b.status
                            && a.isRead == b.isRead;
                }
            };
}