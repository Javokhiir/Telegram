package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.LongSparseArray;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.HashSet;

/** U message: auto reply and the unread message reminder, both driven by incoming messages. */
public class UMessageAutomation {

    private static final String REMINDER_CHANNEL = "umessage_reminder";

    /** Chats that already got the auto reply ("account_dialogId"); cleared when auto reply is switched on again. */
    private static final HashSet<String> autoReplied = new HashSet<>();
    private static final LongSparseArray<Runnable> reminders = new LongSparseArray<>();

    public static void resetAutoReplies() {
        synchronized (autoReplied) {
            autoReplied.clear();
        }
    }

    /** Called from the notifications queue for every new incoming message (open chats excluded). */
    public static void onIncomingMessage(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isOut()
                || messageObject.isReactionPush || messageObject.isStoryReactionPush || messageObject.isStoryPush
                || messageObject.messageOwner.action != null && !(messageObject.messageOwner.action instanceof TLRPC.TL_messageActionEmpty)) {
            return;
        }
        final long dialogId = messageObject.getDialogId();
        if (dialogId <= 0 || UMessageConfig.isNotificationSuppressed(account, dialogId)) {
            return;
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        if (user == null || user.bot || user.self || user.support || UserObject.isService(user.id) || UserObject.isReplyUser(user)) {
            return;
        }
        if (UMessageConfig.isAutoReply()) {
            sendAutoReply(account, dialogId);
        }
        if (UMessageConfig.isUnreadReminder() && !messageObject.isFcmMessage()) {
            scheduleReminder(account, dialogId, messageObject.getId(), UserObject.getUserName(user), messageObject.messageText);
        }
    }

    private static void sendAutoReply(int account, long dialogId) {
        final String text = UMessageConfig.getAutoReplyText();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        synchronized (autoReplied) {
            if (!autoReplied.add(account + "_" + dialogId)) {
                return;
            }
        }
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId)));
    }

    private static void scheduleReminder(int account, long dialogId, int messageId, String name, CharSequence text) {
        final String preview = text == null ? "" : text.toString();
        AndroidUtilities.runOnUIThread(() -> {
            final long key = dialogId * 8 + account;
            Runnable previous = reminders.get(key);
            if (previous != null) {
                // the reminder counts from the first unread message
                return;
            }
            Runnable reminder = () -> {
                reminders.remove(key);
                if (!UMessageConfig.isUnreadReminder()) {
                    return;
                }
                TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
                if (dialog == null || dialog.read_inbox_max_id >= messageId) {
                    return;
                }
                showReminder(account, dialogId, name, preview);
            };
            reminders.put(key, reminder);
            AndroidUtilities.runOnUIThread(reminder, UMessageConfig.getReminderDelayMinutes() * 60_000L);
        });
    }

    private static void showReminder(int account, long dialogId, String name, String preview) {
        final android.content.Context context = ApplicationLoader.applicationContext;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager manager = (NotificationManager) context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                if (manager.getNotificationChannel(REMINDER_CHANNEL) == null) {
                    NotificationChannel channel = new NotificationChannel(REMINDER_CHANNEL, LocaleController.getString(R.string.UMessageReminder), NotificationManager.IMPORTANCE_HIGH);
                    // the sound is played separately, so the chosen reminder sound applies right away
                    channel.setSound(null, null);
                    manager.createNotificationChannel(channel);
                }
            }
            Intent intent = new Intent(context, LaunchActivity.class);
            intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("userId", dialogId);
            intent.putExtra("currentAccount", account);
            PendingIntent contentIntent = PendingIntent.getActivity(context, (int) dialogId, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, REMINDER_CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(LocaleController.formatString(R.string.UMessageReminderTitle, name))
                    .setContentText(preview)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(preview))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent);
            NotificationManagerCompat.from(context).notify("umessage_reminder", (int) (dialogId ^ (dialogId >>> 32)), builder.build());
            playReminderSound();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void playReminderSound() {
        final int type;
        switch (UMessageConfig.getReminderSound()) {
            case UMessageConfig.REMINDER_SOUND_ALARM:
                type = RingtoneManager.TYPE_ALARM;
                break;
            case UMessageConfig.REMINDER_SOUND_RINGTONE:
                type = RingtoneManager.TYPE_RINGTONE;
                break;
            case UMessageConfig.REMINDER_SOUND_NONE:
                return;
            default:
                type = RingtoneManager.TYPE_NOTIFICATION;
                break;
        }
        try {
            Uri uri = RingtoneManager.getDefaultUri(type);
            Ringtone ringtone = uri == null ? null : RingtoneManager.getRingtone(ApplicationLoader.applicationContext, uri);
            if (ringtone != null) {
                ringtone.play();
                if (type != RingtoneManager.TYPE_NOTIFICATION) {
                    // alarm and ringtone sounds loop, keep them short
                    AndroidUtilities.runOnUIThread(ringtone::stop, 4000);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
