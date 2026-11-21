package com.android.wificalllocal;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.android.wificalllocal.ads.AdConsentManager;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end JVM + Robolectric audit of the AdMob Adaptive Banner integration:
 * - Verifies activity_main.xml placement (banner immediately above BottomNavigationView)
 * - Verifies single MobileAds.initialize() call & zero duplicate AdView creation across lifecycle retries
 * - Verifies adaptive banner AdSize calculation, AdView creation, and loadAd() execution
 * - Verifies AdListener onAdLoaded() / onAdFailedToLoad() visibility & error reporting
 * - Verifies Firebase Remote Config ads_enabled toggle & detail screen composer suppression
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AdMobIntegrationTest {

    private void enableInternetCapabilityInShadow(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        Network active = cm.getActiveNetwork();
        if (active == null) return;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps != null) {
            ShadowNetworkCapabilities shadowCaps = Shadows.shadowOf(caps);
            shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
    }

    @Test
    public void activityMainLayoutPlacesAdBannerImmediatelyAboveBottomNav() {
        AppCompatActivity host = Robolectric.buildActivity(AppCompatActivity.class)
                .create().start().resume().get();
        host.setTheme(R.style.Theme_WifiCallLocal);
        View root = host.getLayoutInflater().inflate(R.layout.activity_main, null, false);
        ViewGroup mainRoot = root.findViewById(R.id.main_root);
        View fragmentContainer = root.findViewById(R.id.fragment_container);
        FrameLayout bannerContainer = root.findViewById(R.id.ad_banner_container);
        View bottomNav = root.findViewById(R.id.bottom_navigation);

        assertNotNull(mainRoot);
        assertNotNull(fragmentContainer);
        assertNotNull(bannerContainer);
        assertNotNull(bottomNav);

        int fragIdx = mainRoot.indexOfChild(fragmentContainer);
        int adIdx = mainRoot.indexOfChild(bannerContainer);
        int navIdx = mainRoot.indexOfChild(bottomNav);
        assertTrue(fragIdx >= 0);
        assertEquals("Ad container must be immediately below fragment container", fragIdx + 1, adIdx);
        assertEquals("Ad container must be immediately above BottomNavigationView", adIdx + 1, navIdx);
        assertEquals(View.GONE, bannerContainer.getVisibility());
    }

    @Test
    public void testBannerLoadsOnceComputesAdaptiveSizeAndHandlesCallbacksCleanly() {
        AppCompatActivity host = Robolectric.buildActivity(AppCompatActivity.class)
                .create().start().resume().visible().get();
        host.setTheme(R.style.Theme_WifiCallLocal);
        enableInternetCapabilityInShadow(host);

        FrameLayout bannerContainer = new FrameLayout(host);
        bannerContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        host.setContentView(bannerContainer);

        AdConsentManager mgr = new AdConsentManager();
        mgr.setAdUnitIdOverrideForTesting(AdConsentManager.GOOGLE_TEST_BANNER_UNIT_ID);
        mgr.setAdsEnabledByRemoteConfig(true);

        // Simulate rapid onCreate + checkRemoteControlGate + onResume calls
        mgr.initializeAndLoadBanner(host, bannerContainer);
        mgr.initializeAndLoadBanner(host, bannerContainer);
        mgr.initializeAndLoadBanner(host, bannerContainer);
        // Advance past the 3-second UMP watchdog fallback + posted banner creation runnable
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3200, java.util.concurrent.TimeUnit.MILLISECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        System.out.println("STEP2_AUDIT initStatus=" + mgr.getInitStatus()
                + " | mobileAdsInitCalls=" + mgr.getMobileAdsInitCallCount()
                + " | loadAdCalls=" + mgr.getLoadAdCallCount()
                + " | bannerStatus=" + mgr.getBannerStatus()
                + " | lastErrorCode=" + mgr.getLastErrorCode()
                + " | lastErrorMsg=" + mgr.getLastErrorMessage());

        // Adaptive banner sizing
        AdSize size = mgr.getLastComputedAdSize();
        assertNotNull("Adaptive AdSize should be calculated; net="
                + com.android.wificalllocal.network.NetworkUtils.hasNetworkConnectivity(host)
                + ", init=" + mgr.getInitStatus()
                + ", banner=" + mgr.getBannerStatus()
                + ", avail=" + mgr.getAvailabilityStatus(), size);
        assertTrue("Adaptive AdSize width must be >= 320dp", size.getWidth() >= 320);
        assertTrue("Adaptive AdSize height must be > 0", size.getHeightInPixels(host) > 0);

        // Single MobileAds.initialize() & single AdView instance (never duplicates)
        assertEquals("MobileAds.initialize() must execute exactly once", 1, mgr.getMobileAdsInitCallCount());
        assertEquals("Must create exactly one AdView child", 1, bannerContainer.getChildCount());
        assertTrue(bannerContainer.getChildAt(0) instanceof AdView);
        AdView adView = (AdView) bannerContainer.getChildAt(0);
        assertEquals(AdConsentManager.GOOGLE_TEST_BANNER_UNIT_ID, adView.getAdUnitId());
        assertEquals(1, mgr.getLoadAdCallCount());

        // Verify AdListener onAdLoaded makes banner visible & non-zero height
        adView.getAdListener().onAdLoaded();
        assertTrue(mgr.isAdLoaded());
        assertEquals(View.VISIBLE, bannerContainer.getVisibility());
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bannerContainer.getLayoutParams().height);

        // Verify suppression hides banner on detail/composer screens without destroying it
        mgr.setSuppressedForScreen(true);
        assertEquals(View.GONE, bannerContainer.getVisibility());
        mgr.setSuppressedForScreen(false);
        assertEquals(View.VISIBLE, bannerContainer.getVisibility());

        // Verify onAdFailedToLoad captures exact error code + message and collapses container (no blank box)
        LoadAdError simulatedError = new LoadAdError(3, "No fill.", "com.google.android.gms.ads", null, null);
        adView.getAdListener().onAdFailedToLoad(simulatedError);
        assertFalse(mgr.isAdLoaded());
        assertEquals(3, mgr.getLastErrorCode());
        assertEquals("No fill.", mgr.getLastErrorMessage());
        assertEquals(View.GONE, bannerContainer.getVisibility());
        assertTrue(mgr.getBannerStatus().contains("3: No fill."));
    }

    @Test
    public void remoteConfigAdsDisabledSuppressesAndDestroysBannerCleanly() {
        AppCompatActivity host = Robolectric.buildActivity(AppCompatActivity.class)
                .create().start().resume().visible().get();
        host.setTheme(R.style.Theme_WifiCallLocal);
        enableInternetCapabilityInShadow(host);

        FrameLayout bannerContainer = new FrameLayout(host);
        host.setContentView(bannerContainer);

        AdConsentManager mgr = new AdConsentManager();
        mgr.setAdUnitIdOverrideForTesting(AdConsentManager.GOOGLE_TEST_BANNER_UNIT_ID);
        mgr.initializeAndLoadBanner(host, bannerContainer);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3200, java.util.concurrent.TimeUnit.MILLISECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Expected AdView child; init=" + mgr.getInitStatus()
                + ", banner=" + mgr.getBannerStatus()
                + ", avail=" + mgr.getAvailabilityStatus(), 1, bannerContainer.getChildCount());

        mgr.setAdsEnabledByRemoteConfig(false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(View.GONE, bannerContainer.getVisibility());
        assertEquals(0, bannerContainer.getChildCount());
        assertNull(mgr.getCurrentAdView());
        assertEquals("Disabled via Remote Config", mgr.getInitStatus());
    }

    @Test
    public void manifestHasProductionAdMobAppId() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(
                ctx.getPackageName(), PackageManager.GET_META_DATA);
        assertNotNull(ai.metaData);
        assertEquals("ca-app-pub-3181872815781744~9879947642",
                ai.metaData.getString("com.google.android.gms.ads.APPLICATION_ID"));
        assertEquals("ca-app-pub-3181872815781744~9879947642",
                ctx.getString(R.string.admob_app_id));
        assertEquals("ca-app-pub-3181872815781744/1818458941",
                ctx.getString(R.string.admob_adaptive_banner_unit_id));
    }
}
