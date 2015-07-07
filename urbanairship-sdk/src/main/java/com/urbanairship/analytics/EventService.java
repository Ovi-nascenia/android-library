/*
Copyright 2009-2015 Urban Airship Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE URBAN AIRSHIP INC ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
EVENT SHALL URBAN AIRSHIP INC OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.urbanairship.analytics;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.urbanairship.Autopilot;
import com.urbanairship.Logger;
import com.urbanairship.UAirship;
import com.urbanairship.location.RegionEvent;

import java.util.Map;

/**
 * The EventService is an IntentService designed to handle periodic analytics event
 * uploads and saving events to be uploaded.
 */
public class EventService extends IntentService {

    /**
     * Intent action to send an event.
     */
    static final String ACTION_SEND = "com.urbanairship.analytics.SEND";

    /**
     * Intent action to add an event.
     */
    static final String ACTION_ADD = "com.urbanairship.analytics.ADD";

    /**
     * Intent action to delete all locally stored events.
     */
    static final String ACTION_DELETE_ALL = "com.urbanairship.analytics.DELETE_ALL";

    /**
     * Intent extra for the event's type.
     */
    static final String EXTRA_EVENT_TYPE = "EXTRA_EVENT_TYPE";

    /**
     * Intent extra for the event's ID.
     */
    static final String EXTRA_EVENT_ID = "EXTRA_EVENT_ID";

    /**
     * Intent extra for the event's data.
     */
    static final String EXTRA_EVENT_DATA = "EXTRA_EVENT_DATA";

    /**
     * Intent extra for the event's time stamp.
     */
    static final String EXTRA_EVENT_TIME_STAMP = "EXTRA_EVENT_TIME_STAMP";

    /**
     * Intent extra for the event's session ID.
     */
    static final String EXTRA_EVENT_SESSION_ID = "EXTRA_EVENT_SESSION_ID";

    /**
     * Batch delay for region events in milliseconds.
     */
    private static final long REGION_BATCH_DELAY = 1000; // 1s

    /**
     * Batch delay for normal priority events in milliseconds.
     */
    private static final long BATCH_DELAY = 10000; // 10s

    private static long backoffMs = 0;

    private EventAPIClient eventClient;

    public EventService() {
        this("EventService");
    }

    public EventService(String serviceName) {
        this(serviceName, new EventAPIClient());
    }

    EventService(String serviceName, EventAPIClient eventClient) {
        super(serviceName);
        this.eventClient = eventClient;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Autopilot.automaticTakeOff(getApplicationContext());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        Logger.verbose("EventService - Received intent: " + intent.getAction());

        switch (intent.getAction()) {
            case ACTION_DELETE_ALL:
                Logger.info("Deleting all analytic events.");
                UAirship.shared().getAnalytics().getDataManager().deleteAllEvents();
                break;
            case ACTION_ADD:
                addEventFromIntent(intent);
                break;
            case ACTION_SEND:
                uploadEvents();
                break;
            default:
                Logger.warn("EventService - Unrecognized intent action: " + intent.getAction());
                break;
        }
    }

    /**
     * Adds an event from an intent to the database.
     *
     * @param intent An intent containing the event's content values to be added
     * to the database.
     */
    private void addEventFromIntent(Intent intent) {
        AnalyticsPreferences preferences = UAirship.shared().getAnalytics().getPreferences();
        EventDataManager dataManager = UAirship.shared().getAnalytics().getDataManager();

        String eventType = intent.getStringExtra(EXTRA_EVENT_TYPE);
        String eventId = intent.getStringExtra(EXTRA_EVENT_ID);
        String eventData = intent.getStringExtra(EXTRA_EVENT_DATA);
        String eventTimeStamp = intent.getStringExtra(EXTRA_EVENT_TIME_STAMP);
        String sessionId = intent.getStringExtra(EXTRA_EVENT_SESSION_ID);

        if (eventType == null || eventData == null || eventTimeStamp == null || eventId == null) {
            Logger.warn("Event service unable to add event with missing data.");
            return;
        }

        // Handle database max size exceeded
        if (dataManager.getDatabaseSize() > preferences.getMaxTotalDbSize()) {
            Logger.info("Event database size exceeded. Deleting oldest session.");
            String oldestSessionId = dataManager.getOldestSessionId();
            if (oldestSessionId != null && oldestSessionId.length() > 0) {
                dataManager.deleteSession(oldestSessionId);
            }
        }

        if (dataManager.insertEvent(eventType, eventData, eventId, sessionId, eventTimeStamp) <= 0) {
            Logger.error("EventService - Unable to insert event into database.");
        }

        // In the case of a location event
        if (LocationEvent.TYPE.equals(eventType) && !UAirship.shared().getAnalytics().isAppInForeground()) {
            long currentTime = System.currentTimeMillis();
            long lastSendTime = preferences.getLastSendTime();
            long sendDelta = currentTime - lastSendTime;
            long throttleDelta = UAirship.shared().getAirshipConfigOptions().backgroundReportingIntervalMS;
            long minimumWait = throttleDelta - sendDelta;

            if (minimumWait > getNextSendDelay() && minimumWait > BATCH_DELAY) {
                Logger.info("LocationEvent was inserted, but may not be updated until " + minimumWait + " ms have passed");
                scheduleEventUpload(minimumWait);
            } else {
                scheduleEventUpload(Math.max(getNextSendDelay(), BATCH_DELAY));
            }
        } else if (RegionEvent.TYPE.equals(eventType)) {
            scheduleEventUpload(REGION_BATCH_DELAY);
        } else {
            scheduleEventUpload(Math.max(getNextSendDelay(), BATCH_DELAY));
        }
    }

