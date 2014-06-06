package com.treasuredata.android;

import android.content.Context;
import io.keen.client.java.KeenCallback;
import org.komamitsu.android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TreasureData {
    private static final String TAG = TreasureData.class.getSimpleName();
    private static final String LABEL_ADD_EVENT = "addEvent";
    private static final String LABEL_UPLOAD_EVENTS = "uploadEvents";
    private TDClient client;
    private volatile TDCallback addEventCallBack;
    private volatile TDCallback uploadEventsCallBack;
    private volatile KeenCallback addEventKeenCallBack = createKeenCallback(LABEL_ADD_EVENT, null);
    private volatile KeenCallback uploadEventsKeenCallBack = createKeenCallback(LABEL_UPLOAD_EVENTS, null);;

    public TreasureData(Context context, String apiKey) throws IOException {
        client = new TDClient(context, apiKey);
    }

    // Only for testing
    @Deprecated
    TreasureData() {
    }

    public synchronized void setAddEventCallBack(TDCallback callBack) {
        this.addEventCallBack = callBack;
        this.addEventKeenCallBack = createKeenCallback(LABEL_ADD_EVENT, callBack);
    }

    public TDCallback getAddEventCallBack() {
        return this.addEventCallBack;
    }

    public synchronized void setUploadEventsCallBack(TDCallback callBack) {
        this.uploadEventsCallBack = callBack;
        this.uploadEventsKeenCallBack = createKeenCallback(LABEL_UPLOAD_EVENTS, callBack);
    }

    public TDCallback getUploadEventsCallBack() {
        return this.uploadEventsCallBack;
    }

    public static void enableLogging() {
        TDClient.enableLogging();
    }

    public static void disableLogging() {
        TDClient.disableLogging();
    }

    public static void initializeApiEndpoint(String apiEndpoint) {
        TDClient.setApiEndpoint(apiEndpoint);
    }

    public void addEvent(String database, String table, String key, String value) {
        HashMap<String, Object> record = new HashMap<String, Object>(1);
        record.put(key, value);
        addEvent(database, table, record);
    }

    public void addEvent(String database, String table, Map<String, Object> record) {
        StringBuilder sb = new StringBuilder();
        sb.append(database).append(".").append(table);
        client.queueEvent(null, sb.toString(), record, null, this.addEventKeenCallBack);
    }

    public void uploadEvents() {
        client.sendQueuedEventsAsync(null, uploadEventsKeenCallBack);
    }

    private static KeenCallback createKeenCallback(final String methodName, final TDCallback callback) {
       KeenCallback keenCallback = new KeenCallback() {
            @Override
            public void onSuccess() {
                if (callback != null) {
                    callback.onSuccess();
                }
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, methodName + " failed: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e);
                }
            }
        };
        return keenCallback;
    }

    void setClient(TDClient mockClient) {
        this.client = mockClient;
    }
}
