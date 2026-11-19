package com.android.wificalllocal.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.android.wificalllocal.BuildConfig;
import com.android.wificalllocal.R;
import com.google.firebase.FirebaseApp;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

/**
 * Manages Firebase Remote Config fetching, activation, and offline SharedPreferences caching.
 * Safe when offline or when app/google-services.json has not yet been provisioned.
 */
public class RemoteConfigManager {

    public static final String KEY_IS_APP_ENABLED = "is_app_enabled";
    public static final String KEY_LATEST_VERSION_CODE = "latest_version_code";
    public static final String KEY_UPDATE_LINK = "update_link";
    public static final String KEY_UPDATE_MESSAGE = "update_message";

    private static final String PREFS_NAME = "wcl_remote_config_cache";
    private static final String PREF_ENABLED = "cached_is_app_enabled";
    private static final String PREF_LATEST_VERSION = "cached_latest_version_code";
    private static final String PREF_UPDATE_LINK = "cached_update_link";
    private static final String PREF_UPDATE_MESSAGE = "cached_update_message";
    private static final String PREF_LAST_FETCH_MS = "cached_last_fetch_ms";

    public static class ConfigSnapshot {
        public final boolean isAppEnabled;
        public final long latestVersionCode;
        public final String updateLink;
        public final String updateMessage;
        public final boolean fromNetwork;

        public ConfigSnapshot(boolean isAppEnabled, long latestVersionCode,
                              String updateLink, String updateMessage, boolean fromNetwork) {
            this.isAppEnabled = isAppEnabled;
            this.latestVersionCode = latestVersionCode;
            this.updateLink = updateLink != null ? updateLink.trim() : "";
            this.updateMessage = updateMessage != null ? updateMessage : "";
            this.fromNetwork = fromNetwork;
        }
    }

    public interface FetchCallback {
        void onResult(ConfigSnapshot snapshot);
    }

    private final Context context;
    private final SharedPreferences cachePrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean defaultsApplied = false;

    public RemoteConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.cachePrefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public ConfigSnapshot getCachedSnapshot() {
        boolean enabled = cachePrefs.getBoolean(PREF_ENABLED, true);
        long latestVer = cachePrefs.getLong(PREF_LATEST_VERSION, BuildConfig.VERSION_CODE);
        String link = cachePrefs.getString(PREF_UPDATE_LINK, "");
        String msg = cachePrefs.getString(PREF_UPDATE_MESSAGE, context.getString(R.string.force_update_default_msg));
        return new ConfigSnapshot(enabled, latestVer, link, msg, false);
    }

    public void persistSnapshot(ConfigSnapshot snapshot) {
        if (snapshot == null) return;
        cachePrefs.edit()
                .putBoolean(PREF_ENABLED, snapshot.isAppEnabled)
                .putLong(PREF_LATEST_VERSION, snapshot.latestVersionCode)
                .putString(PREF_UPDATE_LINK, snapshot.updateLink)
                .putString(PREF_UPDATE_MESSAGE, snapshot.updateMessage)
                .putLong(PREF_LAST_FETCH_MS, System.currentTimeMillis())
                .apply();
    }

    public void fetchAndActivate(FetchCallback callback) {
        ConfigSnapshot cached = getCachedSnapshot();
        FirebaseRemoteConfig remoteConfig = getRemoteConfigIfAvailable();
        if (remoteConfig == null) {
            postCallback(callback, cached);
            return;
        }

        if (!defaultsApplied) {
            defaultsApplied = true;
            long minFetchSeconds = BuildConfig.DEBUG ? 0L : 900L;
            FirebaseRemoteConfigSettings settings = new FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(minFetchSeconds)
                    .setFetchTimeoutInSeconds(10L)
                    .build();
            remoteConfig.setConfigSettingsAsync(settings);
            remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults);
        }

        remoteConfig.fetchAndActivate()
                .addOnCompleteListener(task -> {
                    boolean networkOk = task.isSuccessful();
                    ConfigSnapshot latest = networkOk ? readFromFirebase(remoteConfig, true) : getCachedSnapshot();
                    if (networkOk) {
                        persistSnapshot(latest);
                    }
                    postCallback(callback, latest);
                })
                .addOnFailureListener(e -> postCallback(callback, getCachedSnapshot()));
    }

    private FirebaseRemoteConfig getRemoteConfigIfAvailable() {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context);
            }
            if (FirebaseApp.getApps(context).isEmpty()) {
                return null;
            }
            return FirebaseRemoteConfig.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ConfigSnapshot readFromFirebase(FirebaseRemoteConfig rc, boolean fromNetwork) {
        boolean enabled = rc.getBoolean(KEY_IS_APP_ENABLED);
        long latestVer = rc.getLong(KEY_LATEST_VERSION_CODE);
        String link = rc.getString(KEY_UPDATE_LINK);
        String msg = rc.getString(KEY_UPDATE_MESSAGE);
        if (msg.trim().isEmpty()) {
            msg = context.getString(R.string.force_update_default_msg);
        }
        return new ConfigSnapshot(enabled, latestVer, link, msg, fromNetwork);
    }

    private void postCallback(FetchCallback callback, ConfigSnapshot snapshot) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onResult(snapshot));
    }
}
