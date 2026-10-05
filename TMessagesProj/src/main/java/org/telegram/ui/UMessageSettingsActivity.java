package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.Components.RoundVideoEffects;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageAdminFolders;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.privacyguard.OwnerFaceStore;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageLogoView;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** U message options presented with Telegram's native settings layout and cells. */
public class UMessageSettingsActivity extends BaseFragment {

    private static final int BLOCK_ADS = 1;
    private static final int TEMPLATES = 2;
    private static final int FOCUS = 3;
    private static final int DESIGN = 4;
    private static final int BASED_ON = 5;

    private static final int TOGGLE_BASE = 100; // + index in TOGGLES
    private static final int AUTO_REPLY_TEXT = 10;
    private static final int REMINDER_DELAY = 11;
    private static final int REMINDER_SOUND = 12;
    private static final int STRANGER_CHATS = 13;
    private static final int HIDDEN_CHATS = 14;
    private static final int HIDDEN_PASSWORD = 15;
    private static final int HIDDEN_ACCESS = 16;
    private static final int ACCOUNT_AUTO_SWITCH = 17;
    private static final int ROUND_EFFECTS = 18;
    private static final int PRIVACY_GUARD = 19;
    private static final int USE_PROXY = 20;
    private static final int HIDDEN_FOLDERS = 21;
    private static final int UM_PREMIUM = 22;
    private static final int NEARBY_SHARE = 23;
    private static final int SPEECH_ENABLED = 25;
    private static final int SPEECH_MODEL = 26;

    /** Every switch on this screen, by its UMessageConfig key; the item id is TOGGLE_BASE + index. */
    private static final String[] TOGGLES = {
            UMessageConfig.KEY_GHOST_MODE,
            UMessageConfig.KEY_GHOST_BUTTON,
            UMessageConfig.KEY_HIDDEN_CHATS,
            UMessageConfig.KEY_SAVE_EDITED,
            UMessageConfig.KEY_SAVE_DELETED,
            UMessageConfig.KEY_STRANGER_PROTECTION,
            UMessageConfig.KEY_BLOCK_APK,
            UMessageConfig.KEY_FOLDER_ICONS,
            UMessageConfig.KEY_HIDE_FOLDER_TABS,
            UMessageConfig.KEY_ADMIN_FOLDERS,
            UMessageConfig.KEY_STORIES_ANONYMOUS,
            UMessageConfig.KEY_STORIES_HIDDEN,
            UMessageConfig.KEY_STORIES_DOWNLOAD,
            UMessageConfig.KEY_UNREAD_REMINDER,
            UMessageConfig.KEY_AUTO_REPLY,
            UMessageConfig.KEY_CONFIRM_STICKER,
            UMessageConfig.KEY_CONFIRM_VOICE,
            UMessageConfig.KEY_CONFIRM_GIF,
            UMessageConfig.KEY_VOICE_INPUT,
            UMessageConfig.KEY_ROUND_FRONT_CAMERA,
            UMessageConfig.KEY_AUTO_APPROVE,
            UMessageConfig.KEY_STOP_AUTODOWNLOAD,
    };
    private static final int[] TOGGLE_TITLES = {
            R.string.UMessageGhostMode,
            R.string.UMessageGhostButton,
            R.string.UMessageSecretChat,
            R.string.UMessageSaveEdited,
            R.string.UMessageSaveDeleted,
            R.string.UMessageStrangerProtection,
            R.string.UMessageBlockApk,
            R.string.UMessageFolderIcons,
            R.string.UMessageHideFolderTabs,
            R.string.UMessageAdminFolders,
            R.string.UMessageStoriesAnonymous,
            R.string.UMessageStoriesHide,
            R.string.UMessageStoriesDownload,
            R.string.UMessageReminder,
            R.string.UMessageAutoReply,
            R.string.UMessageConfirmSticker,
            R.string.UMessageConfirmVoice,
            R.string.UMessageConfirmGif,
            R.string.UMessageVoiceInput,
            R.string.UMessageFrontCamera,
            R.string.UMessageAutoApprove,
            R.string.UMessageStopAutoDownload,
    };
    private static final int[] TOGGLE_INFOS = {
            R.string.UMessageGhostModeInfo,
            R.string.UMessageGhostButtonInfo,
            R.string.UMessageSecretChatInfo,
            R.string.UMessageSaveEditedInfo,
            R.string.UMessageSaveDeletedInfo,
            R.string.UMessageStrangerProtectionInfo,
            R.string.UMessageBlockApkInfo,
            R.string.UMessageFolderIconsInfo,
            R.string.UMessageHideFolderTabsInfo,
            R.string.UMessageAdminFoldersInfo,
            R.string.UMessageStoriesAnonymousInfo,
            R.string.UMessageStoriesHideInfo,
            R.string.UMessageStoriesDownloadInfo,
            R.string.UMessageReminderInfo,
            R.string.UMessageAutoReplyInfo,
            R.string.UMessageConfirmStickerInfo,
            R.string.UMessageConfirmVoiceInfo,
            R.string.UMessageConfirmGifInfo,
            R.string.UMessageVoiceInputInfo,
            R.string.UMessageFrontCameraInfo,
            R.string.UMessageAutoApproveInfo,
            R.string.UMessageStopAutoDownloadInfo,
    };

