/* Copyright Airship and Contributors */

package com.urbanairship.channel;

import android.content.Context;

import com.urbanairship.AirshipComponent;
import com.urbanairship.AirshipComponentGroups;
import com.urbanairship.Logger;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.UAirship;
import com.urbanairship.config.AirshipRuntimeConfig;
import com.urbanairship.http.RequestException;
import com.urbanairship.http.Response;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.job.JobInfo;
import com.urbanairship.util.UAStringUtil;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.Size;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

/**
 * The named user is an alternate method of identifying the device. Once a named
 * user is associated to the device, it can be used to send push notifications
 * to the device.
 */
public class NamedUser extends AirshipComponent {

    /**
     * The change token tracks the start of setting the named user ID.
     */
    private static final String CHANGE_TOKEN_KEY = "com.urbanairship.nameduser.CHANGE_TOKEN_KEY";

    /**
     * The named user ID.
     */
    private static final String NAMED_USER_ID_KEY = "com.urbanairship.nameduser.NAMED_USER_ID_KEY";

    /**
     * Attribute storage key.
     */
    private static final String ATTRIBUTE_MUTATION_STORE_KEY = "com.urbanairship.nameduser.ATTRIBUTE_MUTATION_STORE_KEY";

    /**
     * Action to update named user association or disassociation.
     */
    static final String ACTION_UPDATE_NAMED_USER = "ACTION_UPDATE_NAMED_USER";

    /**
     * Key for storing the {@link NamedUser#getChangeToken()} in the {@link PreferenceDataStore} from the
     * last time the named user was updated.
     */
    private static final String LAST_UPDATED_TOKEN_KEY = "com.urbanairship.nameduser.LAST_UPDATED_TOKEN_KEY";

    /**
     * The maximum length of the named user ID string.
     */
    private static final int MAX_NAMED_USER_ID_LENGTH = 128;

    private final PreferenceDataStore preferenceDataStore;
    private final Object idLock = new Object();
    private final JobDispatcher jobDispatcher;
    private final TagGroupRegistrar tagGroupRegistrar;

    private final AirshipChannel airshipChannel;
    private final PendingAttributeMutationStore attributeMutationStore;

    private final NamedUserApiClient namedUserApiClient;
    private final AttributeApiClient attributeApiClient;

    /**
     * Creates a NamedUser.
     *
     * @param context The application context.
     * @param preferenceDataStore The preferences data store.
     * @param runtimeConfig The airship runtime config.
     * @param tagGroupRegistrar The tag group registrar.
     * @param airshipChannel The airship channel.
     */
    public NamedUser(@NonNull Context context, @NonNull PreferenceDataStore preferenceDataStore,
                     @NonNull AirshipRuntimeConfig runtimeConfig, @NonNull TagGroupRegistrar tagGroupRegistrar,
                     @NonNull AirshipChannel airshipChannel) {
        this(context, preferenceDataStore, tagGroupRegistrar, airshipChannel, JobDispatcher.shared(context),
                new NamedUserApiClient(runtimeConfig), new AttributeApiClient(runtimeConfig));
    }

    /**
     * @hide
     */
    @VisibleForTesting
    NamedUser(@NonNull Context context, @NonNull PreferenceDataStore preferenceDataStore,
              @NonNull TagGroupRegistrar tagGroupRegistrar, @NonNull AirshipChannel airshipChannel,
              @NonNull JobDispatcher dispatcher, @NonNull NamedUserApiClient namedUserApiClient,
              @NonNull AttributeApiClient attributeApiClient) {
        super(context, preferenceDataStore);
        this.preferenceDataStore = preferenceDataStore;
        this.tagGroupRegistrar = tagGroupRegistrar;
        this.airshipChannel = airshipChannel;
        this.jobDispatcher = dispatcher;
        this.namedUserApiClient = namedUserApiClient;
        this.attributeApiClient = attributeApiClient;
        this.attributeMutationStore = new PendingAttributeMutationStore(preferenceDataStore, ATTRIBUTE_MUTATION_STORE_KEY);
    }

    @Override
    protected void init() {
        super.init();

        airshipChannel.addChannelListener(new AirshipChannelListener() {
            @Override
            public void onChannelCreated(@NonNull String channelId) {
                dispatchNamedUserUpdateJob();
            }

            @Override
            public void onChannelUpdated(@NonNull String channelId) {

            }
        });

        airshipChannel.addChannelRegistrationPayloadExtender(new AirshipChannel.ChannelRegistrationPayloadExtender() {
            @NonNull
            @Override
            public ChannelRegistrationPayload.Builder extend(@NonNull ChannelRegistrationPayload.Builder builder) {
                return builder.setNamedUserId(getId());
            }
        });

        if (airshipChannel.getId() != null && (!isIdUpToDate() || getId() != null)) {
            dispatchNamedUserUpdateJob();
        }

        attributeMutationStore.collapseAndSaveMutations();
    }

    /**
     * @hide
     */
    @Override
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @AirshipComponentGroups.Group
    public int getComponentGroup() {
        return AirshipComponentGroups.NAMED_USER;
    }

