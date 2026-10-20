package com.hujiayucc.notask;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.card.MaterialCardView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
    interface Listener {
        void onScopeChanged(AppEntry entry, boolean enabled);
    }

    private final Context context;
    private final Listener listener;
    private final Collator collator = Collator.getInstance(Locale.getDefault());
    private final Set<String> pendingPackages = new HashSet<>();
    private final List<AppEntry> entries = new ArrayList<>();
    private final List<AppEntry> visibleEntries = new ArrayList<>();

    private boolean controlsEnabled;
    private String query = "";

    AppListAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void setEntries(List<AppEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        rebuildVisibleEntries();
    }

    List<AppEntry> getEntries() {
        return entries;
    }

    int getVisibleCount() {
        return visibleEntries.size();
    }

    void setControlsEnabled(boolean enabled) {
        if (controlsEnabled == enabled) {
            return;
        }
        controlsEnabled = enabled;
        notifyItemRangeChanged(0, visibleEntries.size());
    }

    void setPending(String packageName, boolean pending) {
        if (pending) {
            pendingPackages.add(packageName);
        } else {
            pendingPackages.remove(packageName);
        }
        refresh();
    }

    void clearPending() {
        pendingPackages.clear();
    }

    void filter(String value) {
        query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        rebuildVisibleEntries();
    }

    void refresh() {
        rebuildVisibleEntries();
    }

    private void rebuildVisibleEntries() {
        List<AppEntry> oldVisible = new ArrayList<>(visibleEntries);
        entries.sort((left, right) -> {
            int scopeResult = Boolean.compare(!left.enabled, !right.enabled);
            if (scopeResult != 0) {
                return scopeResult;
            }
            return collator.compare(left.label, right.label);
        });

        visibleEntries.clear();
        for (AppEntry entry : entries) {
            if (query.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(query)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                visibleEntries.add(entry);
            }
        }
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldVisible.size(); }
            @Override public int getNewListSize() { return visibleEntries.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return oldVisible.get(oldPosition).packageName.equals(visibleEntries.get(newPosition).packageName);
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                // Entries are mutable; rebinding also reflects pending/connection state changes.
                return false;
            }
        }).dispatchUpdatesTo(this);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        AppEntry entry = visibleEntries.get(position);
        boolean pending = pendingPackages.contains(entry.packageName);
        boolean enabled = controlsEnabled && !pending;

        holder.binding = true;
        holder.entry = entry;
        holder.card.setCardBackgroundColor(ContextCompat.getColor(context,
                entry.enabled ? R.color.row_enabled : R.color.card_surface));
        holder.card.setStrokeColor(ContextCompat.getColor(context,
                entry.enabled ? R.color.row_outline : R.color.divider));
        holder.icon.setImageDrawable(entry.icon);
        holder.name.setText(entry.label);
        holder.packageName.setText(entry.packageName);
        holder.scopeSwitch.setContentDescription(context.getString(R.string.enable_app, entry.label));
        holder.scopeSwitch.setChecked(entry.enabled);
        holder.scopeSwitch.setEnabled(enabled);

        holder.state.setText(entry.enabled ? R.string.app_enabled : R.string.app_disabled);
        holder.state.setTextColor(ContextCompat.getColor(context,
                entry.enabled ? R.color.primary : R.color.on_surface_variant));
        holder.binding = false;
    }

    @Override
    public int getItemCount() {
        return visibleEntries.size();
    }

    final class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView state;
        final MaterialSwitch scopeSwitch;

        boolean binding;
        AppEntry entry;

        ViewHolder(View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            icon = itemView.findViewById(R.id.app_icon);
            name = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.app_package);
            state = itemView.findViewById(R.id.app_state);
            scopeSwitch = itemView.findViewById(R.id.scope_switch);
            scopeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!binding && entry != null) {
                    listener.onScopeChanged(entry, isChecked);
                }
            });

        }
    }
}
