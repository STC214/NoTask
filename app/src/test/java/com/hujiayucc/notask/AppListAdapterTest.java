package com.hujiayucc.notask;

import android.content.Context;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import org.mockito.ArgumentCaptor;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AppListAdapterTest {
    @Test public void modeButtonsRouteThreeSelectionsAndIgnoreBindingOrUncheck() {
        AppListAdapter.Listener listener = mock(AppListAdapter.Listener.class);
        AppListAdapter adapter = new AppListAdapter(mock(Context.class), listener);
        MaterialCardView card = mock(MaterialCardView.class);
        when(card.findViewById(R.id.app_icon)).thenReturn(mock(ImageView.class));
        for (int id : new int[] {R.id.app_name, R.id.app_package, R.id.app_state}) {
            when(card.findViewById(id)).thenReturn(mock(TextView.class));
        }
        when(card.findViewById(R.id.scope_switch)).thenReturn(mock(MaterialSwitch.class));
        MaterialButtonToggleGroup group = mock(MaterialButtonToggleGroup.class);
        when(card.findViewById(R.id.mode_group)).thenReturn(group);
        for (int id : new int[] {R.id.mode_back, R.id.mode_background, R.id.mode_direct}) {
            when(card.findViewById(id)).thenReturn(mock(MaterialButton.class));
        }
        AppListAdapter.ViewHolder holder = adapter.new ViewHolder(card);
        holder.entry = new AppEntry("Example", "example.app", null);
        ArgumentCaptor<MaterialButtonToggleGroup.OnButtonCheckedListener> captor =
                ArgumentCaptor.forClass(MaterialButtonToggleGroup.OnButtonCheckedListener.class);
        verify(group).addOnButtonCheckedListener(captor.capture());
        captor.getValue().onButtonChecked(group, R.id.mode_back, true);
        captor.getValue().onButtonChecked(group, R.id.mode_background, true);
        captor.getValue().onButtonChecked(group, R.id.mode_direct, true);
        verify(listener).onModeChanged(holder.entry, ModuleConfig.MODE_BACK);
        verify(listener).onModeChanged(holder.entry, ModuleConfig.MODE_BACKGROUND);
        verify(listener).onModeChanged(holder.entry, ModuleConfig.MODE_DIRECT);
        clearInvocations(listener);
        captor.getValue().onButtonChecked(group, R.id.mode_direct, false);
        holder.binding = true;
        captor.getValue().onButtonChecked(group, R.id.mode_back, true);
        verifyNoInteractions(listener);
    }
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
