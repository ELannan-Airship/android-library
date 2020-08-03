/* Copyright Airship and Contributors */

package com.urbanairship.automation.tags;

import com.urbanairship.TestAirshipRuntimeConfig;
import com.urbanairship.TestRequest;
import com.urbanairship.UAirship;
import com.urbanairship.config.AirshipUrlConfig;
import com.urbanairship.http.Request;
import com.urbanairship.http.RequestFactory;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonMap;
import com.urbanairship.json.JsonValue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class TagGroupLookupApiClientTest {

    private TestRequest testRequest;
    private TestAirshipRuntimeConfig runtimeConfig;
    private RequestFactory requestFactory;
    private TagGroupLookupApiClient client;
    private Map<String, Set<String>> requestTags;
    private Map<String, Set<String>> responseTags;

    @Before
    public void setUp() {
        requestTags = new HashMap<>();
        requestTags.put("cool-group", new HashSet<String>());
        requestTags.get("cool-group").add("cool");

        responseTags = new HashMap<>();
        responseTags.put("cool-response", new HashSet<String>());
        responseTags.get("cool-response").add("cool");

        runtimeConfig = TestAirshipRuntimeConfig.newTestConfig();
        runtimeConfig.setUrlConfig(AirshipUrlConfig.newBuilder()
                                                   .setDeviceUrl("https://test.urbanairship.com")
                                                   .build());

        testRequest = new TestRequest();
        testRequest.responseBody = JsonMap.newBuilder()
                                          .putOpt("tag_groups", responseTags)
                                          .put("last_modified", "lastModifiedTime")
                                          .build().toString();

        testRequest.responseStatus = HttpURLConnection.HTTP_OK;

        requestFactory = new RequestFactory() {
            @NonNull
            @Override
            public Request createRequest() {
                return testRequest;
            }
        };

        this.client = new TagGroupLookupApiClient(runtimeConfig, requestFactory);
    }

    /**
     * Test android channel tag lookup.
     */
    @Test
    public void lookupTagsAndroid() throws JsonException {
        TagGroupResponse response = client.lookupTagGroups("some-channel", requestTags, null);

        assertEquals(200, response.status);
        assertEquals("lastModifiedTime", response.lastModifiedTime);
        assertEquals(responseTags, response.tags);

        verifyRequest(UAirship.ANDROID_PLATFORM, "some-channel", requestTags, null);
    }

    /**
     * Test amazon channel tag lookup.
     */
    @Test
    public void lookupTagsAmazon() throws JsonException {
        runtimeConfig.setPlatform(UAirship.AMAZON_PLATFORM);
        TagGroupResponse response = client.lookupTagGroups("some-channel", requestTags, null);

        assertEquals(200, response.status);
        assertEquals("lastModifiedTime", response.lastModifiedTime);
        assertEquals(responseTags, response.tags);

        verifyRequest(UAirship.AMAZON_PLATFORM, "some-channel", requestTags, null);
    }

    /**
     * Test error responses.
     */
    @Test
    public void testFailedResponse() {
        testRequest.responseStatus = 400;

        TagGroupResponse response = client.lookupTagGroups("some-channel", requestTags, null);

        assertEquals(400, response.status);
        assertNull(response.lastModifiedTime);
        assertNull(response.tags);
    }

    /**
     * Test when requesting tags with a cachedResponse, if the new response last_modified_time
     * matches, the cachedResponse is returned.
     */
    @Test
    public void testCachedResponse() throws JsonException {
        // Get a valid response
        TagGroupResponse response = client.lookupTagGroups("some-channel", requestTags, null);
        verifyRequest(UAirship.ANDROID_PLATFORM, "some-channel", requestTags, null);

        // Update the response to return a 200 with the same lastModified time as the
        // response, but no tags. This indicates a 304, but since its a POST we do not
        // get a 304.
        testRequest.responseBody = JsonMap.newBuilder()
                                          .put("last_modified", "lastModifiedTime")
                                          .build().toString();

        testRequest.responseStatus = HttpURLConnection.HTTP_OK;

        TagGroupResponse cachedResponse = client.lookupTagGroups("some-channel", requestTags, response);
        verifyRequest(UAirship.ANDROID_PLATFORM, "some-channel", requestTags, response.lastModifiedTime);

        // Should return the original response
        assertEquals(cachedResponse, response);
    }

    /**
     * Test cached response is not returned if we get new data.
     */
    @Test
    public void testCachedResponseNewData() {
        // Get a valid response
        TagGroupResponse response = client.lookupTagGroups("some-channel", requestTags, null);

        // Update the response to return new data
        testRequest.responseBody = JsonMap.newBuilder()
                                          .putOpt("tag_groups", responseTags)
                                          .put("last_modified", "newDate")
                                          .build().toString();

        testRequest.responseStatus = HttpURLConnection.HTTP_OK;

        TagGroupResponse newResponse = client.lookupTagGroups("some-channel", requestTags, response);

        // Should return the original response
        assertNotEquals(newResponse, response);
        assertEquals("newDate", newResponse.lastModifiedTime);
    }

    void verifyRequest(@UAirship.Platform int platform, String channel, Map<String, Set<String>> tags, String ifModifiedSince) throws JsonException {
        assertEquals("https://test.urbanairship.com/api/channel-tags-lookup", testRequest.getUrl().toString());
        assertEquals("POST", testRequest.getRequestMethod());

        JsonMap body = JsonValue.parseString(testRequest.getRequestBody()).optMap();

        if (platform == UAirship.ANDROID_PLATFORM) {
            assertEquals("android", body.get("device_type").getString());
        } else {
            assertEquals("amazon", body.get("device_type").getString());
        }

        assertEquals(tags, TagGroupUtils.parseTags(body.get("tag_groups")));

        assertEquals(channel, body.get("channel_id").getString());
        assertEquals(ifModifiedSince, body.opt("if_modified_since").getString());
    }

}