    /**
     * @hide
     */
    @Override
    @WorkerThread
    @JobInfo.JobResult
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int onPerformJob(@NonNull UAirship airship, @NonNull JobInfo jobInfo) {
        if (ACTION_UPDATE_NAMED_USER.equals(jobInfo.getAction())) {
            return onUpdateNamedUser();
        }

        return JobInfo.JOB_FINISHED;
    }

    /**
     * Returns the named user ID.
     *
     * @return The named user ID as a string or null if it does not exist.
     */
    @Nullable
    public String getId() {
        return preferenceDataStore.getString(NAMED_USER_ID_KEY, null);
    }

    /**
     * Forces a named user update.
     */
    public void forceUpdate() {
        Logger.debug("NamedUser - force named user update.");
        updateChangeToken();
        dispatchNamedUserUpdateJob();
    }

    /**
     * Sets the named user ID.
     * <p>
     * To associate the named user ID, its length must be greater than 0 and less than 129 characters.
     * To disassociate the named user ID, its value must be empty or null.
     *
     * @param namedUserId The named user ID string.
     */
    public void setId(@Nullable @Size(max = MAX_NAMED_USER_ID_LENGTH) String namedUserId) {
        if (namedUserId != null && !isDataCollectionEnabled()) {
            Logger.debug("NamedUser - Data collection is disabled, ignoring named user association.");
            return;
        }

        String id = null;

        // Treat empty namedUserId as a command to dissociate
        if (!UAStringUtil.isEmpty(namedUserId)) {
            id = namedUserId.trim();

            // Treat namedUserId trimmed to empty as invalid
            if (UAStringUtil.isEmpty(id) || id.length() > MAX_NAMED_USER_ID_LENGTH) {
                Logger.error("Failed to set named user ID. The named user ID must be composed" +
                        "of non-whitespace characters and be less than 129 characters in length.");
                return;
            }
        }

        synchronized (idLock) {
            if (!UAStringUtil.equals(getId(), id)) {
                // New/Cleared Named User, clear pending updates and update the token and ID
                preferenceDataStore.put(NAMED_USER_ID_KEY, id);
                updateChangeToken();
                clearPendingNamedUserUpdates();
                dispatchNamedUserUpdateJob();

                // ID changed, update CRA
                if (id != null) {
                    airshipChannel.updateRegistration();
                }

            } else {
                Logger.debug("NamedUser - Skipping update. Named user ID trimmed already matches existing named user: %s", getId());
            }
        }
    }

    /**
     * Edit the named user tags.
     *
     * @return The TagGroupsEditor.
     */
    @NonNull
    public TagGroupsEditor editTagGroups() {
        return new TagGroupsEditor() {
            @Override
            protected void onApply(@NonNull List<TagGroupsMutation> collapsedMutations) {
                if (!isDataCollectionEnabled()) {
                    Logger.warn("NamedUser - Unable to apply tag group edits when data collection is disabled.");
                    return;
                }

                if (!collapsedMutations.isEmpty()) {
                    tagGroupRegistrar.addMutations(TagGroupRegistrar.NAMED_USER, collapsedMutations);
                    dispatchNamedUserUpdateJob();
                }
            }
        };
    }

    /**
     * Edit the attributes associated with the named user.
     *
     * @return An {@link AttributeEditor}.
     */
    @NonNull
    public AttributeEditor editAttributes() {
        return new AttributeEditor() {
            @Override
            protected void onApply(@NonNull List<AttributeMutation> mutations) {
                if (!isDataCollectionEnabled()) {
                    Logger.info("Ignore attributes, data opted out.");
                    return;
                }

                List<PendingAttributeMutation> pendingMutations = PendingAttributeMutation.fromAttributeMutations(mutations, System.currentTimeMillis());

                // Add mutations to store
                attributeMutationStore.add(pendingMutations);
                dispatchNamedUserUpdateJob();
            }
        };
    }


    @VisibleForTesting
    boolean isIdUpToDate() {
        synchronized (idLock) {
            String changeToken = getChangeToken();
            String lastUpdatedToken = preferenceDataStore.getString(LAST_UPDATED_TOKEN_KEY, null);
            String currentId = getId();

            if (currentId == null && changeToken == null) {
                return true;
            }

            return lastUpdatedToken != null && lastUpdatedToken.equals(changeToken);
        }
    }

    /**
     * Gets the named user ID change token.
     *
     * @return The named user ID change token.
     */
    @Nullable
    private String getChangeToken() {
        return preferenceDataStore.getString(CHANGE_TOKEN_KEY, null);
    }

    /**
     * Modify the change token to force an update.
     */
    private void updateChangeToken() {
        preferenceDataStore.put(CHANGE_TOKEN_KEY, UUID.randomUUID().toString());
    }

