package com.android.wificalllocal.ads;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.wificalllocal.R;
import com.android.wificalllocal.network.NetworkUtils;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Google UMP consent flow, Remote Config ads_enabled toggle, Activity lifecycle safety,
 * and AdMob anchored Adaptive Banner Ads reading @string/admob_adaptive_banner_unit_id.
 *
 * Fixes applied:
 * - Single MobileAds.initialize() call per process (AtomicBoolean guard)
 * - No duplicate AdView creation or rapid destroy/recreate race across onCreate/onResume/RemoteConfig
 * - UMP error / unconfigured consent form fallback to non-personalized ad request instead of silent failure
 * - Uses NET_CAPABILITY_INTERNET rather than strict NET_CAPABILITY_VALIDATED so emulators/proxies/WiFi work
 * - Container stays 1px VISIBLE while loading so AdView lays out properly, collapses to GONE on failure/suppression
 */
public class AdConsentManager {

    public static final String GOOGLE_TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/9214589741";

    private final AtomicBoolean mobileAdsInitStarted = new AtomicBoolean(false);
    private final AtomicBoolean consentFlowInFlight = new AtomicBoolean(false);
    private final AtomicInteger mobileAdsInitCallCount = new AtomicInteger(0);
    private final AtomicInteger loadAdCallCount = new AtomicInteger(0);
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private ConsentInformation consentInformation;
    private FrameLayout currentContainer;
    private AdView currentAdView;
    private String currentAdUnitId;
    private String adUnitIdOverrideForTesting;
    private AdSize lastComputedAdSize;

    private volatile boolean mobileAdsInitialized = false;
    private volatile boolean adLoading = false;
    private volatile boolean adLoaded = false;
    private volatile boolean suppressedForScreen = false;
    private volatile boolean remoteAdsEnabled = true;

    private volatile String initStatus = "Not Initialized";
    private volatile String bannerStatus = "Idle";
    private volatile String availabilityStatus = "Pending";
    private volatile int lastErrorCode = -1;
    private volatile String lastErrorMessage = "";

    @VisibleForTesting
    public void setAdUnitIdOverrideForTesting(@Nullable String adUnitId) {
        this.adUnitIdOverrideForTesting = adUnitId;
    }

    public void setAdsEnabledByRemoteConfig(boolean enabled) {
        this.remoteAdsEnabled = enabled;
        if (!enabled) {
            initStatus = "Disabled via Remote Config";
            bannerStatus = "Skipped (ads_enabled = false)";
            availabilityStatus = "Disabled intentionally via Remote Config";
            adLoading = false;
            adLoaded = false;
            if (currentContainer != null) {
                mainHandler.post(this::destroyBanner);
            }
        }
    }

    public void initializeAndLoadBanner(Activity activity, FrameLayout bannerContainer) {
        if (activity == null || bannerContainer == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        this.currentContainer = bannerContainer;
        if (!remoteAdsEnabled) {
            setAdsEnabledByRemoteConfig(false);
            return;
        }

        String resolvedUnitId = resolveAdUnitId(activity);
        if (currentAdView != null
                && currentAdView.getParent() == bannerContainer
                && resolvedUnitId.equals(currentAdUnitId)
                && (adLoading || adLoaded)) {
            applyBannerVisibility();
            return;
        }

        if (!NetworkUtils.hasNetworkConnectivity(activity)) {
            adLoading = false;
            adLoaded = false;
            initStatus = "Deferred (No Network Interface)";
            bannerStatus = "Offline (Hidden)";
            availabilityStatus = "Unavailable offline (app works normally)";
            bannerContainer.setVisibility(View.GONE);
            return;
        }

        if (!consentFlowInFlight.compareAndSet(false, true)) {
            return;
        }

        initStatus = "Checking Consent (UMP)";
        bannerStatus = "Requesting consent…";
        availabilityStatus = "Initializing";

        final AtomicBoolean loadTriggered = new AtomicBoolean(false);
        final Runnable umpTimeoutFallback = () -> {
            consentFlowInFlight.set(false);
            if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) {
                return;
            }
            boolean canRequest = consentInformation != null && consentInformation.canRequestAds();
            if (loadTriggered.compareAndSet(false, true)) {
                initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, !canRequest);
            }
        };
        mainHandler.postDelayed(umpTimeoutFallback, 3000L);

