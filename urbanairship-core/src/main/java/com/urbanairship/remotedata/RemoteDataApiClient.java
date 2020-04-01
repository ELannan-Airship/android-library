/* Copyright Airship and Contributors */

package com.urbanairship.remotedata;

import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import com.urbanairship.AirshipConfigOptions;
import com.urbanairship.Logger;
import com.urbanairship.PushProviders;
import com.urbanairship.UAirship;
import com.urbanairship.http.Request;
import com.urbanairship.http.RequestFactory;
import com.urbanairship.http.Response;
import com.urbanairship.push.PushProvider;
import com.urbanairship.util.UAStringUtil;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * API client for fetching remote data.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class RemoteDataApiClient {

    private static final String REMOTE_DATA_PATH = "api/remote-data/app/";
    private static final String SDK_VERSION_QUERY_PARAM = "sdk_version";
    // ISO 639-2 two digit country code
    private static final String COUNTRY_QUERY_PARAM = "country";
    // ISO 3166-2 two digit language code
    private static final String LANGUAGE_QUERY_PARAM = "language";

    private static final String MANUFACTURER_QUERY_PARAM = "manufacturer";
    private static final String PUSH_PROVIDER_QUERY_PARAM = "push_providers";

    private static final List<String> MANUFACTURERS_WHITE_LIST = Collections.singletonList("huawei");

    private static final String AMAZON = "amazon";
    private static final String ANDROID = "android";

    private final AirshipConfigOptions configOptions;
    private final RequestFactory requestFactory;
    private final PushProviders pushProviders;
    private final int platform;

    @Nullable
    private URL url;

    /**
     * RemoteDataApiClient constructor.
     *
     * @param configOptions The config options.
     * @param platform The platform.
     * @param pushProviders The push providers
     */
    RemoteDataApiClient(@NonNull AirshipConfigOptions configOptions,
                        @UAirship.Platform int platform,
                        @NonNull PushProviders pushProviders) {
        this(configOptions, platform, pushProviders, RequestFactory.DEFAULT_REQUEST_FACTORY);
    }

    /**
     * RemoteDataApiClient constructor.
     * @param configOptions The config options.
     * @param platform The platform.
     * @param pushProviders The push providers
     * @param requestFactory A RequestFactory.
     */
    @VisibleForTesting
    RemoteDataApiClient(@NonNull AirshipConfigOptions configOptions,
                        @UAirship.Platform int platform,
                        @NonNull PushProviders pushProviders,
                        @NonNull RequestFactory requestFactory) {
        this.configOptions = configOptions;
        this.platform = platform;
        this.pushProviders = pushProviders;
        this.requestFactory = requestFactory;
    }

    /**
     * Executes a remote data request.
     *
     * @param lastModified An optional last-modified timestamp in ISO-8601 format.
     * @param locale The current locale.
     * @return A Response.
     */
    @Nullable
    Response fetchRemoteData(@Nullable String lastModified, @NonNull Locale locale) {
        URL url = getRemoteDataURL(locale);

        if (url == null) {
            return null;
        }

        Request request = requestFactory.createRequest("GET", url)
                                        .setCredentials(configOptions.appKey, configOptions.appSecret);

        if (lastModified != null) {
            request.setHeader("If-Modified-Since", lastModified);
        }

        return request.execute();
    }

    /**
     * Gets a device url for a given path.
     *
     * @param locale The current locale.
     * @return The device URL or {@code null} if the URL is invalid.
     */
    @Nullable
    private URL getRemoteDataURL(@NonNull Locale locale) {
        if (url != null) {
            return url;
        }

        // api/remote-data/app/{appkey}/{platform}?sdk_version={version}&language={language}&country={country}&manufacturer={manufacturer}&push_providers={push_providers}

        try {
            Uri.Builder builder = Uri.parse(configOptions.remoteDataUrl)
                                     .buildUpon()
                                     .appendEncodedPath(REMOTE_DATA_PATH)
                                     .appendPath(configOptions.appKey)
                                     .appendPath(platform == UAirship.AMAZON_PLATFORM ? AMAZON : ANDROID)
                                     .appendQueryParameter(SDK_VERSION_QUERY_PARAM, UAirship.getVersion());


            String manufacturer = getManufacturer();
            if (shouldIncludeManufacturer(manufacturer)) {
                builder.appendQueryParameter(MANUFACTURER_QUERY_PARAM, manufacturer);
            }

            String providers = getPushProviderCsv();
            if (providers != null) {
                builder.appendQueryParameter(PUSH_PROVIDER_QUERY_PARAM, providers);
            }

            if (!UAStringUtil.isEmpty(locale.getLanguage())) {
                builder.appendQueryParameter(LANGUAGE_QUERY_PARAM, locale.getLanguage());
            }

            if (!UAStringUtil.isEmpty(locale.getCountry())) {
                builder.appendQueryParameter(COUNTRY_QUERY_PARAM, locale.getCountry());
            }

            url = new URL(builder.build().toString());
        } catch (MalformedURLException e) {
            Logger.error(e, "Invalid URL.");
            return null;
        }

        return url;
    }

    private boolean shouldIncludeManufacturer(@NonNull String manufacturer) {
        return MANUFACTURERS_WHITE_LIST.contains(manufacturer.toLowerCase());
    }

    @NonNull
    private static String getManufacturer() {
        String manufacturer = Build.MANUFACTURER;
        if (manufacturer == null) {
            return "";
        }
        return manufacturer.toLowerCase(Locale.US);
    }

    @Nullable
    private String getPushProviderCsv() {
        Set<String> deliveryTypes = new HashSet<>();
        for (PushProvider provider : pushProviders.getAvailableProviders()) {
            deliveryTypes.add(provider.getDeliveryType());
        }

        if (deliveryTypes.isEmpty()) {
            return null;
        }

        return UAStringUtil.join(deliveryTypes, ",");
    }
}
