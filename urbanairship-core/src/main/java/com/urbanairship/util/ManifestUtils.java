/* Copyright Airship and Contributors */

package com.urbanairship.util;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.urbanairship.UAirship;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

/**
 * Utility methods for validating the AndroidManifest.xml file.
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class ManifestUtils {

    /**
     * Metadata an app can use to disable installing network security provider.
     */
    @NonNull
    private final static String INSTALL_NETWORK_SECURITY_PROVIDER = "com.urbanairship.INSTALL_NETWORK_SECURITY_PROVIDER";

    /**
     * Metadata an app can use to enable local storage.
     */
    @NonNull
    public final static String ENABLE_LOCAL_STORAGE = "com.urbanairship.webview.ENABLE_LOCAL_STORAGE";

    /**
     * Database directory for local storage on Android version prior to API 19.
     */
    @NonNull
    public final static String LOCAL_STORAGE_DATABASE_DIRECTORY = "com.urbanairship.webview.localstorage";

    /**
     * Returns whether the specified permission is granted for the application or not.
     *
     * @param permission Permission to check.
     * @return <code>true</code> if the permission is granted, otherwise <code>false</code>.
     */
    public static boolean isPermissionGranted(@NonNull String permission) {
        return PackageManager.PERMISSION_GRANTED == UAirship.getPackageManager()
                                                            .checkPermission(permission, UAirship.getPackageName());
    }

    /**
     * Gets the ComponentInfo for an activity
     *
     * @param activity The activity to look up
     * @return The activity's ComponentInfo, or null if the activity
     * is not listed in the manifest
     */
    @Nullable
    public static ActivityInfo getActivityInfo(@NonNull Class activity) {
        if (activity.getCanonicalName() == null) {
            return null;
        }

        ComponentName componentName = new ComponentName(UAirship.getPackageName(),
                activity.getCanonicalName());
        try {
            return UAirship.getPackageManager().getActivityInfo(componentName,
                    PackageManager.GET_META_DATA);

        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Gets the ApplicationInfo for the application.
     *
     * @return An instance of ApplicationInfo, or null if the info is unavailable.
     */
    @Nullable
    public static ApplicationInfo getApplicationInfo() {
        try {
            return UAirship.getPackageManager().getApplicationInfo(UAirship.getPackageName(),
                    PackageManager.GET_META_DATA);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Helper method to check if local storage should be used.
     *
     * @return {@code true} if local storage should be used, otherwise {@code false}.
     */
    public static boolean shouldEnableLocalStorage() {
        ApplicationInfo info = ManifestUtils.getApplicationInfo();
        if (info != null && info.metaData != null && info.metaData.getBoolean(ENABLE_LOCAL_STORAGE, false)) {
            return true;
        }

        return false;
    }

    /**
     * Helper method to check if the network security provider should be installed.
     *
     * @return {@code true} if the provider should be installed, otherwise {@code false}.
     */
    public static boolean shouldInstallNetworkSecurityProvider() {
        ApplicationInfo info = ManifestUtils.getApplicationInfo();
        if (info != null && info.metaData != null && info.metaData.getBoolean(INSTALL_NETWORK_SECURITY_PROVIDER, false)) {
            return true;
        }

        return false;
    }
}
