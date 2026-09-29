package org.telegram.ui.Components;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.net.IDN;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Centralized last-mile checks before an APK or suspicious external link is opened. */
public final class UMessageSecurityGuard {

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Set<String> SHORTENER_HOSTS = new HashSet<>();

    static {
        String[] hosts = {"bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "rebrand.ly", "shorturl.at", "tiny.cc", "ow.ly"};
        for (String host : hosts) {
            SHORTENER_HOSTS.add(host);
        }
    }

    private UMessageSecurityGuard() {
    }

    public static boolean isApk(String fileName, String mimeType) {
        if (APK_MIME.equalsIgnoreCase(mimeType)) {
            return true;
        }
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm");
    }

    public static boolean guardApk(Activity activity, File file, String fileName, String mimeType,
                                   Theme.ResourcesProvider resourcesProvider, Runnable openAction) {
        if (!isApk(fileName, mimeType)) {
            return false;
        }
        // A package must never fall through when no safe UI is available.
        if (activity == null || activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
            return true;
        }

        scanApk(activity, file, fileName, resourcesProvider, openAction);
        return true;
    }

    private static void scanApk(Activity activity, File file, String fileName,
                                Theme.ResourcesProvider resourcesProvider, Runnable openAction) {
        Utilities.globalQueue.postRunnable(() -> {
            ApkReport report = inspectApk(file, fileName);
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                    return;
                }
                if (report.risks.isEmpty()) {
                    openAction.run();
                } else {
                    showApkDanger(activity, report, resourcesProvider, openAction);
                }
            });
        });
    }

    private static void showApkDanger(Activity activity, ApkReport report,
                                      Theme.ResourcesProvider resourcesProvider, Runnable openAction) {
        StringBuilder message = new StringBuilder();
        if (!TextUtils.isEmpty(report.appName)) {
            message.append(LocaleController.formatString(R.string.UMessageApkAppName, report.appName)).append('\n');
        }
        if (!TextUtils.isEmpty(report.packageName)) {
            message.append(LocaleController.formatString(R.string.UMessageApkPackageName, report.packageName)).append('\n');
        }
        if (message.length() > 0) {
            message.append('\n');
        }
        message.append(LocaleController.getString(R.string.UMessageApkRequests)).append("\n\n");
        for (String risk : report.risks) {
            message.append("• ").append(risk).append('\n');
        }
        message.append('\n').append(LocaleController.getString(R.string.UMessageApkDangerFooter));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.UMessageApkDangerTitle));
        builder.setMessage(message.toString());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageOpenDespiteRisk), (dialog, which) -> openAction.run());
        showProtected(builder, activity, resourcesProvider, true);
    }

    @SuppressWarnings("deprecation")
    private static ApkReport inspectApk(File file, String fileName) {
        ApkReport report = new ApkReport();
        if (file == null || !file.isFile() || !file.canRead()) {
            report.add(LocaleController.getString(R.string.UMessageApkUnreadableRisk));
            return report;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apkm")) {
            report.add(LocaleController.getString(R.string.UMessageApkBundleRisk));
            return report;
        }
        try {
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS;
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            if (info == null) {
                report.add(LocaleController.getString(R.string.UMessageApkParseRisk));
                return report;
            }
            report.packageName = info.packageName;
            ApplicationInfo applicationInfo = info.applicationInfo;
            if (applicationInfo != null) {
                applicationInfo.sourceDir = file.getAbsolutePath();
                applicationInfo.publicSourceDir = file.getAbsolutePath();
                try {
                    report.appName = String.valueOf(pm.getApplicationLabel(applicationInfo));
                } catch (Exception ignore) {
                }
            }

            if (info.requestedPermissions != null) {
                for (String permission : info.requestedPermissions) {
                    addPermissionRisk(report, permission);
                }
            }
            if (info.services != null) {
                for (ServiceInfo service : info.services) {
                    if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(service.permission)) {
                        report.add(LocaleController.getString(R.string.UMessageRiskAccessibility));
                    }
                }
            }
            if (info.receivers != null) {
                for (ActivityInfo receiver : info.receivers) {
                    if ("android.permission.BIND_DEVICE_ADMIN".equals(receiver.permission)) {
                        report.add(LocaleController.getString(R.string.UMessageRiskDeviceAdmin));
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            report.add(LocaleController.getString(R.string.UMessageApkParseRisk));
        }
        return report;
    }

    // Only signals that banking trojans and account stealers depend on. Common
    // permissions (camera, storage, location...) are ignored to avoid warning on every app.
    private static void addPermissionRisk(ApkReport report, String permission) {
        if (permission == null) {
            return;
        }
        switch (permission) {
            case Manifest.permission.READ_SMS:
            case Manifest.permission.RECEIVE_SMS:
            case Manifest.permission.SEND_SMS:
                report.add(LocaleController.getString(R.string.UMessageRiskSms));
                break;
            case Manifest.permission.READ_CONTACTS:
            case Manifest.permission.WRITE_CONTACTS:
            case Manifest.permission.GET_ACCOUNTS:
                report.add(LocaleController.getString(R.string.UMessageRiskContacts));
                break;
            case Manifest.permission.READ_CALL_LOG:
            case Manifest.permission.WRITE_CALL_LOG:
            case Manifest.permission.PROCESS_OUTGOING_CALLS:
            case Manifest.permission.CALL_PHONE:
            case Manifest.permission.READ_PHONE_STATE:
                report.add(LocaleController.getString(R.string.UMessageRiskPhone));
                break;
            case "android.permission.SYSTEM_ALERT_WINDOW":
                report.add(LocaleController.getString(R.string.UMessageRiskOverlay));
                break;
        }
    }

    // First domain-looking token in link/button text, e.g. "🌐 Kirish: delivery.yandex.uz".
    private static final Pattern SHOWN_HOST = Pattern.compile("(?:https?://)?((?:[\\p{L}0-9-]+\\.)+\\p{L}{2,})(?![\\p{L}0-9-])", Pattern.CASE_INSENSITIVE);

    /**
     * Returns the host the link text pretends to open when it differs from the real target
     * (e.g. text "delivery.yandex.uz" hiding https://yandexdostavka.lol), otherwise null.
     */
    public static String getMaskedHost(CharSequence shownText, String url) {
        if (TextUtils.isEmpty(shownText) || TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            java.util.regex.Matcher matcher = SHOWN_HOST.matcher(shownText);
            if (!matcher.find()) {
                return null;
            }
            String shownHost = matcher.group(1).toLowerCase(Locale.ROOT);
            String realHost = Uri.parse(url.contains("://") ? url : "https://" + url).getHost();
            if (realHost == null) {
                return null;
            }
            return baseDomain(shownHost).equals(baseDomain(realHost)) ? null : shownHost;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String baseDomain(String host) {
        String ascii;
        try {
            ascii = IDN.toASCII(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            ascii = host.toLowerCase(Locale.ROOT);
        }
        String[] labels = ascii.split("\\.");
        int n = labels.length;
        if (n < 2) {
            return ascii;
        }
        // Second-level public suffixes like co.uk, com.uz keep one more label.
        int keep = n >= 3 && labels[n - 1].length() == 2 && labels[n - 2].length() <= 3 ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - keep; i < n; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    /** Warns when the visible link text hides a different real destination. */
    public static void showMaskedLinkDanger(Context context, String shownHost, String url, Runnable openAction) {
        Activity activity = AndroidUtilities.getActivity(context);
        if (!AndroidUtilities.isContextSafe(activity)) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append(LocaleController.formatString(R.string.UMessageLinkMaskedShown, shownHost)).append("\n");
        message.append(LocaleController.formatString(R.string.UMessageLinkMaskedReal, url)).append("\n\n");
        message.append(LocaleController.getString(R.string.UMessageLinkDangerReasons)).append("\n\n");
        message.append("• ").append(LocaleController.getString(R.string.UMessageLinkRiskMasked)).append('\n');
        for (String risk : getUrlRisks(url)) {
            message.append("• ").append(risk).append('\n');
        }
        message.append('\n').append(LocaleController.getString(R.string.UMessageLinkDangerFooter));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.UMessageLinkDangerTitle));
        builder.setMessage(message.toString());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageOpenDespiteRisk), (dialog, which) -> openAction.run());
        showProtected(builder, activity, null, true);
    }

    public static boolean isSuspiciousUrl(String value) {
        return !getUrlRisks(value).isEmpty();
    }

    /** Returns true when the URL was intercepted (including fail-closed cases). */
    public static boolean guardUrl(Context context, Uri uri, Runnable openAction) {
        if (uri == null) {
            return false;
        }
        final String value = uri.toString();
        final List<String> risks = getUrlRisks(value);
        if (risks.isEmpty()) {
            return false;
        }
        Activity activity = AndroidUtilities.getActivity(context);
        if (!AndroidUtilities.isContextSafe(activity)) {
            Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.getString(R.string.UMessageLinkDangerTitle),
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        showLinkDanger(activity, value, risks, openAction);
        return true;
    }

    private static void showLinkDanger(Context context, String url, List<String> risks, Runnable openAction) {
        StringBuilder message = new StringBuilder();
        message.append(url).append("\n\n");
        message.append(LocaleController.getString(R.string.UMessageLinkDangerReasons)).append("\n\n");
        for (String risk : risks) {
            message.append("• ").append(risk).append('\n');
        }
        message.append('\n').append(LocaleController.getString(R.string.UMessageLinkDangerFooter));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.UMessageLinkDangerTitle));
        builder.setMessage(message.toString());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageOpenDespiteRisk), (dialog, which) -> openAction.run());
        showProtected(builder, context, null, true);
    }

    private static void showProtected(AlertDialog.Builder builder, Context context, Theme.ResourcesProvider resourcesProvider, boolean danger) {
        // The user must accept responsibility before the "open anyway" button unlocks.
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        row.setBackground(Theme.getSelectorDrawable(false));
        CheckBoxSquare consent = new CheckBoxSquare(context, true, resourcesProvider);
        row.addView(consent, LayoutHelper.createLinear(18, 18, 0, 0, 16, 0));
        TextView consentText = new TextView(context);
        consentText.setText(LocaleController.getString(R.string.UMessageRiskConsent));
        consentText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        consentText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        row.addView(consentText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        builder.setView(row);
        AlertDialog dialog = builder.create();
        row.setOnClickListener(v -> {
            consent.setChecked(!consent.isChecked(), true);
            setOpenEnabled(dialog, consent.isChecked());
        });
        dialog.setCanceledOnTouchOutside(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dialog.getWindow() != null) {
            try {
                // Throws SecurityException unless HIDE_OVERLAY_WINDOWS is granted.
                dialog.getWindow().setHideOverlayWindows(true);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        dialog.setOnShowListener(ignored -> {
            int[] buttonTypes = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
            for (int buttonType : buttonTypes) {
                View button = dialog.getButton(buttonType);
                if (button != null) {
                    // Reject taps delivered through a screen overlay on older Android versions.
                    button.setFilterTouchesWhenObscured(true);
                }
            }
            if (danger) {
                TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    button.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
                }
            }
            setOpenEnabled(dialog, consent.isChecked());
        });
        try {
            dialog.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void setOpenEnabled(AlertDialog dialog, boolean enabled) {
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private static List<String> getUrlRisks(String value) {
        ArrayList<String> risks = new ArrayList<>();
        if (TextUtils.isEmpty(value)) {
            return risks;
        }
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                if ("intent".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)
                        || "content".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)
                        || "javascript".equalsIgnoreCase(scheme)) {
                    addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskScheme));
                }
                return risks; // Telegram deep links, phone and mail actions keep their existing handling.
            }
            String host = uri.getHost();
            String authority = uri.getEncodedAuthority();
            if (TextUtils.isEmpty(host)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskMalformed));
                return risks;
            }
            String unicodeHost = host.toLowerCase(Locale.ROOT);
            String asciiHost;
            try {
                asciiHost = IDN.toASCII(unicodeHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskIdn));
                asciiHost = unicodeHost;
            }
            if (!"https".equalsIgnoreCase(scheme)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskHttp));
            }
            if (!TextUtils.isEmpty(authority) && authority.contains("@")) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskUserInfo));
            }
            String lowerValue = value.toLowerCase(Locale.ROOT);
            if (lowerValue.contains("%40") || lowerValue.contains("\\") || containsControlCharacter(value)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskObfuscated));
            }
            if (asciiHost.contains("xn--") || !unicodeHost.equals(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskIdn));
            }
            if (IPV4.matcher(asciiHost).matches() || asciiHost.contains(":")) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskIp));
            }
            if (SHORTENER_HOSTS.contains(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskShortener));
            }
            if (looksLikeTelegramImpersonation(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskTelegramImpersonation));
            }
            if (looksLikeInstagramImpersonation(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskInstagramImpersonation));
            }
            if (!isTrustedHost(asciiHost) && containsSuspiciousHostWord(asciiHost)) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskPhishingWords));
            }
            if (asciiHost.split("\\.").length > 5) {
                addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskSubdomains));
            }
        } catch (Throwable e) {
            addRisk(risks, LocaleController.getString(R.string.UMessageLinkRiskMalformed));
        }
        // A single weak signal is common on legitimate links. Keep the warning
        // for impersonation, obfuscation, or combinations of signals.
        if (risks.size() == 1) {
            String onlyRisk = risks.get(0);
            if (onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskHttp))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskIp))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskIdn))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskShortener))
                    || onlyRisk.equals(LocaleController.getString(R.string.UMessageLinkRiskSubdomains))) {
                risks.clear();
            }
        }
        return risks;
    }

    private static boolean isTrustedHost(String host) {
        return host.equals("t.me") || host.endsWith(".t.me") || host.equals("telegram.org") || host.endsWith(".telegram.org")
                || host.equals("telegram.me") || host.endsWith(".telegram.me") || host.equals("telegra.ph") || host.endsWith(".telegra.ph");
    }

    private static boolean looksLikeTelegramImpersonation(String host) {
        if (isTrustedHost(host)) {
            return false;
        }
        if (host.contains("telegram") || host.startsWith("t-me.") || host.contains(".t-me.") || host.contains("t.me.")) {
            return true;
        }
        String[] labels = host.split("\\.");
        for (String label : labels) {
            String normalized = label.replace("-", "").replace('1', 'l').replace('0', 'o');
            if (normalized.length() >= 6 && editDistance(normalized, "telegram") <= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeInstagramImpersonation(String host) {
        if (host.equals("instagram.com") || host.endsWith(".instagram.com")) {
            return false;
        }
        return host.contains("instagram") && (host.contains("verif") || host.contains("badge")
                || host.contains("login") || host.contains("instagram-com") || host.contains("instagram.com."));
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replace = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), replace);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static boolean containsSuspiciousHostWord(String host) {
        String[] words = {"login", "verify", "verification", "secure", "account", "support", "wallet", "airdrop", "bonus", "gift", "prize"};
        for (String word : words) {
            if (host.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void addRisk(List<String> risks, String value) {
        if (!risks.contains(value)) {
            risks.add(value);
        }
    }

    private static class ApkReport {
        String appName;
        String packageName;
        final ArrayList<String> risks = new ArrayList<>();

        void add(String risk) {
            if (!risks.contains(risk)) {
                risks.add(risk);
            }
        }
    }
}