    /**
     * Dispatches a job to update the named user.
     */
    void dispatchNamedUserUpdateJob() {
        JobInfo jobInfo = JobInfo.newBuilder()
                                 .setAction(ACTION_UPDATE_NAMED_USER)
                                 .setId(JobInfo.NAMED_USER_UPDATE_ID)
                                 .setNetworkAccessRequired(true)
                                 .setAirshipComponent(NamedUser.class)
                                 .build();

        jobDispatcher.dispatch(jobInfo);
    }

    private void clearPendingNamedUserUpdates() {
        Logger.verbose("Clearing pending Named Users tag updates.");
        tagGroupRegistrar.clearMutations(TagGroupRegistrar.NAMED_USER);
    }

    @Override
    protected void onDataCollectionEnabledChanged(boolean isDataCollectionEnabled) {
        if (!isDataCollectionEnabled) {
            clearPendingNamedUserUpdates();
            setId(null);
        }
    }

    /**
     * Handles named user update job.
     *
     * @return The job result.
     */
    @JobInfo.JobResult
    @WorkerThread
    private int onUpdateNamedUser() {
        String channelId = airshipChannel.getId();
        if (UAStringUtil.isEmpty(channelId)) {
            Logger.verbose("NamedUser - The channel ID does not exist. Will retry when channel ID is available.");
            return JobInfo.JOB_FINISHED;
        }

        // Update ID
        if (!isIdUpToDate()) {
            int result = updateNamedUserId(channelId);
            if (result != JobInfo.JOB_FINISHED) {
                return result;
            }
        }

        // Update tag groups and attributes
        String currentId = getId();
        if (isIdUpToDate() && currentId != null) {
            int tagResult = updateTagGroups(currentId);
            int attributeResult = updateAttributes(currentId);

            if (tagResult == JobInfo.JOB_RETRY || attributeResult == JobInfo.JOB_RETRY) {
                return JobInfo.JOB_RETRY;
            }
        }

        return JobInfo.JOB_FINISHED;
    }

    /**
     * Handles associate/disassociate updates.
     *
     * @return The job result.
     */
    @JobInfo.JobResult
    @WorkerThread
    private int updateNamedUserId(@NonNull String channelId) {
        String changeToken;
        String namedUserId;

        synchronized (idLock) {
            changeToken = getChangeToken();
            namedUserId = getId();
        }

        Response<Void> response;
        try {
            response = namedUserId == null ? namedUserApiClient.disassociate(channelId)
                    : namedUserApiClient.associate(namedUserId, channelId);
        } catch (RequestException e) {
            // Server error occurred, so retry later.
            Logger.debug(e, "NamedUser - Update named user failed, will retry.");
            return JobInfo.JOB_RETRY;
        }

        // 500 | 429
        if (response.isServerError() || response.isTooManyRequestsError()) {
            Logger.debug("Update named user failed. Too many requests. Will retry.");
            return JobInfo.JOB_RETRY;
        }

        // 403
        if (response.getStatus() == HttpURLConnection.HTTP_FORBIDDEN) {
            Logger.debug("Update named user failed with response: %s." +
                    "This action is not allowed when the app is in server-only mode.", response);
            return JobInfo.JOB_FINISHED;
        }

        // 2xx
        if (response.isSuccessful()) {
            Logger.debug("Update named user succeeded with status: %s", response.getStatus());
            preferenceDataStore.put(LAST_UPDATED_TOKEN_KEY, changeToken);
            return JobInfo.JOB_FINISHED;
        }

        // 4xx
        Logger.debug("Update named user failed with response: %s", response);
        return JobInfo.JOB_FINISHED;
    }

    /**
     * Sends any pending tag groups.
     *
     * @return The job result.
     */
    @WorkerThread
    @JobInfo.JobResult
    private int updateTagGroups(@NonNull String namedUserId) {
        if (tagGroupRegistrar.uploadMutations(TagGroupRegistrar.NAMED_USER, namedUserId)) {
            return JobInfo.JOB_FINISHED;
        }
        return JobInfo.JOB_RETRY;
    }

    /**
     * Sends any pending attribute changes.
     *
     * @return The job result.
     */
    @JobInfo.JobResult
    @WorkerThread
    private int updateAttributes(@NonNull String namedUserId) {
        while (isIdUpToDate()) {
            // Collapse mutations before we try to send any updates
            attributeMutationStore.collapseAndSaveMutations();

            List<PendingAttributeMutation> mutations = attributeMutationStore.peek();
            if (mutations == null) {
                break;
            }

            Response<Void> response;
            try {
                response = attributeApiClient.updateNamedUserAttributes(namedUserId, mutations);
            } catch (RequestException e) {
                Logger.debug(e, "NamedUser - Failed to update attributes");
                return JobInfo.JOB_RETRY;
            }

            Logger.debug("NamedUser - Updated attributes response: %s", response);
            if (response.isServerError() || response.isTooManyRequestsError()) {
                return JobInfo.JOB_RETRY;
            }

            if (response.isClientError()) {
                Logger.error("NamedUser - Dropping attributes %s due to error: %s message: %s", mutations, response.getStatus(), response.getResponseBody());
            }

            attributeMutationStore.pop();
        }

        return JobInfo.JOB_FINISHED;
    }
}
