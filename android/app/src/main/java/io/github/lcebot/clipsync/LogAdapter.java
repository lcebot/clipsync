package io.github.lcebot.clipsync;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Log page. The buffer is a 500-line ring and is replaced wholesale on every refresh — the
 * point of the RecyclerView is not incremental updates but that only the rows actually on screen
 * are ever measured, which one long TextView cannot do.
 */
final class LogAdapter extends RecyclerView.Adapter<LogAdapter.Row> {
    private final List<String> lines = new ArrayList<>();

    void submit(List<String> next) {
        lines.clear();
        lines.addAll(next);
        notifyDataSetChanged();
    }

    /** The whole log as one string, for the Copy action. */
    String text() {
        return String.join("\n", lines);
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Row((TextView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Row holder, int position) {
        ((TextView) holder.itemView).setText(lines.get(position));
    }

    static final class Row extends RecyclerView.ViewHolder {
        Row(@NonNull TextView v) {
            super(v);
        }
    }
}
