package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/** U message: messages kept after the sender deleted them and the earlier versions of edited messages. */
public class UMessageHistory {

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_history_" + account, Context.MODE_PRIVATE);
    }

    /** Private chats and basic groups share one id space, so they are keyed by 0; channels by -channelId. */
    private static long idSpace(TLRPC.Message message) {
        return message.peer_id != null && message.peer_id.channel_id != 0 ? -message.peer_id.channel_id : 0;
    }

    /* Deleted messages */

    public static void markDeleted(int account, long idSpace, ArrayList<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = prefs(account).edit();
        for (Integer id : ids) {
            editor.putBoolean("d_" + idSpace + "_" + id, true);
        }
        editor.apply();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.umessageMessagesChanged));
    }

    public static boolean isDeleted(int account, MessageObject messageObject) {
        if (messageObject != null && messageObject.umPreviewDeleted) {
            return true;
        }
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0 || !UMessageConfig.isSaveDeleted()) {
            return false;
        }
        return prefs(account).getBoolean("d_" + idSpace(messageObject.messageOwner) + "_" + messageObject.getId(), false);
    }

    /* Edited messages */

    private static String editKey(long dialogId, int id) {
        return "e_" + dialogId + "_" + id;
    }

    /**
     * Called before the edited message is written to the database: the storage queue runs in order,
     * so the row read here is still the previous version.
     */
    public static void recordEdit(int account, TLRPC.Message edited) {
        final long dialogId = MessageObject.getDialogId(edited);
        final int id = edited.id;
        final String newText = edited.message == null ? "" : edited.message;
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId + " AND mid = " + id + " LIMIT 1");
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        TLRPC.Message old = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        if (old != null && old.message != null && !TextUtils.equals(old.message, newText)) {
                            addVersion(account, dialogId, id, old.message, old.edit_date != 0 ? old.edit_date : old.date);
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        });
    }

    private static void addVersion(int account, long dialogId, int id, String text, int date) throws Exception {
        final SharedPreferences prefs = prefs(account);
        final String key = editKey(dialogId, id);
        JSONArray versions = new JSONArray(prefs.getString(key, "[]"));
        versions.put(new JSONObject().put("t", text).put("d", date));
        prefs.edit().putString(key, versions.toString()).apply();
    }

    public static boolean hasEditHistory(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0) {
            return false;
        }
        return prefs(account).contains(editKey(messageObject.getDialogId(), messageObject.getId()));
    }

    private static JSONArray getVersions(int account, MessageObject messageObject) {
        try {
            return new JSONArray(prefs(account).getString(editKey(messageObject.getDialogId(), messageObject.getId()), "[]"));
        } catch (Exception e) {
            FileLog.e(e);
            return new JSONArray();
        }
    }

    /**
     * Shows (or hides again) the earlier versions in italic right under the message text.
     * Returns false when that is not possible: no saved versions or not a text message.
     */
    public static boolean toggleInlineHistory(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.type != MessageObject.TYPE_TEXT || messageObject.messageText == null) {
            return false;
        }
        if (messageObject.umTextBeforeHistory != null) {
            messageObject.messageText = messageObject.umTextBeforeHistory;
            messageObject.umTextBeforeHistory = null;
        } else {
            final JSONArray versions = getVersions(account, messageObject);
            if (versions.length() == 0) {
                return false;
            }
            final android.graphics.Paint.FontMetricsInt fontMetrics = org.telegram.ui.ActionBar.Theme.chat_msgTextPaint.getFontMetricsInt();
            final SpannableStringBuilder text = new SpannableStringBuilder(messageObject.messageText);
            for (int i = versions.length() - 1; i >= 0; i--) {
                final JSONObject version = versions.optJSONObject(i);
                if (version == null) {
                    continue;
                }
                text.append("\n\n");
                final int labelStart = text.length();
                text.append("\u270E ").append(LocaleController.formatDateAudio(version.optInt("d"), true));
                text.setSpan(new android.text.style.RelativeSizeSpan(0.8f), labelStart, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.append("\n");
                final int start = text.length();
                text.append(Emoji.replaceEmoji(version.optString("t"), fontMetrics, false));
                text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new android.text.style.RelativeSizeSpan(0.95f), start, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            messageObject.umTextBeforeHistory = messageObject.messageText;
            messageObject.messageText = text;
        }
        TLRPC.User fromUser = messageObject.isFromUser() ? MessagesController.getInstance(account).getUser(messageObject.messageOwner.from_id.user_id) : null;
        messageObject.generateLayout(fromUser);
        messageObject.forceUpdate = true;
        return true;
    }

    public static void showEditHistory(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || fragment.getParentActivity() == null || messageObject == null) {
            return;
        }
        if (getVersions(fragment.getCurrentAccount(), messageObject).length() == 0) {
            org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.UMessageEditHistoryEmpty)).show();
            return;
        }
        final SpannableStringBuilder text = new SpannableStringBuilder();
        try {
            JSONArray versions = new JSONArray(prefs(fragment.getCurrentAccount()).getString(editKey(messageObject.getDialogId(), messageObject.getId()), "[]"));
            for (int i = 0; i < versions.length(); i++) {
                JSONObject version = versions.getJSONObject(i);
                appendVersion(text, version.optInt("d"), version.optString("t"));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        final TLRPC.Message current = messageObject.messageOwner;
        appendVersion(text, current.edit_date != 0 ? current.edit_date : current.date, current.message);

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageEditHistory));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        fragment.showDialog(builder.create());
    }

    private static void appendVersion(SpannableStringBuilder text, int date, String message) {
        if (text.length() > 0) {
            text.append("\n\n");
        }
        final int start = text.length();
        text.append(LocaleController.formatDateAudio(date, true));
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append("\n").append(message == null ? "" : message);
    }
}
