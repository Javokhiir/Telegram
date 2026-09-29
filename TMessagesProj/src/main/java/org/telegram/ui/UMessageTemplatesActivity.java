package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/** U message quick replies: "/shortcut " in a chat is replaced with the template text. */
public class UMessageTemplatesActivity extends BaseFragment {

    private LinearLayout list;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageTemplates));
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

        LinearLayout addSection = new LinearLayout(context);
        addSection.setOrientation(LinearLayout.VERTICAL);
        addSection.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        TextCell addCell = new TextCell(context);
        addCell.setTextAndIcon(LocaleController.getString(R.string.UMessageTemplateAdd), R.drawable.msg_add, false);
        addCell.setBackground(Theme.getSelectorDrawable(false));
        addCell.setOnClickListener(v -> showEditor(null));
        addSection.addView(addCell);
        content.addView(addSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.UMessageTemplatesInfo));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rebuildList();
        fragmentView = scrollView;
        return fragmentView;
    }

    private void rebuildList() {
        list.removeAllViews();
        final java.util.ArrayList<UMessageConfig.Template> templates = UMessageConfig.getTemplates();
        list.setVisibility(templates.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < templates.size(); i++) {
            final UMessageConfig.Template template = templates.get(i);
            TextDetailSettingsCell cell = new TextDetailSettingsCell(getContext());
            cell.setMultilineDetail(false);
            cell.setTextAndValue("/" + template.shortcut, template.text, i < templates.size() - 1);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> showEditor(template));
            list.addView(cell);
        }
    }

    private void showEditor(UMessageConfig.Template existing) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);

        final EditText shortcutField = createField(context, LocaleController.getString(R.string.UMessageTemplateShortcut), false);
        final EditText textField = createField(context, LocaleController.getString(R.string.UMessageTemplateText), true);
        if (existing != null) {
            shortcutField.setText(existing.shortcut);
            textField.setText(existing.text);
        }
        form.addView(shortcutField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        form.addView(textField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(existing == null ? R.string.UMessageTemplateAdd : R.string.UMessageTemplateEdit));
        builder.setView(form);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String shortcut = UMessageConfig.normalizeShortcut(shortcutField.getText().toString());
            final String text = textField.getText().toString().trim();
            if (TextUtils.isEmpty(shortcut) || TextUtils.isEmpty(text)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageTemplateEmpty)).show();
                return;
            }
            final UMessageConfig.Template sameShortcut = UMessageConfig.findTemplate(shortcut);
            if (sameShortcut != null && sameShortcut != existing) {
                UMessageConfig.getTemplates().remove(sameShortcut);
            }
            if (existing != null) {
                existing.shortcut = shortcut;
                existing.text = text;
            } else {
                UMessageConfig.getTemplates().add(new UMessageConfig.Template(shortcut, text));
            }
            UMessageConfig.saveTemplates();
            rebuildList();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                UMessageConfig.getTemplates().remove(existing);
                UMessageConfig.saveTemplates();
                rebuildList();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setFocusable(true);
        dialog.setOnShowListener(d -> {
            shortcutField.requestFocus();
            AndroidUtilities.showKeyboard(shortcutField);
        });
        showDialog(dialog);
        if (existing != null) {
            android.widget.TextView deleteButton = (android.widget.TextView) dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (deleteButton != null) {
                deleteButton.setTextColor(getThemedColor(Theme.key_text_RedBold));
            }
        }
    }

    private EditText createField(Context context, String hint, boolean multiline) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        field.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (multiline) {
            field.setMaxLines(6);
        } else {
            field.setSingleLine(true);
        }
        return field;
    }
}