    /**
     * Uploads events.
     */
    private void uploadEvents() {
        AnalyticsPreferences preferences = UAirship.shared().getAnalytics().getPreferences();
        EventDataManager dataManager = UAirship.shared().getAnalytics().getDataManager();

        preferences.setLastSendTime(System.currentTimeMillis());

        final int eventCount = dataManager.getEventCount();

        if (eventCount <= 0) {
            Logger.debug("EventService - No events to send. Ending analytics upload.");
            return;
        }

        final int avgSize = dataManager.getDatabaseSize() / eventCount;

        //pull enough events to fill a batch (roughly)
        Map<String, String> events = dataManager.getEvents(preferences.getMaxBatchSize() / avgSize);

        EventResponse response = eventClient.sendEvents(events.values());

        boolean isSuccess = response != null && response.getStatus() == 200;

        if (isSuccess) {
            Logger.info("Analytic events uploaded successfully.");
            dataManager.deleteEvents(events.keySet());
            backoffMs = 0;
        } else {

            if (backoffMs == 0) {
                backoffMs = preferences.getMinBatchInterval();
            } else {
                backoffMs = Math.min(backoffMs * 2, preferences.getMaxWait());
            }

            Logger.debug("Analytic events failed to send. Will retry in " + backoffMs + "ms.");
        }

        // If there are still events left, schedule the next send
        if (!isSuccess || eventCount - events.size() > 0) {
            Logger.debug("EventService - Scheduling next event batch upload.");
            scheduleEventUpload(getNextSendDelay());
        }

        if (response != null) {
            preferences.setMaxTotalDbSize(response.getMaxTotalSize());
            preferences.setMaxBatchSize(response.getMaxBatchSize());
            preferences.setMaxWait(response.getMaxWait());
            preferences.setMinBatchInterval(response.getMinBatchInterval());
        }
    }

    /**
     * Gets the next upload delay in milliseconds.
     *
     * @return A delay in ms for the time the events should be sent.
     */
    private long getNextSendDelay() {
        AnalyticsPreferences preferences = UAirship.shared().getAnalytics().getPreferences();
        long nextSendTime = preferences.getLastSendTime() + preferences.getMinBatchInterval() + backoffMs;
        return Math.max(nextSendTime - System.currentTimeMillis(), 0);
    }

    /**
     * Schedule a batch event upload at a given time in the future.
     *
     * @param milliseconds The milliseconds from the current time to schedule the event upload.
     */
    private void scheduleEventUpload(final long milliseconds) {
        long sendTime = System.currentTimeMillis() + milliseconds;

        AnalyticsPreferences preferences = UAirship.shared().getAnalytics().getPreferences();
        AlarmManager alarmManager = (AlarmManager) getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(getApplicationContext(), EventService.class);
        intent.setAction(EventService.ACTION_SEND);

        long previousScheduledTime = preferences.getScheduledSendTime();

        // Check if we should reschedule - previousAlarmTime is older than now or greater then the new send time
        boolean reschedule = previousScheduledTime < System.currentTimeMillis() || previousScheduledTime > sendTime;

        // Schedule the alarm if we need to either reschedule or an existing pending intent does not exist
        if (reschedule || PendingIntent.getService(getApplicationContext(), 0, intent, PendingIntent.FLAG_NO_CREATE) == null) {
            PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            // Reschedule the intent
            alarmManager.set(AlarmManager.RTC, sendTime, pendingIntent);
            preferences.setScheduledSendTime(sendTime);
        } else {
            Logger.verbose("EventService - Alarm already scheduled for an earlier time.");
        }
    }
}