        try {
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            consentInformation = UserMessagingPlatform.getConsentInformation(activity);
            consentInformation.requestConsentInfoUpdate(
                    activity,
                    params,
                    () -> {
                        mainHandler.removeCallbacks(umpTimeoutFallback);
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            consentFlowInFlight.set(false);
                            return;
                        }
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                                activity,
                                loadAndShowError -> {
                                    consentFlowInFlight.set(false);
                                    if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) {
                                        return;
                                    }
                                    boolean canRequest = consentInformation != null && consentInformation.canRequestAds();
                                    boolean useNpa = !canRequest;
                                    if (loadTriggered.compareAndSet(false, true)) {
                                        initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, useNpa);
                                    }
                                }
                        );
                    },
                    requestConsentError -> {
                        mainHandler.removeCallbacks(umpTimeoutFallback);
                        consentFlowInFlight.set(false);
                        if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) {
                            return;
                        }
                        boolean canRequest = consentInformation != null && consentInformation.canRequestAds();
                        boolean useNpa = !canRequest;
                        if (loadTriggered.compareAndSet(false, true)) {
                            initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, useNpa);
                        }
                    }
            );

            if (consentInformation != null && consentInformation.canRequestAds()
                    && loadTriggered.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(umpTimeoutFallback);
                consentFlowInFlight.set(false);
                initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, false);
            }
        } catch (Throwable t) {
            mainHandler.removeCallbacks(umpTimeoutFallback);
            consentFlowInFlight.set(false);
            if (loadTriggered.compareAndSet(false, true)) {
                initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, true);
            }
        }
    }

    public void setSuppressedForScreen(boolean suppressed) {
        this.suppressedForScreen = suppressed;
        applyBannerVisibility();
    }

    private void applyBannerVisibility() {
        if (currentContainer == null) return;
        if (suppressedForScreen || !remoteAdsEnabled) {
            currentContainer.setVisibility(View.GONE);
            return;
        }
        if (adLoaded) {
            setContainerHeight(currentContainer, ViewGroup.LayoutParams.WRAP_CONTENT);
            currentContainer.setVisibility(View.VISIBLE);
        } else if (adLoading) {
            // Keep 1px VISIBLE while request is in-flight so Android measures/lays out AdView without showing blank gap
            setContainerHeight(currentContainer, 1);
            currentContainer.setVisibility(View.VISIBLE);
        } else {
            currentContainer.setVisibility(View.GONE);
        }
    }

    private void setContainerHeight(FrameLayout container, int heightPx) {
        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp != null && lp.height != heightPx) {
            lp.height = heightPx;
            container.setLayoutParams(lp);
        }
    }

    public void pauseBanner() {
        if (currentAdView != null) {
            try {
                currentAdView.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    public void resumeBanner() {
        if (currentAdView != null) {
            try {
                currentAdView.resume();
            } catch (Throwable ignored) {
            }
        }
    }

    public void destroyBanner() {
        adLoading = false;
        adLoaded = false;
        currentAdUnitId = null;
        if (currentAdView != null) {
            try {
                currentAdView.destroy();
            } catch (Throwable ignored) {
            }
            currentAdView = null;
        }
        if (currentContainer != null) {
            currentContainer.removeAllViews();
            currentContainer.setVisibility(View.GONE);
        }
    }

    public String getInitStatus() { return initStatus; }
    public String getBannerStatus() { return bannerStatus; }
    public String getAvailabilityStatus() { return availabilityStatus; }
    public int getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public int getMobileAdsInitCallCount() { return mobileAdsInitCallCount.get(); }
    public int getLoadAdCallCount() { return loadAdCallCount.get(); }
    public AdSize getLastComputedAdSize() { return lastComputedAdSize; }
    public AdView getCurrentAdView() { return currentAdView; }
    public boolean isAdLoaded() { return adLoaded; }

    public String resolveAdUnitId(Activity activity) {
        if (adUnitIdOverrideForTesting != null && !adUnitIdOverrideForTesting.trim().isEmpty()) {
            return adUnitIdOverrideForTesting.trim();
        }
        return activity.getString(R.string.admob_adaptive_banner_unit_id);
    }

    private void initMobileAdsAndLoad(Activity activity, FrameLayout bannerContainer,
                                      String adUnitId, boolean nonPersonalizedOnly) {
        if (!remoteAdsEnabled || activity.isFinishing() || activity.isDestroyed()) return;

        if (mobileAdsInitStarted.compareAndSet(false, true)) {
            initStatus = "Initializing MobileAds…";
            mobileAdsInitCallCount.incrementAndGet();
            try {
                RequestConfiguration config = new RequestConfiguration.Builder()
                        .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                        .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_UNSPECIFIED)
                        .build();
                MobileAds.setRequestConfiguration(config);
                MobileAds.initialize(activity.getApplicationContext(), status -> {
                    mobileAdsInitialized = true;
                    initStatus = "Initialized (AdMob SDK active)";
                });
            } catch (Throwable t) {
                initStatus = "SDK init error: " + t.getMessage();
            }
        } else if (mobileAdsInitialized) {
            initStatus = "Initialized (AdMob SDK active)";
        }

        mainHandler.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) return;
            if (currentAdView != null && currentAdView.getParent() == bannerContainer
                    && adUnitId.equals(currentAdUnitId) && (adLoading || adLoaded)) {
                applyBannerVisibility();
                return;
            }

            AdSize adaptiveSize = computeAdaptiveBannerSize(activity, bannerContainer);
            lastComputedAdSize = adaptiveSize;

            if (currentAdView != null) {
                try {
                    currentAdView.destroy();
                } catch (Throwable ignored) {
                }
            }
            bannerContainer.removeAllViews();

            adLoading = true;
            adLoaded = false;
            lastErrorCode = -1;
            lastErrorMessage = "";
            currentAdUnitId = adUnitId;
            bannerStatus = "Loading Adaptive Banner (" + adUnitId + ")…";
            availabilityStatus = "Requesting ad";

            AdView adView = new AdView(activity);
            adView.setAdUnitId(adUnitId);
            adView.setAdSize(adaptiveSize);
            adView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    adLoading = false;
                    adLoaded = true;
                    lastErrorCode = -1;
                    lastErrorMessage = "";
                    bannerStatus = "Loaded (" + adView.getAdUnitId() + ")";
                    availabilityStatus = suppressedForScreen ? "Loaded (Hidden on subscreen)" : "Visible";
                    applyBannerVisibility();
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError error) {
                    adLoading = false;
                    adLoaded = false;
                    lastErrorCode = error.getCode();
                    lastErrorMessage = error.getMessage();
                    bannerStatus = "Failed (" + error.getCode() + ": " + error.getMessage() + ")";
                    availabilityStatus = "Failed (" + error.getCode() + ")";
                    applyBannerVisibility();
                }
            });

            currentAdView = adView;
            bannerContainer.addView(adView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            applyBannerVisibility();

            AdRequest.Builder reqBuilder = new AdRequest.Builder();
            if (nonPersonalizedOnly) {
                Bundle extras = new Bundle();
                extras.putString("npa", "1");
                reqBuilder.addNetworkExtrasBundle(AdMobAdapter.class, extras);
            }
            loadAdCallCount.incrementAndGet();
            try {
                adView.loadAd(reqBuilder.build());
            } catch (Throwable t) {
                adLoading = false;
                adLoaded = false;
                lastErrorCode = -999;
                lastErrorMessage = t.getMessage() != null ? t.getMessage() : "loadAd threw exception";
                bannerStatus = "Failed (-999: " + lastErrorMessage + ")";
                availabilityStatus = "Load exception";
                applyBannerVisibility();
            }
        });
    }

    @VisibleForTesting
    public AdSize computeAdaptiveBannerSize(Activity activity, FrameLayout container) {
        float density = Math.max(1f, activity.getResources().getDisplayMetrics().density);
        int widthPx = container != null ? container.getWidth() : 0;
        if (widthPx <= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
                widthPx = metrics.getBounds().width();
            } else {
                DisplayMetrics out = new DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getMetrics(out);
                widthPx = out.widthPixels;
            }
        }
        int adWidthDp = Math.max(320, Math.round(widthPx / density));
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidthDp);
    }
}
