package org.telegram.ui.Components;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.LinkedHashSet;

/** Shows only profile data already exposed to the current Telegram account. */
public final class UMessagePublicProfileInfo {

    private UMessagePublicProfileInfo() {
    }

    public static void show(BaseFragment fragment, TLRPC.User user, TLRPC.UserFull userFull) {
        if (fragment == null || fragment.getParentActivity() == null || user == null) {
            return;
        }

        String username = UserObject.getPublicUsername(user);
        StringBuilder message = new StringBuilder();
        append(message, LocaleController.getString(R.string.UMessagePublicInfoName), UserObject.getUserName(user));
        append(message, LocaleController.getString(R.string.UMessagePublicInfoUsername),
                TextUtils.isEmpty(username) ? LocaleController.getString(R.string.UMessagePublicInfoUnavailable) : "@" + username);
        append(message, LocaleController.getString(R.string.UMessagePublicInfoId), String.valueOf(user.id));
        if (!TextUtils.isEmpty(user.phone)) {
            append(message, LocaleController.getString(R.string.UMessagePublicInfoPhone), "+" + user.phone);
        }
        if (userFull != null && !TextUtils.isEmpty(userFull.about)) {
            append(message, LocaleController.getString(R.string.UMessagePublicInfoBio), userFull.about);
        }
        if (userFull != null) {
            append(message, LocaleController.getString(R.string.UMessagePublicInfoCommonChats), String.valueOf(userFull.common_chats_count));
        }

        if (TextUtils.isEmpty(username)) {
            showResult(fragment, message.toString(), LocaleController.getString(R.string.UMessagePublicInfoNoUsername));
            return;
        }

        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, fragment.getResourceProvider());
        progress.setCanCancel(true);
        fragment.showDialog(progress);

        TLRPC.TL_channels_searchPosts request = new TLRPC.TL_channels_searchPosts();
        request.flags |= 2;
        request.query = "@" + username;
        request.limit = 50;
        request.offset_peer = new TLRPC.TL_inputPeerEmpty();
        ConnectionsManager.getInstance(fragment.getCurrentAccount()).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                showResult(fragment, message.toString(), LocaleController.getString(R.string.UMessagePublicInfoSearchError));
                return;
            }

            TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
            MessagesController.getInstance(fragment.getCurrentAccount()).putUsers(result.users, false);
            MessagesController.getInstance(fragment.getCurrentAccount()).putChats(result.chats, false);
            LinkedHashSet<String> channels = new LinkedHashSet<>();
            LinkedHashSet<String> people = new LinkedHashSet<>();
            for (TLRPC.Chat chat : result.chats) {
                String handle = ChatObject.getPublicUsername(chat);
                if (!TextUtils.isEmpty(handle)) {
                    channels.add(chat.title + " (@" + handle + ")");
                }
            }
            for (TLRPC.User foundUser : result.users) {
                String handle = UserObject.getPublicUsername(foundUser);
                if (foundUser.id != user.id && !TextUtils.isEmpty(handle)) {
                    people.add(UserObject.getUserName(foundUser) + " (@" + handle + ")");
                }
            }
            append(message, LocaleController.getString(R.string.UMessagePublicInfoPublicPosts), String.valueOf(result.messages.size()));
            appendList(message, LocaleController.getString(R.string.UMessagePublicInfoChannels), channels);
            appendList(message, LocaleController.getString(R.string.UMessagePublicInfoPeople), people);
            showResult(fragment, message.toString(), LocaleController.getString(R.string.UMessagePublicInfoPublicOnly));
        }));
    }

    private static void append(StringBuilder result, String label, String value) {
        if (result.length() > 0) {
            result.append("\n\n");
        }
        result.append(label).append(": ").append(value);
    }

    private static void appendList(StringBuilder result, String label, LinkedHashSet<String> values) {
        if (values.isEmpty()) {
            return;
        }
        if (result.length() > 0) {
            result.append("\n\n");
        }
        result.append(label).append(":");
        int count = 0;
        for (String value : values) {
            if (count++ == 10) {
                result.append("\n…");
                break;
            }
            result.append("\n• ").append(value);
        }
    }

    private static void showResult(BaseFragment fragment, String message, String note) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessagePublicInfo));
        builder.setMessage(message + "\n\n" + note);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        fragment.showDialog(builder.create());
    }
}
