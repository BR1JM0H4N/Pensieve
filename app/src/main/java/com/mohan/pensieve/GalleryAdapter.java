package com.mohan.pensieve;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {
    private final Context context;
    private List<MediaItem> items;
    private final Set<Integer> selectedPositions = new HashSet<>();
    private boolean selectionMode = false;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(MediaItem item, int position);
        void onItemLongClick(MediaItem item, int position);
    }

    public GalleryAdapter(Context context, List<MediaItem> items) {
        this.context = context;
        this.items = items;
    }

    public void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

    public void setItems(List<MediaItem> items) {
        this.items = items;
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean mode) {
        selectionMode = mode;
        if (!mode) selectedPositions.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() { return selectionMode; }

    public void toggleSelection(int pos) {
        if (selectedPositions.contains(pos)) selectedPositions.remove(pos);
        else selectedPositions.add(pos);
        notifyItemChanged(pos);
    }

    public List<MediaItem> getSelectedItems() {
        List<MediaItem> selected = new ArrayList<>();
        for (int pos : selectedPositions) {
            if (pos < items.size()) selected.add(items.get(pos));
        }
        return selected;
    }

    public int getSelectedCount() { return selectedPositions.size(); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_media, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        MediaItem item = items.get(position);
        h.tvName.setText(item.getFileName());
        h.tvSize.setText(item.getFormattedSize());

        // Type icon / thumbnail
        if (item.getType() == MediaItem.Type.IMAGE) {
            Glide.with(context).load(new File(item.getFilePath())).centerCrop()
                    .placeholder(R.drawable.ic_image).into(h.ivThumb);
        } else if (item.getType() == MediaItem.Type.VIDEO) {
            Glide.with(context).load(new File(item.getFilePath())).centerCrop()
                    .placeholder(R.drawable.ic_video).into(h.ivThumb);
        } else if (item.getType() == MediaItem.Type.AUDIO) {
            h.ivThumb.setImageResource(R.drawable.ic_audio);
        } else {
            h.ivThumb.setImageResource(R.drawable.ic_file);
        }

        // Selection
        h.cbSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.cbSelect.setChecked(selectedPositions.contains(position));

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item, h.getAdapterPosition());
        });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onItemLongClick(item, h.getAdapterPosition());
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView tvName, tvSize;
        CheckBox cbSelect;

        ViewHolder(View v) {
            super(v);
            ivThumb = v.findViewById(R.id.iv_thumb);
            tvName = v.findViewById(R.id.tv_name);
            tvSize = v.findViewById(R.id.tv_size);
            cbSelect = v.findViewById(R.id.cb_select);
        }
    }
}
