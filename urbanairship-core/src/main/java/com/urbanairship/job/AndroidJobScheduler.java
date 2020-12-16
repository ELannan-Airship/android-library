package com.urbanairship.job;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import com.urbanairship.Logger;
import com.urbanairship.util.ManifestUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Job scheduler using the Android Job's API.
 *
 * @hide
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
class AndroidJobScheduler implements Scheduler {

    private static final long INITIAL_RETRY_MS = 30000; // 30 seconds.
    private static final long DEFAULT_DELAY_MS = 10000; // 10 seconds.
    private JobScheduler scheduler;

    @Override
    public void cancel(@NonNull Context context, int schedulerId) {
        JobScheduler scheduler = getScheduler(context);
        if (scheduler != null) {
            scheduler.cancel(schedulerId);
        }
    }

    @Override
    public void reschedule(@NonNull Context context, @NonNull JobInfo jobInfo, int schedulerId, @Nullable Bundle extras) throws SchedulerException {
        scheduleJob(context, jobInfo, schedulerId, INITIAL_RETRY_MS);
    }

    @Override
    public void schedule(@NonNull Context context, @NonNull JobInfo jobInfo, int schedulerId) throws SchedulerException {
        if (jobInfo.isNetworkAccessRequired() || jobInfo.getInitialDelay() > 0) {
            scheduleJob(context, jobInfo, schedulerId, jobInfo.getInitialDelay());
        } else {
            scheduleJob(context, jobInfo, schedulerId, DEFAULT_DELAY_MS);
        }
    }

    /**
     * Helper method to schedule a jobInfo with the Android JobScheduler.
     *
     * @param context The application context.
     * @param jobInfo The jobInfo.
     * @param scheduleId The schedule ID.
     * @param millisecondsDelay Minimum amount of time in milliseconds to delay the jobInfo.
     * @throws SchedulerException if the schedule fails.
     */
    private void scheduleJob(@NonNull Context context, @NonNull JobInfo jobInfo, int scheduleId, long millisecondsDelay) throws SchedulerException {
        JobScheduler scheduler = getScheduler(context);
        if (scheduler == null) {
            return;
        }

        try {
            android.app.job.JobInfo androidJobInfo = createAndroidJobInfo(context, jobInfo, scheduleId, millisecondsDelay);
            if (scheduler.schedule(androidJobInfo) == JobScheduler.RESULT_FAILURE) {
                throw new SchedulerException("Android JobScheduler failed to schedule job.");
            }
            Logger.verbose("AndroidJobScheduler: Scheduling jobInfo: %s scheduleId: %s", jobInfo, scheduleId);
        } catch (Exception e) {
            throw new SchedulerException("Android JobScheduler failed to schedule job: ", e);
        }
    }

    @SuppressLint("MissingPermission")
    private android.app.job.JobInfo createAndroidJobInfo(@NonNull Context context, @NonNull JobInfo jobInfo, int scheduleId, long millisecondsDelay) {
        ComponentName component = new ComponentName(context, AndroidJobService.class);
        android.app.job.JobInfo.Builder builder = new android.app.job.JobInfo.Builder(scheduleId, component)
                .setExtras(jobInfo.toPersistableBundle());

        if (millisecondsDelay > 0) {
            builder.setMinimumLatency(millisecondsDelay);
        }

        if (jobInfo.isPersistent() && ManifestUtils.isPermissionGranted(Manifest.permission.RECEIVE_BOOT_COMPLETED)) {
            builder.setPersisted(true);
        }

        if (jobInfo.isNetworkAccessRequired()) {
            builder.setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY);
        }

        return builder.build();
    }

    /**
     * Gets the job scheduler.
     *
     * @param context The application context.
     * @return The job scheduler.
     */
    private JobScheduler getScheduler(@NonNull Context context) {
        if (scheduler == null) {
            scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        }

        return scheduler;
    }

}