    private FrameLayout contentView;
    private UniversalRecyclerView listView;
    private View actionBarBackground;
    private UMessageLogoView logoView;
    private TextView versionView;
    private boolean actionBarVisible;
    private ValueAnimator actionBarVisibleAnimator;

    @Override
    public View createView(Context context) {
        contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setUseContainerForTitles();
        actionBar.setTitle("U message");
        actionBar.setAddToContainer(false);
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout topView = createTopView(context);
        versionView = new TextView(context);
        versionView.setText("U message " + BuildVars.BUILD_VERSION_STRING);
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText4));
        versionView.setGravity(Gravity.CENTER);
        versionView.setPadding(dp(21), dp(10), dp(21), dp(18));

        listView = new UniversalRecyclerView(this, (items, adapter) -> fillItems(items, adapter, topView), this::onItemClick, null);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        listView.setPadding(0, AndroidUtilities.statusBarHeight + dp(12), 0, AndroidUtilities.navigationBarHeight);
        listView.setClipToPadding(false);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateActionBarVisible(true);
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        actionBarBackground = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final int height = actionBar.getHeight();
                paint.setColor(getThemedColor(Theme.key_actionBarDefault));
                canvas.drawRect(0, 0, getWidth(), height, paint);
                if (getParentLayout() != null) {
                    getParentLayout().drawHeaderShadow(canvas, height);
                }
            }
        };
        contentView.addView(actionBarBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.TOP));
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        listView.adapter.update(false);
        updateActionBarVisible(false);

        fragmentView = contentView;
        return fragmentView;
    }

    private FrameLayout createTopView(Context context) {
        FrameLayout topView = new FrameLayout(context);

        FrameLayout logoContainer = new FrameLayout(context);
        logoContainer.setBackground(Theme.createCircleDrawable(dp(90), getThemedColor(Theme.key_featuredStickers_addButton)));
        logoContainer.setContentDescription("U message");
        topView.addView(logoContainer, LayoutHelper.createFrame(90, 90, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 15, 0, 0));
        ScaleStateListAnimator.apply(logoContainer);

        logoView = new UMessageLogoView(context);
        logoView.setColor(0xfff7f8fa);
        logoView.setOnClickListener(null);
        logoContainer.addView(logoView, LayoutHelper.createFrame(64, 42, Gravity.CENTER));
        logoContainer.setOnClickListener(v -> logoView.play());

        TextView titleView = new TextView(context);
        titleView.setText("U message");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        topView.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 16, 126, 16, 0));

        TextView subtitleView = new TextView(context);
        subtitleView.setText(LocaleController.getString(R.string.UMessageTagline));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setSingleLine(true);
        topView.addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 16, 156, 16, 0));

        return topView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter, View topView) {
        items.add(UItem.asCustomShadow(topView, 188));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionGeneral)));
        items.add(UItem.asButtonCheck(BLOCK_ADS, LocaleController.getString(R.string.UMessageBlockAds), LocaleController.getString(R.string.UMessageBlockAdsInfo))
                .setChecked(UMessageConfig.isAdsBlocked()));
        items.add(UItem.asButtonCheck(USE_PROXY, LocaleController.getString(R.string.UMessageUseProxy), LocaleController.getString(R.string.UMessageUseProxyInfo))
                .setChecked(UMessageConfig.isProxyFallbackEnabled()));
        items.add(UItem.asButtonCheck(UM_PREMIUM, LocaleController.getString(R.string.UMessagePremium), LocaleController.getString(R.string.UMessagePremiumInfo))
                .setChecked(UMessagePremiumController.getInstance().isSelfEnabled()));
        items.add(UItem.asButtonCheck(NEARBY_SHARE, LocaleController.getString(R.string.UMessageNearbyShare), LocaleController.getString(R.string.UMessageNearbyShareInfo))
                .setChecked(UMessageConfig.isNearbyShareEnabled()));
        items.add(UItem.asButton(TEMPLATES, LocaleController.getString(R.string.UMessageTemplates), String.valueOf(UMessageConfig.getTemplates().size())));
        items.add(UItem.asButton(FOCUS, LocaleController.getString(R.string.UMessageFocus),
                LocaleController.getString(UMessageConfig.isFocusEnabled() ? R.string.PasswordOn : R.string.PasswordOff)));
        items.add(UItem.asShadow(null));

        if (org.telegram.messenger.UMessageUzbekSpeech.isSupported()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSpeechModelTitle)));
            items.add(UItem.asCheck(SPEECH_ENABLED, LocaleController.getString(R.string.UMessageSpeechEnabled)).setChecked(org.telegram.messenger.UMessageUzbekSpeech.isEnabled()));
            final long totalMb = org.telegram.messenger.UMessageUzbekSpeech.TOTAL_BYTES / (1024 * 1024);
            String state;
            if (org.telegram.messenger.UMessageUzbekSpeech.isDownloading()) {
                state = LocaleController.formatString(R.string.UMessageSpeechModelProgress, (int) (org.telegram.messenger.UMessageUzbekSpeech.getProgressBytes() * 100 / org.telegram.messenger.UMessageUzbekSpeech.TOTAL_BYTES));
            } else if (org.telegram.messenger.UMessageUzbekSpeech.isReady()) {
                state = LocaleController.getString(R.string.UMessageSpeechModelDelete);
            } else {
                state = LocaleController.getString(R.string.UMessageSpeechModelDownload) + " · " + totalMb + " MB";
            }
            items.add(UItem.asButton(SPEECH_MODEL, LocaleController.getString(R.string.UMessageSpeechModelRow), state));
            items.add(UItem.asShadow(LocaleController.formatString(R.string.UMessageSpeechModelInfo, totalMb)));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionPrivacy)));
        addToggle(items, UMessageConfig.KEY_GHOST_MODE);
        addToggle(items, UMessageConfig.KEY_GHOST_BUTTON);
        items.add(UItem.asButton(PRIVACY_GUARD, LocaleController.getString(R.string.PrivacyGuard),
                LocaleController.getString(PrivacyGuardSettings.isEnabled() && OwnerFaceStore.hasOwner() ? R.string.PasswordOn : R.string.PasswordOff)));
        addToggle(items, UMessageConfig.KEY_HIDDEN_CHATS);
        if (UMessageConfig.isHiddenChatsEnabled()) {
            items.add(UItem.asButton(HIDDEN_CHATS, LocaleController.getString(R.string.UMessageHiddenChats), String.valueOf(UMessageConfig.getHiddenChats(currentAccount).size())));
            items.add(UItem.asButton(HIDDEN_FOLDERS, LocaleController.getString(R.string.UMessageHiddenFolders), String.valueOf(UMessageConfig.getHiddenFolders(currentAccount).size())));
            items.add(UItem.asButton(HIDDEN_ACCESS, LocaleController.getString(R.string.UMessageHiddenAccess), getAccessName(UMessageConfig.getHiddenAccess())));
            items.add(UItem.asButton(HIDDEN_PASSWORD, LocaleController.getString(UMessageConfig.hasHiddenPassword() ? R.string.UMessageHiddenPasswordChange : R.string.UMessageHiddenPasswordSet)));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionMessages)));
        addToggle(items, UMessageConfig.KEY_SAVE_EDITED);
        addToggle(items, UMessageConfig.KEY_SAVE_DELETED);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionSecurity)));
        items.add(UItem.asButton(ACCOUNT_AUTO_SWITCH,
                LocaleController.getString(R.string.UMessageAccountAutoSwitch),
                LocaleController.getString(UMessageConfig.hasAccountSwitchPins() ? R.string.UMessageConfigured : R.string.UMessageNotConfigured)));
        addToggle(items, UMessageConfig.KEY_STRANGER_PROTECTION);
        items.add(UItem.asButton(STRANGER_CHATS, LocaleController.getString(R.string.UMessageStrangerChats)));
        addToggle(items, UMessageConfig.KEY_BLOCK_APK);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionFolders)));
        addToggle(items, UMessageConfig.KEY_FOLDER_ICONS);
        addToggle(items, UMessageConfig.KEY_HIDE_FOLDER_TABS);
        addToggle(items, UMessageConfig.KEY_ADMIN_FOLDERS);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionStories)));
        addToggle(items, UMessageConfig.KEY_STORIES_ANONYMOUS);
        addToggle(items, UMessageConfig.KEY_STORIES_HIDDEN);
        addToggle(items, UMessageConfig.KEY_STORIES_DOWNLOAD);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionReminder)));
        addToggle(items, UMessageConfig.KEY_UNREAD_REMINDER);
        items.add(UItem.asButton(REMINDER_DELAY, LocaleController.getString(R.string.UMessageReminderDelay), formatDelay(UMessageConfig.getReminderDelayMinutes()))
                .setEnabled(UMessageConfig.isUnreadReminder()));
        items.add(UItem.asButton(REMINDER_SOUND, LocaleController.getString(R.string.UMessageReminderSound), getSoundName(UMessageConfig.getReminderSound()))
                .setEnabled(UMessageConfig.isUnreadReminder()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionAutoReply)));
        addToggle(items, UMessageConfig.KEY_AUTO_REPLY);
        items.add(UItem.asButton(AUTO_REPLY_TEXT, LocaleController.getString(R.string.UMessageAutoReplyText)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionConfirm)));
        addToggle(items, UMessageConfig.KEY_CONFIRM_STICKER);
        addToggle(items, UMessageConfig.KEY_CONFIRM_VOICE);
        addToggle(items, UMessageConfig.KEY_CONFIRM_GIF);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionMessageField)));
        addToggle(items, UMessageConfig.KEY_VOICE_INPUT);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionVideoMessages)));
        addToggle(items, UMessageConfig.KEY_ROUND_FRONT_CAMERA);
        items.add(UItem.asButton(ROUND_EFFECTS, LocaleController.getString(R.string.UMessageRoundEffects), getRoundEffectsValue()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionChannels)));
        addToggle(items, UMessageConfig.KEY_AUTO_APPROVE);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionDownload)));
        addToggle(items, UMessageConfig.KEY_STOP_AUTODOWNLOAD);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageAbout)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                DESIGN,
                IconBackgroundColors.BLUE_ALT.top,
                IconBackgroundColors.BLUE_ALT.bottom,
                R.drawable.settings_chat,
                LocaleController.getString(R.string.UMessageDesign),
                null,
                LocaleController.getString(R.string.UMessageDesignMonochrome)
        ));
        items.add(SettingsActivity.SettingCell.Factory.of(
                BASED_ON,
                IconBackgroundColors.BLUE_LIGHT.top,
                IconBackgroundColors.BLUE_LIGHT.bottom,
                R.drawable.settings_features,
                LocaleController.getString(R.string.UMessageBasedOn),
                null,
                "Telegram"
        ));
        items.add(UItem.asCustomShadow(versionView));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case BLOCK_ADS:
            case USE_PROXY:
            case NEARBY_SHARE:
            case UM_PREMIUM: {
                final UMessageFeatureActivity.Switch sw = item.id == BLOCK_ADS ? UMessageFeatureActivity.BLOCK_ADS
                        : item.id == USE_PROXY ? UMessageFeatureActivity.USE_PROXY
                        : item.id == UM_PREMIUM ? UMessageFeatureActivity.UM_PREMIUM : UMessageFeatureActivity.NEARBY_SHARE;
                if (isSwitchClick(view, x)) {
                    sw.toggle(this, () -> listView.adapter.update(true));
                } else {
                    presentFragment(new UMessageFeatureActivity(sw));
                }
                break;
            }
            case SPEECH_ENABLED:
                org.telegram.messenger.UMessageUzbekSpeech.setEnabled(!org.telegram.messenger.UMessageUzbekSpeech.isEnabled());
                listView.adapter.update(true);
                break;
            case SPEECH_MODEL:
                onSpeechModelClick();
                break;
            case TEMPLATES:
                presentFragment(new UMessageTemplatesActivity());
                break;
            case FOCUS:
                presentFragment(new UMessageFocusActivity());
                break;
            case AUTO_REPLY_TEXT:
                showAutoReplyEditor();
                break;
            case REMINDER_DELAY:
                showReminderDelayPicker();
                break;
            case REMINDER_SOUND:
                showReminderSoundPicker();
                break;
            case STRANGER_CHATS:
                presentFragment(new UMessageChatsActivity(UMessageChatsActivity.MODE_STRANGERS));
                break;
            case HIDDEN_CHATS:
                UMessageHiddenChats.open(this);
                break;
            case HIDDEN_FOLDERS:
                showHiddenFoldersPicker();
                break;
            case HIDDEN_PASSWORD:
                UMessageHiddenChats.changePassword(this);
                break;
            case HIDDEN_ACCESS:
                showHiddenAccessPicker();
                break;
            case ACCOUNT_AUTO_SWITCH:
                showAccountAutoSwitchEditor();
                break;
            case ROUND_EFFECTS:
                presentFragment(new UMessageRoundEffectsActivity());
                break;
            case PRIVACY_GUARD:
                presentFragment(new PrivacyGuardSettingsActivity());
                break;
            default:
                if (item.id >= TOGGLE_BASE && item.id < TOGGLE_BASE + TOGGLES.length) {
                    final int index = item.id - TOGGLE_BASE;
                    if (isSwitchClick(view, x)) {
                        toggle(this, TOGGLES[index], () -> listView.adapter.update(true));
                    } else {
                        presentFragment(new UMessageFeatureActivity(TOGGLES[index], TOGGLE_TITLES[index], TOGGLE_INFOS[index]));
                    }
                }
                break;
        }
    }

    private void showHiddenFoldersPicker() {
        final ArrayList<MessagesController.DialogFilter> hidden = new ArrayList<>();
        for (MessagesController.DialogFilter filter : MessagesController.getInstance(currentAccount).getDialogFilters()) {
            if (!filter.isDefault() && UMessageConfig.getHiddenFolders(currentAccount).contains(filter.id)) {
                hidden.add(filter);
            }
        }
        if (hidden.isEmpty()) {
            showDialog(new AlertDialog.Builder(getContext(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.UMessageHiddenFolders))
                    .setMessage(LocaleController.getString(R.string.UMessageHiddenFoldersEmpty))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .create());
            return;
        }
        CharSequence[] names = new CharSequence[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) {
            names[i] = hidden.get(i).name;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageHiddenFolders));
        builder.setItems(names, (dialog, which) -> {
            UMessageConfig.setFolderHidden(currentAccount, hidden.get(which).id, false);
            listView.adapter.update(true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_unhide, LocaleController.getString(R.string.UMessageShowFolder)).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** The switch after the divider flips the option, the rest of the row opens its page. */
    static boolean isSwitchClick(View view, float x) {
        return LocaleController.isRTL ? x <= dp(76) : x >= view.getMeasuredWidth() - dp(76);
    }

    private static String getRoundEffectsValue() {
        final int preset = RoundVideoEffects.getConfiguredPreset();
        final String name = RoundVideoEffects.getPresetName(preset);
        if (preset == RoundVideoEffects.PRESET_ORIGINAL) {
            return name;
        }
        return name + " " + RoundVideoEffects.getConfiguredIntensity() + "%";
    }

    private void addToggle(ArrayList<UItem> items, String key) {
        final int index = indexOf(key);
        items.add(UItem.asButtonCheck(TOGGLE_BASE + index, LocaleController.getString(TOGGLE_TITLES[index]), LocaleController.getString(TOGGLE_INFOS[index]))
                .setChecked(UMessageConfig.get(key)));
    }

    private static int indexOf(String key) {
        for (int i = 0; i < TOGGLES.length; i++) {
            if (TOGGLES[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** Flips a switch, including the extra work some of them need, then runs {@code done}. */
    static void toggle(BaseFragment fragment, String key, Runnable done) {
        final boolean enable = !UMessageConfig.get(key);
        if (UMessageConfig.KEY_ADMIN_FOLDERS.equals(key)) {
            if (enable) {
                UMessageAdminFolders.enable(fragment, created -> {
                    if (created) {
                        UMessageConfig.set(key, true);
                    } else {
                        UMessageAdminFolders.showLimitError(fragment);
                    }
                    done.run();
                });
                return;
            }
            UMessageAdminFolders.disable(fragment.getCurrentAccount());
        }
        UMessageConfig.set(key, enable);
        done.run();
    }

    private void showAccountAutoSwitchEditor() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final int[] accounts = new int[2];
        int count = 0;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT && count < accounts.length; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                accounts[count++] = account;
            }
        }
        if (count < 2) {
            AlertDialog.Builder error = new AlertDialog.Builder(context, getResourceProvider());
            error.setTitle(LocaleController.getString(R.string.UMessageAccountAutoSwitch));
            error.setMessage(LocaleController.getString(R.string.UMessageAccountAutoSwitchNeedTwo));
            error.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(error.create());
            return;
        }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(4), dp(24), 0);

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.UMessageAccountAutoSwitchInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        info.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.bottomMargin = dp(16);
        container.addView(info, infoParams);

        EditTextBoldCursor[] fields = new EditTextBoldCursor[2];
        for (int i = 0; i < fields.length; i++) {
            TextView label = new TextView(context);
            String name = UserObject.getUserName(UserConfig.getInstance(accounts[i]).getCurrentUser());
            label.setText(LocaleController.formatString(R.string.UMessageAccountAutoSwitchAccount, i + 1, name));
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setTypeface(AndroidUtilities.bold());
            label.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            container.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            fields[i] = createAccountPinField(context, i == fields.length - 1);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            fieldParams.bottomMargin = dp(i == fields.length - 1 ? 4 : 14);
            container.addView(fields[i], fieldParams);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageAccountAutoSwitch));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (UMessageConfig.hasAccountSwitchPins()) {
            builder.setNeutralButton(LocaleController.getString(R.string.Disable), (dialog, which) -> {
                UMessageConfig.clearAccountSwitchPins();
                listView.adapter.update(true);
            });
        }
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView saveButton = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> {
                String firstPin = fields[0].getText().toString();
                String secondPin = fields[1].getText().toString();
                if (firstPin.length() != 4) {
                    fields[0].setError(LocaleController.getString(R.string.UMessageAccountAutoSwitchFourDigits));
                    fields[0].requestFocus();
                    return;
                }
                if (secondPin.length() != 4) {
                    fields[1].setError(LocaleController.getString(R.string.UMessageAccountAutoSwitchFourDigits));
                    fields[1].requestFocus();
                    return;
                }
                if (firstPin.equals(secondPin)) {
                    fields[1].setError(LocaleController.getString(R.string.UMessageAccountAutoSwitchDifferent));
                    fields[1].requestFocus();
                    return;
                }
                UMessageConfig.setAccountSwitchPins(accounts[0], firstPin, accounts[1], secondPin);
                AndroidUtilities.hideKeyboard(fields[1]);
                dialog.dismiss();
                listView.adapter.update(true);
            });
        }
        fields[0].requestFocus();
        AndroidUtilities.showKeyboard(fields[0]);
    }

    private EditTextBoldCursor createAccountPinField(Context context, boolean last) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.UMessageAccountAutoSwitchPinHint));
        field.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setLineColors(getThemedColor(Theme.key_dialogInputField), getThemedColor(Theme.key_dialogInputFieldActivated), getThemedColor(Theme.key_text_RedBold));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        field.setSingleLine(true);
        field.setImeOptions(last ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        return field;
    }

    private void showAutoReplyEditor() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setText(UMessageConfig.getAutoReplyText());
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.UMessageAutoReplyText));
        field.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setLineColors(getThemedColor(Theme.key_dialogInputField), getThemedColor(Theme.key_dialogInputFieldActivated), getThemedColor(Theme.key_text_RedBold));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMaxLines(6);
        field.setBackground(null);
        field.setPadding(0, dp(6), 0, dp(6));
        FrameLayout container = new FrameLayout(context);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 8, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageAutoReplyText));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String text = field.getText().toString().trim();
            if (!TextUtils.isEmpty(text)) {
                UMessageConfig.setAutoReplyText(text);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            field.setSelection(field.length());
            AndroidUtilities.showKeyboard(field);
        });
        showDialog(dialog);
    }

    private static final int[] ACCESS = {
            UMessageConfig.HIDDEN_ACCESS_HIDE_ICON,
            UMessageConfig.HIDDEN_ACCESS_NEW_CHAT_LONG_PRESS,
            UMessageConfig.HIDDEN_ACCESS_EDIT_LONG_PRESS
    };

    private String getAccessName(int access) {
        switch (access) {
            case UMessageConfig.HIDDEN_ACCESS_NEW_CHAT_LONG_PRESS:
                return LocaleController.getString(R.string.UMessageHiddenAccessNewChat);
            case UMessageConfig.HIDDEN_ACCESS_EDIT_LONG_PRESS:
                return LocaleController.getString(R.string.UMessageHiddenAccessEdit);
            default:
                return LocaleController.getString(R.string.UMessageHiddenAccessIcon);
        }
    }

    private void showHiddenAccessPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] titles = new CharSequence[ACCESS.length];
        for (int i = 0; i < ACCESS.length; i++) {
            titles[i] = getAccessName(ACCESS[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageHiddenAccess));
        builder.setItems(titles, (dialog, which) -> {
            UMessageConfig.setHiddenAccess(ACCESS[which]);
            listView.adapter.update(true);
        });
        showDialog(builder.create());
    }

    private String formatDelay(int minutes) {
        return LocaleController.formatPluralString("Minutes", minutes);
    }

    private void showReminderDelayPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final int[] delays = UMessageConfig.REMINDER_DELAYS;
        final CharSequence[] titles = new CharSequence[delays.length];
        for (int i = 0; i < delays.length; i++) {
            titles[i] = formatDelay(delays[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageReminderDelay));
        builder.setItems(titles, (dialog, which) -> {
            UMessageConfig.setReminderDelayMinutes(delays[which]);
            listView.adapter.update(true);
        });
        showDialog(builder.create());
    }

    private static final int[] SOUNDS = {
            UMessageConfig.REMINDER_SOUND_NOTIFICATION,
            UMessageConfig.REMINDER_SOUND_ALARM,
            UMessageConfig.REMINDER_SOUND_RINGTONE,
            UMessageConfig.REMINDER_SOUND_NONE
    };

    private String getSoundName(int sound) {
        switch (sound) {
            case UMessageConfig.REMINDER_SOUND_ALARM:
                return LocaleController.getString(R.string.UMessageSoundAlarm);
            case UMessageConfig.REMINDER_SOUND_RINGTONE:
                return LocaleController.getString(R.string.UMessageSoundRingtone);
            case UMessageConfig.REMINDER_SOUND_NONE:
                return LocaleController.getString(R.string.UMessageSoundNone);
            default:
                return LocaleController.getString(R.string.UMessageSoundNotification);
        }
    }

    private void showReminderSoundPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] titles = new CharSequence[SOUNDS.length];
        for (int i = 0; i < SOUNDS.length; i++) {
            titles[i] = getSoundName(SOUNDS[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageReminderSound));
        builder.setItems(titles, (dialog, which) -> {
            UMessageConfig.setReminderSound(SOUNDS[which]);
            listView.adapter.update(true);
        });
        showDialog(builder.create());
    }

    private void updateActionBarVisible(boolean animated) {
        if (listView == null || actionBarBackground == null || actionBar.getTitlesContainer() == null) {
            return;
        }
        boolean visible = false;
        if (listView.getChildCount() > 0) {
            final View firstChild = listView.getChildAt(0);
            visible = listView.getChildAdapterPosition(firstChild) > 0
                    || firstChild.getY() + firstChild.getHeight() < actionBar.getHeight();
        }
        if (actionBarVisible == visible && animated) {
            return;
        }
        actionBarVisible = visible;
        if (actionBarVisibleAnimator != null) {
            actionBarVisibleAnimator.cancel();
            actionBarVisibleAnimator = null;
        }
        final float target = visible ? 1f : 0f;
        if (!animated) {
            actionBar.getTitlesContainer().setAlpha(target);
            actionBarBackground.setAlpha(target);
            return;
        }
        actionBarVisibleAnimator = ValueAnimator.ofFloat(actionBar.getTitlesContainer().getAlpha(), target);
        actionBarVisibleAnimator.addUpdateListener(animation -> {
            final float value = (float) animation.getAnimatedValue();
            actionBar.getTitlesContainer().setAlpha(value);
            actionBarBackground.setAlpha(value);
        });
        actionBarVisibleAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        actionBarVisibleAnimator.setDuration(420);
        actionBarVisibleAnimator.start();
    }

    private final Runnable speechProgress = new Runnable() {
        @Override
        public void run() {
            if (listView != null) listView.adapter.update(false);
            if (org.telegram.messenger.UMessageUzbekSpeech.isDownloading()) AndroidUtilities.runOnUIThread(this, 700);
        }
    };

    /** Download / cancel / delete the offline speech model. */
    private void onSpeechModelClick() {
        if (org.telegram.messenger.UMessageUzbekSpeech.isDownloading()) {
            org.telegram.messenger.UMessageUzbekSpeech.cancelDownload();
            AndroidUtilities.runOnUIThread(speechProgress, 500);
        } else if (org.telegram.messenger.UMessageUzbekSpeech.isReady()) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(LocaleController.getString(R.string.UMessageSpeechModelDelete));
            b.setMessage(LocaleController.getString(R.string.UMessageSpeechModelDeleteInfo));
            b.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                org.telegram.messenger.UMessageUzbekSpeech.delete();
                listView.adapter.update(true);
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b.create());
        } else {
            org.telegram.messenger.UMessageUzbekSpeech.download(new org.telegram.messenger.UMessageUzbekSpeech.DownloadListener() {
                @Override
                public void onProgress(long done, long total) {
                }

                @Override
                public void onDone(boolean ok, String error) {
                    if (listView != null) listView.adapter.update(true);
                    if (!ok && error != null && !"cancelled".equals(error)) {
                        BulletinFactory.of(UMessageSettingsActivity.this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageSpeechModelFailed) + " (" + error + ")").show();
                    }
                }
            });
            AndroidUtilities.runOnUIThread(speechProgress, 300);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (actionBarVisibleAnimator != null) {
            actionBarVisibleAnimator.cancel();
            actionBarVisibleAnimator = null;
        }
        super.onFragmentDestroy();
    }
}
