package com.hujiayucc.notask;

import android.content.Context;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AppListAdapterTest {
    @Test public void packageHashCollisionsDoNotBecomeRecyclerViewStableIds() {
        AppListAdapter adapter = adapter();
        AppEntry first = new AppEntry("A", "example.Aa", null);
        AppEntry second = new AppEntry("B", "example.BB", null);
        assertEquals(first.packageName.hashCode(), second.packageName.hashCode());
        adapter.setEntries(Arrays.asList(first, second));
        assertFalse(adapter.hasStableIds());
        assertEquals(2, adapter.getItemCount());
    }

    @Test public void toggleReordersEnabledAppsWithoutGlobalInvalidation() {
        AppListAdapter adapter = adapter();
        AppEntry first = new AppEntry("A", "example.a", null);
        AppEntry second = new AppEntry("B", "example.b", null);
        adapter.setEntries(Arrays.asList(first, second));
        second.enabled = true;
        adapter.refresh();
        assertSame(second, adapter.getEntries().get(0));
        adapter.filter("EXAMPLE.A");
        assertEquals(1, adapter.getVisibleCount());
        adapter.filter("");
        assertEquals(2, adapter.getVisibleCount());
        verify(adapter, never()).notifyDataSetChanged();
    }

    private AppListAdapter adapter() {
        AppListAdapter adapter = spy(new AppListAdapter(mock(Context.class), mock(AppListAdapter.Listener.class)));
        // Android Observable is a stub in local JVM tests. Keep the real diff/list logic,
        // but intercept only the notification boundary that would require Android runtime.
        doNothing().when(adapter).notifyItemRangeInserted(anyInt(), anyInt());
        doNothing().when(adapter).notifyItemRangeRemoved(anyInt(), anyInt());
        doNothing().when(adapter).notifyItemMoved(anyInt(), anyInt());
        doNothing().when(adapter).notifyItemRangeChanged(anyInt(), anyInt(), any());
        return adapter;
    }
}
