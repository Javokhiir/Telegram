package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;

import org.telegram.tgnet.TLRPC;

/** U message specific settings (stored apart from Telegram's own config). */
public class UMessageConfig {

    private static final String PREFS = "umessage";
    public static final String KEY_BLOCK_ADS = "block_ads";

    private static volatile Boolean adsBlocked;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Sponsored messages, sponsored search results and video ads are not requested (on by default). */
    public static boolean isAdsBlocked() {
        Boolean value = adsBlocked;
        if (value == null) {
            value = adsBlocked = prefs().getBoolean(KEY_BLOCK_ADS, true);
        }
        return value;
    }

    public static void setAdsBlocked(boolean blocked) {
        adsBlocked = blocked;
        prefs().edit().putBoolean(KEY_BLOCK_ADS, blocked).apply();
    }

    /* "Use U Message Proxy": fall back to built-in proxies when direct connection fails (off by default) */

    /** Preview/page key of the U message Premium switch (value lives in UMessagePremiumController). */
    public static final String KEY_UM_PREMIUM = "um_premium";
    public static final String KEY_PROXY_FALLBACK = "proxy_fallback";

    private static volatile Boolean proxyFallback;

    public static boolean isProxyFallbackEnabled() {
        Boolean value = proxyFallback;
        if (value == null) {
            value = proxyFallback = prefs().getBoolean(KEY_PROXY_FALLBACK, false);
        }
        return value;
    }

    public static void setProxyFallbackEnabled(boolean enabled) {
        proxyFallback = enabled;
        prefs().edit().putBoolean(KEY_PROXY_FALLBACK, enabled).apply();
        UMessageProxyManager.onSettingChanged();
    }

    /* Quick reply templates: typing "/shortcut " in a chat expands to the template text */

    private static final String KEY_TEMPLATES = "templates";

    public static class Template {
        public String shortcut;
        public String text;

        public Template(String shortcut, String text) {
            this.shortcut = shortcut;
            this.text = text;
        }
    }

    private static ArrayList<Template> templates;

