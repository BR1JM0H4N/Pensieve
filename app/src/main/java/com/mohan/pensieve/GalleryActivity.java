package com.mohan.pensieve;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GalleryActivity extends AppCompatActivity {
    private GalleryAdapter adapter;
    private List<MediaItem> allItems = new ArrayList<>();
    private MediaSaver saver;
    private MediaItem.Type currentFilter = null;
    private Toolbar toolbar;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("Saved Media");

        saver = new MediaSaver(this);
        tvEmpty = findViewById(R.id.tv_empty);

        RecyclerView rv = findViewById(R.id.recycler_view);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new GalleryAdapter(this, new ArrayList<>());
        rv.setAdapter(adapter);

        setupFilterChips();
        loadMedia();

        adapter.setOnItemClickListener(new GalleryAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(MediaItem item, int position) {
                if (adapter.isSelectionMode()) {
                    adapter.toggleSelection(position);
                    updateSelectionTitle();
                } else {
                    openFile(item);
                }
            }

            @Override
            public void onItemLongClick(MediaItem item, int position) {
                if (!adapter.isSelectionMode()) {
                    adapter.setSelectionMode(true);
                    adapter.toggleSelection(position);
                    updateSelectionTitle();
                    invalidateOptionsMenu();
                }
            }
        });
    }

    private void setupFilterChips() {
        ChipGroup cg = findViewById(R.id.chip_group);

        String[] labels = {"All", "Images", "Videos", "Audio"};
        MediaItem.Type[] types = {null, MediaItem.Type.IMAGE, MediaItem.Type.VIDEO, MediaItem.Type.AUDIO};

        for (int i = 0; i < labels.length; i++) {
            final MediaItem.Type type = types[i];
            Chip chip = new Chip(this);
            chip.setText(labels[i]);
            chip.setCheckable(true);
            chip.setChecked(i == 0);
            chip.setOnClickListener(v -> {
                currentFilter = type;
                applyFilter();
            });
            cg.addView(chip);
        }
    }

    private void loadMedia() {
        allItems.clear();
        File root = saver.getRootDir();
        for (MediaItem.Type type : MediaItem.Type.values()) {
            File dir = new File(root, MediaSaver.getSubDir(type));
            if (!dir.exists()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String mime = MediaSaver.guessMimeFromUrl(f.getName());
                allItems.add(new MediaItem(f.getAbsolutePath(), f.getName(), type,
                        f.length(), f.lastModified(), ""));
            }
        }
        // Sort by newest first
        allItems.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        applyFilter();
    }

    private void applyFilter() {
        List<MediaItem> filtered = new ArrayList<>();
        for (MediaItem item : allItems) {
            if (currentFilter == null || item.getType() == currentFilter) {
                filtered.add(item);
            }
        }
        adapter.setItems(filtered);
        tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openFile(MediaItem item) {
        File f = new File(item.getFilePath());
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        String mime = MediaSaver.guessMimeFromUrl(item.getFileName());
        if (mime == null) mime = "*/*";
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareSelected() {
        List<MediaItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;

        ArrayList<Uri> uris = new ArrayList<>();
        for (MediaItem item : selected) {
            File f = new File(item.getFilePath());
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            uris.add(uri);
        }

        Intent intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("*/*");
        if (uris.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        startActivity(Intent.createChooser(intent, "Share via"));
    }

    private void deleteSelected() {
        List<MediaItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete " + selected.size() + " file(s)?")
                .setPositiveButton("Delete", (d, w) -> {
                    for (MediaItem item : selected) new File(item.getFilePath()).delete();
                    adapter.setSelectionMode(false);
                    invalidateOptionsMenu();
                    loadMedia();
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateSelectionTitle() {
        int count = adapter.getSelectedCount();
        if (getSupportActionBar() != null)
            getSupportActionBar().setTitle(count + " selected");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (adapter.isSelectionMode()) {
            menu.add(0, 1, 0, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, 2, 0, "Delete").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, 3, 0, "Cancel").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                if (adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                    getSupportActionBar().setTitle("Saved Media");
                    invalidateOptionsMenu();
                } else finish();
                return true;
            case 1: shareSelected(); return true;
            case 2: deleteSelected(); return true;
            case 3:
                adapter.setSelectionMode(false);
                getSupportActionBar().setTitle("Saved Media");
                invalidateOptionsMenu();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMedia();
    }
}
