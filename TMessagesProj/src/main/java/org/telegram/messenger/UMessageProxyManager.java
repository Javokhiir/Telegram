package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.util.List;

/**
 * "Use U Message Proxy": direct first, built-in MTProto proxies when direct keeps failing,
 * periodic return to direct. The proxy is applied at runtime only (never written to Telegram's
 * proxy prefs), so the user's own proxy settings stay untouched and always win.
 * All work happens on the UI thread.
 */
public class UMessageProxyManager implements NotificationCenter.NotificationCenterDelegate {

    private static final UMessageProxyManager INSTANCE = new UMessageProxyManager();

    private static final long ATTEMPT_TIMEOUT_MS = 20_000;          // no connection for this long = failed
    private static final long ATTEMPT_TIMEOUT_MAX_MS = 5 * 60_000;  // backoff cap per attempt
    private static final long DIRECT_PROBE_MS = 10 * 60_000;        // try direct again after this on proxy
    private static final long DIRECT_PROBE_MAX_MS = 60 * 60_000;

    private boolean usingProxy;
    private int proxyIndex;
    private int failedRounds;         // full direct+all-proxies cycles that failed, drives backoff
    private boolean probingDirect;
    private long directProbeDelay = DIRECT_PROBE_MS;
    private boolean failCheckScheduled;
    private boolean probeScheduled;
    private boolean waitingForNetwork;

    private final Runnable failCheck = this::onAttemptTimeout;
    private final Runnable directProbe = this::onDirectProbe;

    public static void init() {
        INSTANCE.initInternal();
    }

    /** Called when the toggle changes. */
    public static void onSettingChanged() {
        AndroidUtilities.runOnUIThread(INSTANCE::onToggle);
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        evaluate();
    }

    private static boolean isActive() {
        return UMessageConfig.isProxyFallbackEnabled() && !SharedConfig.isProxyEnabled();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged) {
            // User touched Telegram's own proxy settings; those already applied their proxy (or direct).
            usingProxy = false;
            probingDirect = false;
            resetTimers();
            evaluate();
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            evaluate();
        }
    }

    private void onToggle() {
        if (!UMessageConfig.isProxyFallbackEnabled() && usingProxy && !SharedConfig.isProxyEnabled()) {
            usingProxy = false;
            probingDirect = false;
            apply();
        }
        failedRounds = 0;
        directProbeDelay = DIRECT_PROBE_MS;
        resetTimers();
        evaluate();
    }

    private void evaluate() {
        if (!isActive() || UMessageProxyConfig.getProxies().isEmpty()) {
            resetTimers();
            return;
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
            cancelFailCheck();
            failedRounds = 0;
            waitingForNetwork = false;
            if (!usingProxy) {
                if (probingDirect) {
                    probingDirect = false;
                    directProbeDelay = DIRECT_PROBE_MS;
                    log("direct connection restored");
                }
            } else if (!probeScheduled) {
                probeScheduled = true;
                AndroidUtilities.runOnUIThread(directProbe, directProbeDelay);
            }
        } else if (state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            // No network is not a server failure; after it comes back direct gets a fresh chance soon.
            cancelFailCheck();
            if (!waitingForNetwork && usingProxy) {
                directProbeDelay = DIRECT_PROBE_MS;
            }
            waitingForNetwork = true;
        } else {
            waitingForNetwork = false;
            scheduleFailCheck();
        }
    }

    private void onAttemptTimeout() {
        failCheckScheduled = false;
        if (!isActive()) {
            return;
        }
        List<ProxySettings> proxies = UMessageProxyConfig.getProxies();
        if (proxies.isEmpty()) {
            return;
        }
        if (ApplicationLoader.mainInterfacePaused) {
            // Don't hop between servers in background; re-check later.
            scheduleFailCheck();
            return;
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating
                || state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            return;
        }
        if (!usingProxy) {
            if (probingDirect) {
                probingDirect = false;
                directProbeDelay = Math.min(directProbeDelay * 2, DIRECT_PROBE_MAX_MS);
            } else {
                proxyIndex = 0;
            }
            if (proxyIndex >= proxies.size()) {
                proxyIndex = 0;
            }
            usingProxy = true;
            log("direct failed, using proxy #" + (proxyIndex + 1));
        } else if (proxyIndex + 1 < proxies.size()) {
            proxyIndex++;
            log("proxy failed, using proxy #" + (proxyIndex + 1));
        } else {
            usingProxy = false;
            proxyIndex = 0;
            failedRounds++;
            log("all proxies failed, back to direct, round " + failedRounds);
        }
        cancelProbe();
        apply();
        scheduleFailCheck();
    }

    private void onDirectProbe() {
        probeScheduled = false;
        if (!isActive() || !usingProxy) {
            return;
        }
        if (ApplicationLoader.mainInterfacePaused) {
            probeScheduled = true;
            AndroidUtilities.runOnUIThread(directProbe, directProbeDelay);
            return;
        }
        log("probing direct connection");
        usingProxy = false;
        probingDirect = true;
        apply();
        cancelFailCheck();
        scheduleFailCheck();
    }

    private void apply() {
        try {
            if (usingProxy) {
                List<ProxySettings> proxies = UMessageProxyConfig.getProxies();
                if (proxyIndex < proxies.size()) {
                    ConnectionsManager.setProxySettings(true, proxies.get(proxyIndex));
                    return;
                }
                usingProxy = false;
            }
            ConnectionsManager.setProxySettings(false, null);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private long attemptTimeout() {
        return Math.min(ATTEMPT_TIMEOUT_MS << Math.min(failedRounds, 4), ATTEMPT_TIMEOUT_MAX_MS);
    }

    private void scheduleFailCheck() {
        if (!failCheckScheduled) {
            failCheckScheduled = true;
            AndroidUtilities.runOnUIThread(failCheck, attemptTimeout());
        }
    }

    private void cancelFailCheck() {
        failCheckScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(failCheck);
    }

    private void cancelProbe() {
        probeScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(directProbe);
    }

    private void resetTimers() {
        cancelFailCheck();
        cancelProbe();
    }

    private static void log(String message) {
        // Never log addresses or secrets.
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UMessageProxy: " + message);
        }
    }
}