    public static ArrayList<Template> getTemplates() {
        if (templates == null) {
            templates = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(prefs().getString(KEY_TEMPLATES, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    templates.add(new Template(o.getString("s"), o.getString("t")));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return templates;
    }

    public static void saveTemplates() {
        JSONArray array = new JSONArray();
        try {
            for (Template t : getTemplates()) {
                array.put(new JSONObject().put("s", t.shortcut).put("t", t.text));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().putString(KEY_TEMPLATES, array.toString()).apply();
    }

    public static Template findTemplate(String shortcut) {
        for (Template t : getTemplates()) {
            if (t.shortcut.equalsIgnoreCase(shortcut)) {
                return t;
            }
        }
        return null;
    }

    /* Nearby share: exchange contacts with U message users around via Nearby Connections */

    public static final String KEY_NEARBY_SHARE = "nearby_share";

    public static boolean isNearbyShareEnabled() {
        return prefs().getBoolean(KEY_NEARBY_SHARE, true);
    }

    public static void setNearbyShareEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_NEARBY_SHARE, enabled).apply();
    }

    /* Focus mode: during the chosen hours only chats from the allowed folders notify */

    private static final String KEY_FOCUS_ENABLED = "focus_enabled";
    private static final String KEY_FOCUS_START = "focus_start";
    private static final String KEY_FOCUS_END = "focus_end";
    private static final String KEY_FOCUS_FOLDERS = "focus_folders";

    public static boolean isFocusEnabled() {
        return prefs().getBoolean(KEY_FOCUS_ENABLED, false);
    }

    public static void setFocusEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_FOCUS_ENABLED, enabled).apply();
    }

    /** Minutes since midnight. */
    public static int getFocusStart() {
        return prefs().getInt(KEY_FOCUS_START, 9 * 60);
    }

    public static int getFocusEnd() {
        return prefs().getInt(KEY_FOCUS_END, 18 * 60);
    }

    public static void setFocusHours(int startMinutes, int endMinutes) {
        prefs().edit().putInt(KEY_FOCUS_START, startMinutes).putInt(KEY_FOCUS_END, endMinutes).apply();
    }

    public static HashSet<Integer> getFocusFolders() {
        HashSet<Integer> result = new HashSet<>();
        for (String id : prefs().getString(KEY_FOCUS_FOLDERS, "").split(",")) {
            try {
                if (!id.isEmpty()) result.add(Integer.parseInt(id));
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    public static void setFocusFolders(HashSet<Integer> folderIds) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : folderIds) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs().edit().putString(KEY_FOCUS_FOLDERS, sb.toString()).apply();
    }

    /** True while focus mode is on and the current time is inside the focus hours (may wrap midnight). */
    public static boolean isFocusActiveNow() {
        if (!isFocusEnabled()) {
            return false;
        }
        final Calendar calendar = Calendar.getInstance();
        final int now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        final int start = getFocusStart(), end = getFocusEnd();
        if (start == end) {
            return true;
        }
        return start < end ? now >= start && now < end : now >= start || now < end;
    }

    /** Whether focus mode silences this dialog now. Mentions and replies to you always come through. */
    public static boolean isSilencedByFocus(int account, long dialogId, boolean mentioned) {
        if (mentioned || !isFocusActiveNow()) {
            return false;
        }
        final HashSet<Integer> allowed = getFocusFolders();
        final MessagesController controller = MessagesController.getInstance(account);
        for (MessagesController.DialogFilter filter : controller.getDialogFilters()) {
            if (allowed.contains(filter.id) && (filter.isDefault() || filter.includesDialog(AccountInstance.getInstance(account), dialogId))) {
                return false;
            }
        }
        return true;
    }

    /* Toggles from the U message settings screen */

    public static final String KEY_STORIES_ANONYMOUS = "stories_anonymous";
    public static final String KEY_STORIES_HIDDEN = "stories_hidden";
    public static final String KEY_STORIES_DOWNLOAD = "stories_download";
    public static final String KEY_SAVE_EDITED = "save_edited";
    public static final String KEY_SAVE_DELETED = "save_deleted";
    public static final String KEY_STOP_AUTODOWNLOAD = "stop_autodownload";
    public static final String KEY_AUTO_REPLY = "auto_reply";
    public static final String KEY_GHOST_MODE = "ghost_mode";
    public static final String KEY_GHOST_BUTTON = "ghost_button";
    public static final String KEY_HIDDEN_CHATS = "hidden_chats";
    public static final String KEY_CONFIRM_STICKER = "confirm_sticker";
    public static final String KEY_CONFIRM_VOICE = "confirm_voice";
    public static final String KEY_CONFIRM_GIF = "confirm_gif";
    public static final String KEY_ROUND_FRONT_CAMERA = "round_front_camera";
    public static final String KEY_VOICE_INPUT = "voice_input";
    public static final String KEY_FOLDER_ICONS = "folder_icons";
    public static final String KEY_HIDE_FOLDER_TABS = "hide_folder_tabs";
    public static final String KEY_ADMIN_FOLDERS = "admin_folders";
    public static final String KEY_AUTO_APPROVE = "auto_approve_requests";
    public static final String KEY_UNREAD_REMINDER = "unread_reminder";
    public static final String KEY_STRANGER_PROTECTION = "stranger_protection";
    public static final String KEY_BLOCK_APK = "block_apk";

    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> flags = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int version;

    /** Changes every time a setting changes, so screens can tell when to refresh. */
    public static int getVersion() {
        return version;
    }

    public static boolean isDefaultOn(String key) {
        return KEY_ROUND_FRONT_CAMERA.equals(key) || KEY_VOICE_INPUT.equals(key);
    }

    public static boolean get(String key) {
        Boolean value = flags.get(key);
        if (value == null) {
            value = prefs().getBoolean(key, isDefaultOn(key));
            flags.put(key, value);
        }
        return value;
    }

    public static void set(String key, boolean value) {
        flags.put(key, value);
        prefs().edit().putBoolean(key, value).apply();
        version++;
        switch (key) {
            case KEY_HIDDEN_CHATS:
            case KEY_STRANGER_PROTECTION:
                refreshDialogs();
                break;
            case KEY_AUTO_REPLY:
                if (value) {
                    UMessageAutomation.resetAutoReplies();
                }
                break;
        }
    }

    public static boolean isAnonymousStories() { return get(KEY_STORIES_ANONYMOUS); }
    public static boolean isStoriesHidden() { return get(KEY_STORIES_HIDDEN); }
    public static boolean isStoriesDownload() { return get(KEY_STORIES_DOWNLOAD); }
    public static boolean isSaveEdited() { return get(KEY_SAVE_EDITED); }
    public static boolean isSaveDeleted() { return get(KEY_SAVE_DELETED); }
    public static boolean isAutoDownloadStopped() { return get(KEY_STOP_AUTODOWNLOAD); }
    public static boolean isAutoReply() { return get(KEY_AUTO_REPLY); }
    public static boolean isGhostMode() { return get(KEY_GHOST_MODE); }
    public static void setGhostMode(boolean value) { set(KEY_GHOST_MODE, value); }
    public static boolean isGhostButton() { return get(KEY_GHOST_BUTTON); }
    public static boolean isHiddenChatsEnabled() { return get(KEY_HIDDEN_CHATS); }
    public static boolean isConfirmSticker() { return get(KEY_CONFIRM_STICKER); }
    public static boolean isConfirmVoice() { return get(KEY_CONFIRM_VOICE); }
    public static boolean isConfirmGif() { return get(KEY_CONFIRM_GIF); }
    public static boolean isRoundFrontCamera() { return get(KEY_ROUND_FRONT_CAMERA); }

    /* Round video effects: defaults applied when recording starts (see RoundVideoEffects) */

    private static final String KEY_ROUND_FILTER = "round_filter";
    private static final String KEY_ROUND_BEAUTY_LEVEL = "round_beauty_level";
    private static final String KEY_ROUND_BLUSH = "round_blush";
    private static final String KEY_ROUND_EFFECT_PRESET = "round_effect_preset";
    private static final String KEY_ROUND_EFFECT_INTENSITY = "round_effect_intensity";

    private static final String KEY_ROUND_MAKEUP = "round_makeup_manual";

    /** U message: the user's own round video makeup as JSON (see MakeupSettings), null for the defaults. */
    public static String getRoundMakeup() {
        return prefs().getString(KEY_ROUND_MAKEUP, null);
    }

    public static void setRoundMakeup(String json) {
        prefs().edit().putString(KEY_ROUND_MAKEUP, json).apply();
    }

    /** Selected all-in-one makeup look. -1 means that the legacy individual controls still need migration. */
    public static int getRoundEffectPreset() {
        return prefs().getInt(KEY_ROUND_EFFECT_PRESET, -1);
    }

    public static void setRoundEffectPreset(int preset) {
        prefs().edit().putInt(KEY_ROUND_EFFECT_PRESET, preset).apply();
    }

    /** Overall strength of the selected makeup look, 0 is off. */
    public static int getRoundEffectIntensity() {
        final SharedPreferences p = prefs();
        if (p.contains(KEY_ROUND_EFFECT_INTENSITY)) {
            return Math.max(0, Math.min(100, p.getInt(KEY_ROUND_EFFECT_INTENSITY, 70)));
        }
        final int legacy = Math.max(getRoundBeauty(), Math.max(getRoundBlush(), getRoundLipstick()));
        return legacy > 0 ? legacy : 70;
    }

    public static void setRoundEffectIntensity(int percent) {
        prefs().edit().putInt(KEY_ROUND_EFFECT_INTENSITY, Math.max(0, Math.min(100, percent))).apply();
    }

    public static int getRoundFilter() {
        return prefs().getInt(KEY_ROUND_FILTER, 0);
    }

    public static void setRoundFilter(int filter) {
        prefs().edit().putInt(KEY_ROUND_FILTER, filter).apply();
    }

    public static final int DEFAULT_ROUND_BEAUTY = 70;

    /** Skin smoothing strength in percent, 0 is off. */
    public static int getRoundBeauty() {
        final SharedPreferences p = prefs();
        if (p.contains(KEY_ROUND_BEAUTY_LEVEL)) {
            return p.getInt(KEY_ROUND_BEAUTY_LEVEL, 0);
        }
        // earlier versions stored a style or an on/off switch
        return p.getInt("round_beauty_style", p.getBoolean("round_beauty", false) ? 1 : 0) > 0 ? DEFAULT_ROUND_BEAUTY : 0;
    }

    public static void setRoundBeauty(int percent) {
        prefs().edit().putInt(KEY_ROUND_BEAUTY_LEVEL, percent).apply();
    }

    /** Blush strength in percent, 0 is off. */
    public static int getRoundBlush() {
        return prefs().getInt(KEY_ROUND_BLUSH, 0);
    }

    public static void setRoundBlush(int percent) {
        prefs().edit().putInt(KEY_ROUND_BLUSH, percent).apply();
    }

    private static final String KEY_ROUND_LIPSTICK = "round_lipstick";
    private static final String KEY_ROUND_LIPSTICK_SHADE = "round_lipstick_shade";
    private static final String KEY_ROUND_LIP_SATURATION = "round_lip_saturation";
    private static final String KEY_ROUND_LIP_BRIGHTNESS = "round_lip_brightness";
    private static final String KEY_ROUND_LIP_SOFTNESS = "round_lip_softness";

    /** Natural look: about half strength. */
    public static final int DEFAULT_ROUND_LIPSTICK = 55;

    /** Lipstick strength in percent, 0 is off. */
    public static int getRoundLipstick() {
        return prefs().getInt(KEY_ROUND_LIPSTICK, 0);
    }

    public static void setRoundLipstick(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIPSTICK, percent).apply();
    }

    /** Index into RoundVideoEffects.LIPSTICK_COLORS. */
    public static int getRoundLipstickShade() {
        return prefs().getInt(KEY_ROUND_LIPSTICK_SHADE, 0);
    }

    public static void setRoundLipstickShade(int shade) {
        prefs().edit().putInt(KEY_ROUND_LIPSTICK_SHADE, shade).apply();
    }

    /** 0..100, 50 is the shade's own saturation. */
    public static int getRoundLipSaturation() {
        return prefs().getInt(KEY_ROUND_LIP_SATURATION, 50);
    }

    public static void setRoundLipSaturation(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIP_SATURATION, percent).apply();
    }

