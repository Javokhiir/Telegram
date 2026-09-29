package org.telegram.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/** U message focus mode: during the chosen hours only the allowed folders notify. */
public class UMessageFocusActivity extends BaseFragment {

    private TextSettingsCell startCell, endCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageFocus));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // on/off + hours
        LinearLayout main = section(context);
        TextCheckCell enabledCell = new TextCheckCell(context);
        enabledCell.setTextAndCheck(LocaleController.getString(R.string.UMessageFocusEnable), UMessageConfig.isFocusEnabled(), true);
        enabledCell.setBackground(Theme.getSelectorDrawable(false));
        enabledCell.setOnClickListener(v -> {
            final boolean enabled = !UMessageConfig.isFocusEnabled();
            UMessageConfig.setFocusEnabled(enabled);
            enabledCell.setChecked(enabled);
        });
        main.addView(enabledCell);

        startCell = new TextSettingsCell(context);
        startCell.setBackground(Theme.getSelectorDrawable(false));
        startCell.setOnClickListener(v -> pickTime(true));
        main.addView(startCell);

        endCell = new TextSettingsCell(context);
        endCell.setBackground(Theme.getSelectorDrawable(false));
        endCell.setOnClickListener(v -> pickTime(false));
        main.addView(endCell);
        updateTimes();
        content.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.UMessageFocusInfo));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // allowed folders
        LinearLayout folders = section(context);
        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(R.string.UMessageFocusFolders));
        folders.addView(header);
        final HashSet<Integer> allowed = UMessageConfig.getFocusFolders();
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter filter = filters.get(i);
            final String name = filter.isDefault() ? LocaleController.getString(R.string.FilterAllChats) : filter.name;
            TextCheckCell cell = new TextCheckCell(context);
            cell.setTextAndCheck(name, allowed.contains(filter.id), i < filters.size() - 1);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> {
                final HashSet<Integer> set = UMessageConfig.getFocusFolders();
                final boolean checked = !set.contains(filter.id);
                if (checked) {
                    set.add(filter.id);
                } else {
                    set.remove(filter.id);
                }
                UMessageConfig.setFocusFolders(set);
                cell.setChecked(checked);
            });
            folders.addView(cell);
        }
        if (filters.isEmpty()) {
            TextInfoPrivacyCell empty = new TextInfoPrivacyCell(context);
            empty.setText(LocaleController.getString(R.string.UMessageFocusNoFolders));
            folders.addView(empty);
        }
        content.addView(folders, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell foldersInfo = new TextInfoPrivacyCell(context);
        foldersInfo.setText(LocaleController.getString(R.string.UMessageFocusFoldersInfo));
        content.addView(foldersInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = scrollView;
        return fragmentView;
    }

    private LinearLayout section(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        return layout;
    }

    private void updateTimes() {
        startCell.setTextAndValue(LocaleController.getString(R.string.UMessageFocusFrom), formatTime(UMessageConfig.getFocusStart()), true);
        endCell.setTextAndValue(LocaleController.getString(R.string.UMessageFocusTo), formatTime(UMessageConfig.getFocusEnd()), false);
    }

    private static String formatTime(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void pickTime(boolean start) {
        if (getParentActivity() == null) {
            return;
        }
        final int current = start ? UMessageConfig.getFocusStart() : UMessageConfig.getFocusEnd();
        new TimePickerDialog(getParentActivity(), (view, hour, minute) -> {
            final int value = hour * 60 + minute;
            if (start) {
                UMessageConfig.setFocusHours(value, UMessageConfig.getFocusEnd());
            } else {
                UMessageConfig.setFocusHours(UMessageConfig.getFocusStart(), value);
            }
            updateTimes();
        }, current / 60, current % 60, DateFormat.is24HourFormat(getParentActivity())).show();
    }
}
