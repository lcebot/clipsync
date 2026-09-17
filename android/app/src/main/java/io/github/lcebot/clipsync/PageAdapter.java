package io.github.lcebot.clipsync;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The two pages of the ViewPager2, handed in already inflated.
 *
 * <p>Both pages exist for the activity's whole life — the activity holds direct references to every
 * field on them — so there is nothing to create lazily and nothing to recycle. Giving each position
 * its own view type means the recycler keeps one holder per page and never reuses one page's view
 * for the other. Using this instead of a FragmentStateAdapter keeps the activity's single
 * findViewById pass intact; with two static pages, fragments would buy only lifecycle overhead.
 */
final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Page> {
    private final View[] pages;

    PageAdapter(View... pages) {
        this.pages = pages;
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return pages.length;
    }

    @NonNull
    @Override
    public Page onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View page = pages[viewType];
        // ViewPager2 insists on exactly these; the page layouts declare them too, but the
        // inflate(..., null) in the activity drops them
        page.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return new Page(page);
    }

    @Override
    public void onBindViewHolder(@NonNull Page holder, int position) {
        // nothing: the activity owns the contents
    }

    static final class Page extends RecyclerView.ViewHolder {
        Page(@NonNull View v) {
            super(v);
        }
    }
}