    /** 0..100, 50 keeps the lips' own brightness. */
    public static int getRoundLipBrightness() {
        return prefs().getInt(KEY_ROUND_LIP_BRIGHTNESS, 50);
    }

    public static void setRoundLipBrightness(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIP_BRIGHTNESS, percent).apply();
    }

    /** 0..100, width of the soft lipstick edge. */
    public static int getRoundLipSoftness() {
        return prefs().getInt(KEY_ROUND_LIP_SOFTNESS, 50);
    }

    public static void setRoundLipSoftness(int percent) {
        prefs().edit().putInt(KEY_ROUND_LIP_SOFTNESS, percent).apply();
    }

    public static boolean isVoiceInputButton() { return get(KEY_VOICE_INPUT); }
    public static boolean isFolderIcons() { return get(KEY_FOLDER_ICONS); }
    public static boolean isFolderTabsHidden() { return get(KEY_HIDE_FOLDER_TABS); }
    public static boolean isAdminFolders() { return get(KEY_ADMIN_FOLDERS); }
    public static boolean isAutoApproveRequests() { return get(KEY_AUTO_APPROVE); }
    public static boolean isUnreadReminder() { return get(KEY_UNREAD_REMINDER); }
    public static boolean isStrangerProtection() { return get(KEY_STRANGER_PROTECTION); }
    public static boolean isApkBlocked() { return get(KEY_BLOCK_APK); }

    /* Account auto-switch: two independent PINs mapped to two Telegram accounts. */

    private static final String ACCOUNT_SWITCH_ACCOUNT_PREFIX = "account_switch_account_";
    private static final String ACCOUNT_SWITCH_SALT_PREFIX = "account_switch_salt_";
    private static final String ACCOUNT_SWITCH_HASH_PREFIX = "account_switch_hash_";

    public static boolean hasAccountSwitchPins() {
        int first = prefs().getInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 0, -1);
        int second = prefs().getInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 1, -1);
        return first != second && UserConfig.isValidAccount(first) && UserConfig.isValidAccount(second)
                && prefs().contains(ACCOUNT_SWITCH_HASH_PREFIX + 0)
                && prefs().contains(ACCOUNT_SWITCH_HASH_PREFIX + 1);
    }

    public static void setAccountSwitchPins(int firstAccount, String firstPin, int secondAccount, String secondPin) {
        byte[] firstSalt = new byte[16];
        byte[] secondSalt = new byte[16];
        Utilities.random.nextBytes(firstSalt);
        Utilities.random.nextBytes(secondSalt);
        String firstSaltHex = Utilities.bytesToHex(firstSalt);
        String secondSaltHex = Utilities.bytesToHex(secondSalt);
        prefs().edit()
                .putInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 0, firstAccount)
                .putString(ACCOUNT_SWITCH_SALT_PREFIX + 0, firstSaltHex)
                .putString(ACCOUNT_SWITCH_HASH_PREFIX + 0, hashPassword(firstSaltHex, firstPin))
                .putInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + 1, secondAccount)
                .putString(ACCOUNT_SWITCH_SALT_PREFIX + 1, secondSaltHex)
                .putString(ACCOUNT_SWITCH_HASH_PREFIX + 1, hashPassword(secondSaltHex, secondPin))
                .apply();

        // The first account PIN is also the app passcode, so configuring this feature
        // always enables Telegram's lock screen and keeps exactly the requested flow.
        try {
            SharedConfig.passcodeSalt = new byte[16];
            Utilities.random.nextBytes(SharedConfig.passcodeSalt);
            byte[] pinBytes = firstPin.getBytes("UTF-8");
            byte[] bytes = new byte[32 + pinBytes.length];
            System.arraycopy(SharedConfig.passcodeSalt, 0, bytes, 0, 16);
            System.arraycopy(pinBytes, 0, bytes, 16, pinBytes.length);
            System.arraycopy(SharedConfig.passcodeSalt, 0, bytes, pinBytes.length + 16, 16);
            SharedConfig.passcodeHash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            SharedConfig.passcodeType = SharedConfig.PASSCODE_TYPE_PIN;
            SharedConfig.allowScreenCapture = true;
            SharedConfig.badPasscodeTries = 0;
            SharedConfig.passcodeRetryInMs = 0;
            SharedConfig.saveConfig();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetPasscode);
        } catch (Exception e) {
            FileLog.e(e);
        }
        version++;
    }

    public static void clearAccountSwitchPins() {
        SharedPreferences.Editor editor = prefs().edit();
        for (int i = 0; i < 2; i++) {
            editor.remove(ACCOUNT_SWITCH_ACCOUNT_PREFIX + i)
                    .remove(ACCOUNT_SWITCH_SALT_PREFIX + i)
                    .remove(ACCOUNT_SWITCH_HASH_PREFIX + i);
        }
        editor.apply();
        version++;
    }

    /** Returns the mapped active account, or -1 when this is not an account-switch PIN. */
    public static int findAccountForSwitchPin(String pin) {
        if (pin == null || pin.length() != 4) {
            return -1;
        }
        for (int i = 0; i < 2; i++) {
            int account = prefs().getInt(ACCOUNT_SWITCH_ACCOUNT_PREFIX + i, -1);
            String salt = prefs().getString(ACCOUNT_SWITCH_SALT_PREFIX + i, null);
            String hash = prefs().getString(ACCOUNT_SWITCH_HASH_PREFIX + i, null);
            if (UserConfig.isValidAccount(account) && salt != null && hash != null && hash.equals(hashPassword(salt, pin))) {
                return account;
            }
        }
        return -1;
    }

    /* Auto reply text */

    public static String getAutoReplyText() {
        return prefs().getString("auto_reply_text", LocaleController.getString(R.string.UMessageAutoReplyDefault));
    }

    public static void setAutoReplyText(String text) {
        prefs().edit().putString("auto_reply_text", text).apply();
        UMessageAutomation.resetAutoReplies();
    }

    /* Unread reminder */

    public static final int REMINDER_SOUND_NOTIFICATION = 0;
    public static final int REMINDER_SOUND_ALARM = 1;
    public static final int REMINDER_SOUND_RINGTONE = 2;
    public static final int REMINDER_SOUND_NONE = 3;
    public static final int[] REMINDER_DELAYS = {1, 5, 10, 15, 30, 60};

    public static int getReminderDelayMinutes() {
        return prefs().getInt("reminder_delay", 5);
    }

    public static void setReminderDelayMinutes(int minutes) {
        prefs().edit().putInt("reminder_delay", minutes).apply();
    }

    public static int getReminderSound() {
        return prefs().getInt("reminder_sound", REMINDER_SOUND_NOTIFICATION);
    }

    public static void setReminderSound(int sound) {
        prefs().edit().putInt("reminder_sound", sound).apply();
    }

    /* Hidden chats ("secret chat") and stranger protection */

    private static final HashMap<Integer, HashSet<Long>> hiddenChats = new HashMap<>();
    private static final HashMap<Integer, HashSet<Integer>> hiddenFolders = new HashMap<>();

    public static HashSet<Long> getHiddenChats(int account) {
        synchronized (hiddenChats) {
            HashSet<Long> set = hiddenChats.get(account);
            if (set == null) {
                set = new HashSet<>();
                for (String id : prefs().getString("hidden_chats_" + account, "").split(",")) {
                    try {
                        if (!id.isEmpty()) set.add(Long.parseLong(id));
                    } catch (NumberFormatException ignore) {
                    }
                }
                hiddenChats.put(account, set);
            }
            return set;
        }
    }

    public static void setChatsHidden(int account, ArrayList<Long> dialogIds, boolean hidden) {
        synchronized (hiddenChats) {
            HashSet<Long> set = getHiddenChats(account);
            if (hidden) {
                set.addAll(dialogIds);
            } else {
                set.removeAll(dialogIds);
            }
            StringBuilder sb = new StringBuilder();
            for (Long id : set) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            prefs().edit().putString("hidden_chats_" + account, sb.toString()).apply();
        }
        version++;
        refreshDialogs();
    }

    public static boolean isChatHidden(int account, long dialogId) {
        if (!isHiddenChatsEnabled()) {
            return false;
        }
        synchronized (hiddenChats) {
            return getHiddenChats(account).contains(dialogId);
        }
    }

    public static HashSet<Integer> getHiddenFolders(int account) {
        synchronized (hiddenFolders) {
            HashSet<Integer> set = hiddenFolders.get(account);
            if (set == null) {
                set = new HashSet<>();
                for (String id : prefs().getString("hidden_folders_" + account, "").split(",")) {
                    try {
                        if (!id.isEmpty()) set.add(Integer.parseInt(id));
                    } catch (NumberFormatException ignore) {
                    }
                }
                hiddenFolders.put(account, set);
            }
            return set;
        }
    }

    public static void setFolderHidden(int account, int filterId, boolean hidden) {
        synchronized (hiddenFolders) {
            HashSet<Integer> set = getHiddenFolders(account);
            if (hidden) {
                set.add(filterId);
            } else {
                set.remove(filterId);
            }
            StringBuilder sb = new StringBuilder();
            for (Integer id : set) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            prefs().edit().putString("hidden_folders_" + account, sb.toString()).apply();
        }
        version++;
        refreshDialogs();
    }

    public static boolean isFolderHidden(int account, int filterId) {
        if (!isHiddenChatsEnabled()) {
            return false;
        }
        synchronized (hiddenFolders) {
            return getHiddenFolders(account).contains(filterId);
        }
    }

    /* Hidden chats password: only a salted SHA-256 hash is stored */

    /** A 4 digit PIN entered on the Telegram-style lock screen (older text passwords are not accepted any more). */
    public static boolean hasHiddenPassword() {
        return prefs().contains("hidden_password_hash") && prefs().getBoolean("hidden_password_pin", false);
    }

    /* How the hidden chats are opened */

    public static final int HIDDEN_ACCESS_HIDE_ICON = 0;
    public static final int HIDDEN_ACCESS_NEW_CHAT_LONG_PRESS = 1;
    public static final int HIDDEN_ACCESS_EDIT_LONG_PRESS = 2;

    public static int getHiddenAccess() {
        return prefs().getInt("hidden_access", HIDDEN_ACCESS_HIDE_ICON);
    }

    public static void setHiddenAccess(int access) {
        prefs().edit().putInt("hidden_access", access).apply();
        version++;
    }

    public static boolean isHiddenAccess(int access) {
        return isHiddenChatsEnabled() && getHiddenAccess() == access;
    }

    public static void setHiddenPassword(String password) {
        final byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        final String saltHex = Utilities.bytesToHex(salt);
        prefs().edit()
                .putString("hidden_password_salt", saltHex)
                .putString("hidden_password_hash", hashPassword(saltHex, password))
                .putBoolean("hidden_password_pin", true)
                .apply();
    }

    public static boolean checkHiddenPassword(String password) {
        final String saltHex = prefs().getString("hidden_password_salt", null);
        final String hash = prefs().getString("hidden_password_hash", null);
        return saltHex != null && hash != null && hash.equals(hashPassword(saltHex, password));
    }

    private static String hashPassword(String saltHex, String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(saltHex.getBytes("UTF-8"));
            return Utilities.bytesToHex(digest.digest(password.getBytes("UTF-8")));
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    /** A private chat with someone who is not a contact and you never wrote to. */
    public static boolean isStranger(int account, TLRPC.Dialog dialog) {
        if (dialog == null || dialog.id <= 0 || dialog.read_outbox_max_id > 0) {
            return false;
        }
        try {
            return isStrangerInternal(account, dialog);
        } catch (Exception e) {
            // also called from the notifications queue while the lists may change
            return false;
        }
    }

    private static boolean isStrangerInternal(int account, TLRPC.Dialog dialog) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.User user = controller.getUser(dialog.id);
        if (user == null || user.self || user.contact || user.bot || user.support || user.verified
                || UserObject.isService(user.id) || UserObject.isReplyUser(user) || UserObject.isDeleted(user)) {
            return false;
        }
        final java.util.ArrayList<MessageObject> last = controller.dialogMessage.get(dialog.id);
        if (last != null) {
            for (MessageObject message : last) {
                if (message != null && message.isOut()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isStranger(int account, long dialogId) {
        return isStranger(account, MessagesController.getInstance(account).dialogs_dict.get(dialogId));
    }

    /** Kept out of the chat list: hidden chats and, with stranger protection, chats with strangers. */
    public static boolean isHiddenFromChatList(int account, TLRPC.Dialog dialog) {
        return dialog != null && (isChatHidden(account, dialog.id) || isStrangerProtection() && isStranger(account, dialog));
    }

    public static boolean isNotificationSuppressed(int account, long dialogId) {
        return isChatHidden(account, dialogId) || isStrangerProtection() && isStranger(account, dialogId);
    }

    public static void refreshDialogs() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    MessagesController.getInstance(a).sortDialogs(null);
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                }
            }
        });
    }

    /* Folder icons: the server folder emoticon, otherwise one picked from the folder type */

    public static void setFolderEmoticon(int account, int filterId, String emoticon) {
        final String key = "folder_emoticon_" + account + "_" + filterId;
        if (android.text.TextUtils.isEmpty(emoticon)) {
            prefs().edit().remove(key).apply();
        } else {
            prefs().edit().putString(key, emoticon).apply();
        }
    }

    public static String getFolderIcon(int account, MessagesController.DialogFilter filter) {
        if (filter.isDefault()) {
            return "\uD83D\uDCAC"; // speech balloon
        }
        final String emoticon = prefs().getString("folder_emoticon_" + account + "_" + filter.id, null);
        if (!android.text.TextUtils.isEmpty(emoticon)) {
            return emoticon;
        }
        final int f = filter.flags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        if ((filter.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0) return "\u2705"; // check mark
        if (f == MessagesController.DIALOG_FILTER_FLAG_CONTACTS || f == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
                || f == (MessagesController.DIALOG_FILTER_FLAG_CONTACTS | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS)) return "\uD83D\uDC64"; // person
        if (f == MessagesController.DIALOG_FILTER_FLAG_GROUPS) return "\uD83D\uDC65"; // people
        if (f == MessagesController.DIALOG_FILTER_FLAG_CHANNELS) return "\uD83D\uDCE2"; // loudspeaker
        if (f == MessagesController.DIALOG_FILTER_FLAG_BOTS) return "\uD83E\uDD16"; // robot
        return "\uD83D\uDCC1"; // folder
    }

    /* Confirmation before sending */

    public static void showSendConfirm(org.telegram.ui.ActionBar.BaseFragment fragment, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, int questionRes, Runnable onConfirm) {
        if (fragment == null || fragment.getParentActivity() == null) {
            onConfirm.run();
            return;
        }
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.UMessageConfirmTitle));
        builder.setMessage(LocaleController.getString(questionRes));
        builder.setPositiveButton(LocaleController.getString(R.string.Send), (dialog, which) -> onConfirm.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** Shortcut names: letters, digits and underscore. */
    public static String normalizeShortcut(String shortcut) {
        if (shortcut == null) return "";
        shortcut = shortcut.trim();
        if (shortcut.startsWith("/")) shortcut = shortcut.substring(1);
        return shortcut.replaceAll("[^\\p{L}\\p{N}_]", "");
    }
}
