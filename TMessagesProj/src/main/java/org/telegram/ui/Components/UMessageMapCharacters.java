package org.telegram.ui.Components;

import android.text.TextUtils;

import org.telegram.messenger.MediaDataController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Friend Map characters are Telegram's own animated emoji (the AnimatedEmojies set, Lottie).
 * A character id is "tg:" + emoji; ids saved by older builds ("car", "dog", ...) map to their emoji.
 */
public final class UMessageMapCharacters {

    private static final String PREFIX = "tg:";
    private static final String SET = "AnimatedEmojies";
    private static final HashMap<String, String> LEGACY = new HashMap<>();

    static {
        String[][] legacy = {
                {"car", "🚗"}, {"suv", "🚙"}, {"taxi", "🚕"}, {"racing", "🏎"}, {"moto", "🏍"}, {"bike", "🚲"},
                {"scooter", "🛴"}, {"walker", "🚶"}, {"runner", "🏃"}, {"dog", "🐕"}, {"cat", "🐈"}, {"horse", "🐎"},
                {"tiger", "🐅"}, {"penguin", "🐧"}, {"rabbit", "🐇"}, {"trex", "🦖"}, {"turtle", "🐢"}, {"panda", "🐼"},
                {"fox", "🦊"}, {"robot", "🤖"}, {"bus", "🚌"}, {"police", "🚓"}, {"ambulance", "🚑"}, {"fire", "🚒"},
                {"tractor", "🚜"}, {"truck", "🚚"}, {"plane", "✈"}, {"heli", "🚁"}, {"rocket", "🚀"}, {"ufo", "🛸"},
                {"boat", "⛵"}, {"cyclist", "🚴"}, {"surfer", "🏄"}, {"dancer", "💃"}, {"ninja", "🥷"}, {"astronaut", "🧑‍🚀"},
                {"ghost", "👻"}, {"alien", "👽"}, {"snowman", "⛄"}, {"dragon", "🐉"}, {"snail", "🐌"}, {"elephant", "🐘"},
                {"camel", "🐫"}, {"duck", "🦆"}, {"chicken", "🐔"}, {"owl", "🦉"}, {"butterfly", "🦋"}, {"bee", "🐝"},
                {"dolphin", "🐬"}, {"shark", "🦈"}, {"whale", "🐳"}, {"octopus", "🐙"}, {"unicorn", "🦄"}, {"lion", "🦁"},
                {"bear", "🐻"}, {"koala", "🐨"}, {"monkey", "🐒"}, {"frog", "🐸"}, {"wolf", "🐺"}, {"puppy", "🐶"},
                {"kitten", "🐱"}, {"pig", "🐷"},
        };
        for (String[] l : legacy) LEGACY.put(l[0], l[1]);
    }

    private UMessageMapCharacters() {
    }

    public static String idFor(String emoji) {
        return PREFIX + emoji;
    }

    /** The emoji behind a character id, or null for none / unknown. */
    public static String emojiOf(String id) {
        if (TextUtils.isEmpty(id)) return null;
        if (id.startsWith(PREFIX)) return id.substring(PREFIX.length());
        return LEGACY.get(id);
    }

    /** The animated (Lottie) document for a character, or null while the set is not loaded. */
    public static TLRPC.Document document(int account, String id) {
        String emoji = emojiOf(id);
        if (emoji == null) return null;
        MediaDataController mdc = MediaDataController.getInstance(account);
        TLRPC.Document doc = mdc.getEmojiAnimatedSticker(emoji);
        if (doc == null) mdc.checkStickers(MediaDataController.TYPE_EMOJI);
        return doc;
    }

    /** Every emoji Telegram has an animation for, in the set's own order (empty until it loads). */
    public static ArrayList<String> allAnimated(int account) {
        ArrayList<String> out = new ArrayList<>();
        MediaDataController mdc = MediaDataController.getInstance(account);
        ArrayList<TLRPC.TL_messages_stickerSet> sets = mdc.getStickerSets(MediaDataController.TYPE_EMOJI);
        for (int a = 0; a < sets.size(); a++) {
            TLRPC.TL_messages_stickerSet set = sets.get(a);
            if (set.set == null || !SET.equals(set.set.short_name)) continue;
            for (int b = 0; b < set.packs.size(); b++) {
                TLRPC.TL_stickerPack pack = set.packs.get(b);
                if (!pack.documents.isEmpty() && !TextUtils.isEmpty(pack.emoticon) && !out.contains(pack.emoticon)) {
                    out.add(pack.emoticon);
                }
            }
        }
        if (out.isEmpty()) mdc.checkStickers(MediaDataController.TYPE_EMOJI);
        return out;
    }
}
