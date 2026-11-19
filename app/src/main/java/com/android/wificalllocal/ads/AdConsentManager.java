package com.android.wificalllocal.ads;

import android.app.Activity;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

import com.android.wificalllocal.R;
import com.android.wificalllocal.network.NetworkUtils;
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

/**
 * Manages Google UMP consent flow and AdMob anchored Adaptive Banner Ads.
 * Reads the Ad Unit ID from @string/admob_adaptive_banner_unit_id.
 * Remains completely safe and hidden when offline, during calls, or when suppressed by IME/detail screens.
 */
public class AdConsentManager {

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private ConsentInformation consentInformation;
    private FrameLayout currentContainer;
    private AdView currentAdView;
    private boolean adLoaded = false;
    private boolean suppressedForScreen = false;

    public void initializeAndLoadBanner(Activity activity, FrameLayout bannerContainer) {
        if (activity == null || bannerContainer == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        this.currentContainer = bannerContainer;
        if (!NetworkUtils.hasInternetConnection(activity)) {
            adLoaded = false;
            bannerContainer.setVisibility(View.GONE);
            return;
        }

        final AtomicBoolean loadTriggered = new AtomicBoolean(false);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        consentInformation = UserMessagingPlatform.getConsentInformation(activity);
        consentInformation.requestConsentInfoUpdate(
                activity,
                params,
                () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                            activity,
                            loadAndShowError -> {
                                if (!activity.isFinishing()
                                        && !activity.isDestroyed()
                                        && consentInformation != null
                                        && consentInformation.canRequestAds()
                                        && loadTriggered.compareAndSet(false, true)) {
                                    initMobileAdsAndLoad(activity, bannerContainer);
                                }
                            }
                    );
                },
                requestConsentError -> {
                    if (consentInformation == null || !consentInformation.canRequestAds()) {
                        adLoaded = false;
                        bannerContainer.setVisibility(View.GONE);
                    }
                }
        );

        if (consentInformation.canRequestAds() && loadTriggered.compareAndSet(false, true)) {
            initMobileAdsAndLoad(activity, bannerContainer);
        }
    }

    public void setSuppressedForScreen(boolean suppressed) {
        this.suppressedForScreen = suppressed;
        if (currentContainer == null) return;
        if (suppressed) {
            currentContainer.setVisibility(View.GONE);
        } else if (adLoaded) {
            currentContainer.setVisibility(View.VISIBLE);
        }
    }

    private void initMobileAdsAndLoad(Activity activity, FrameLayout bannerContainer) {
        if (initialized.compareAndSet(false, true)) {
            RequestConfiguration config = new RequestConfiguration.Builder()
                    .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                    .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_UNSPECIFIED)
                    .build();
            MobileAds.setRequestConfiguration(config);
            MobileAds.initialize(activity.getApplicationContext(), status -> {});
        }

        bannerContainer.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            AdSize adaptiveSize = computeAdaptiveBannerSize(activity, bannerContainer);
            if (currentAdView != null) {
                currentAdView.destroy();
            }
            bannerContainer.removeAllViews();

            AdView adView = new AdView(activity);
            adView.setAdUnitId(activity.getString(R.string.admob_adaptive_banner_unit_id));
            adView.setAdSize(adaptiveSize);
            adView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    adLoaded = true;
                    bannerContainer.setVisibility(suppressedForScreen ? View.GONE : View.VISIBLE);
                }

                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    adLoaded = false;
                    bannerContainer.setVisibility(View.GONE);
                }
            });
            currentAdView = adView;
            bannerContainer.addView(adView);
            adView.loadAd(new AdRequest.Builder().build());
        });
    }

    private AdSize computeAdaptiveBannerSize(Activity activity, FrameLayout container) {
        float density = activity.getResources().getDisplayMetrics().density;
        int widthPx = container.getWidth();
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
        int adWidthDp = Math.max(320, Math.round(widthPx / Math.max(1f, density)));
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidthDp);
    }
}
