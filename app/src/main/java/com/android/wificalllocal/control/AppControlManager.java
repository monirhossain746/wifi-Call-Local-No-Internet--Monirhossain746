package com.android.wificalllocal.control;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.android.wificalllocal.BuildConfig;
import com.android.wificalllocal.service.LocalMeshService;
import com.android.wificalllocal.ui.blocked.AppBlockedActivity;

/**
 * Evaluates Firebase Remote Config kill switch & force-update rules cleanly outside Activities.
 * Enforces immediate blocking of calls/messaging/UI when disabled or outdated.
 */
public class AppControlManager {

    public enum AccessDecision {
        ALLOWED,
        BLOCKED_DISABLED,
        BLOCKED_FORCE_UPDATE
    }

    public static class AppGateState {
        public final AccessDecision decision;
        public final long latestVersionCode;
        public final String updateLink;
        public final String updateMessage;
        public final boolean fromNetwork;

        public AppGateState(AccessDecision decision, long latestVersionCode,
                            String updateLink, String updateMessage, boolean fromNetwork) {
            this.decision = decision;
            this.latestVersionCode = latestVersionCode;
            this.updateLink = updateLink;
            this.updateMessage = updateMessage;
            this.fromNetwork = fromNetwork;
        }

        public boolean isBlocked() {
            return decision != AccessDecision.ALLOWED;
        }
    }

    public interface GateCheckListener {
        void onGateEvaluated(AppGateState state);
    }

    private final Context context;
    private final RemoteConfigManager remoteConfigManager;
    private volatile AppGateState lastState;

    public AppControlManager(Context context) {
        this.context = context.getApplicationContext();
        this.remoteConfigManager = new RemoteConfigManager(this.context);
        this.lastState = evaluate(remoteConfigManager.getCachedSnapshot(), BuildConfig.VERSION_CODE);
    }

    public static AppGateState evaluate(RemoteConfigManager.ConfigSnapshot snapshot, int currentVersionCode) {
        if (snapshot == null) {
            return new AppGateState(AccessDecision.ALLOWED, currentVersionCode, "", "", false);
        }
        // Kill switch has highest priority and disables all functionality immediately
        if (!snapshot.isAppEnabled) {
            return new AppGateState(
                    AccessDecision.BLOCKED_DISABLED,
                    snapshot.latestVersionCode,
                    snapshot.updateLink,
                    snapshot.updateMessage,
                    snapshot.fromNetwork
            );
        }
        // Force update check when latest_version_code > local VERSION_CODE
        if (snapshot.latestVersionCode > currentVersionCode) {
            return new AppGateState(
                    AccessDecision.BLOCKED_FORCE_UPDATE,
                    snapshot.latestVersionCode,
                    snapshot.updateLink,
                    snapshot.updateMessage,
                    snapshot.fromNetwork
            );
        }
        return new AppGateState(
                AccessDecision.ALLOWED,
                snapshot.latestVersionCode,
                snapshot.updateLink,
                snapshot.updateMessage,
                snapshot.fromNetwork
        );
    }

    public AppGateState getCurrentGateState() {
        lastState = evaluate(remoteConfigManager.getCachedSnapshot(), BuildConfig.VERSION_CODE);
        return lastState;
    }

    public void checkRemoteGate(GateCheckListener listener) {
        remoteConfigManager.fetchAndActivate(snapshot -> {
            AppGateState evaluated = evaluate(snapshot, BuildConfig.VERSION_CODE);
            lastState = evaluated;
            if (evaluated.isBlocked()) {
                LocalMeshService.stop(context);
            }
            if (listener != null) {
                listener.onGateEvaluated(evaluated);
            }
        });
    }

    public void enforceGateIfBlocked(Activity activity, AppGateState state) {
        if (activity == null || state == null || !state.isBlocked()) return;
        if (activity instanceof AppBlockedActivity) return;

        LocalMeshService.stop(context);
        Intent i = new Intent(activity, AppBlockedActivity.class);
        i.putExtra(AppBlockedActivity.EXTRA_DECISION, state.decision.name());
        i.putExtra(AppBlockedActivity.EXTRA_UPDATE_URL, state.updateLink);
        i.putExtra(AppBlockedActivity.EXTRA_UPDATE_MESSAGE, state.updateMessage);
        i.putExtra(AppBlockedActivity.EXTRA_LATEST_VERSION, state.latestVersionCode);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(i);
        activity.finish();
    }

    public RemoteConfigManager getRemoteConfigManager() {
        return remoteConfigManager;
    }
}
