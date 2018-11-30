package com.treasuredata.android.billing.internal;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import com.treasuredata.android.TreasureData;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.treasuredata.android.billing.internal.PurchaseConstants.IAP_INTRO_PRICE_PERIOD;
import static com.treasuredata.android.billing.internal.PurchaseConstants.INAPP;
import static com.treasuredata.android.billing.internal.PurchaseConstants.SUBSCRIPTION;

public class PurchaseEventActivityLifecycleTracker {
    private static final String TAG = PurchaseEventActivityLifecycleTracker.class.getSimpleName();

    private static final String BILLING_ACTIVITY_NAME =
            "com.android.billingclient.api.ProxyBillingActivity";

    private static Boolean hasBillingService = null;
    private static boolean hasBillingActivity = false;
    private static ServiceConnection serviceConnection;
    private static Application.ActivityLifecycleCallbacks callbacks;
    private static Intent serviceIntent;
    private static Object inAppBillingObj;

    private static final AtomicBoolean isTracking = new AtomicBoolean(false);
    private static TreasureData treasureData;

    private PurchaseEventActivityLifecycleTracker() {

    }

    public static void update(TreasureData treasureData) {
        PurchaseEventActivityLifecycleTracker.treasureData = treasureData;
        initialize();
        if (!hasBillingService) {
            return;
        }

        if (!isTracking.compareAndSet(false, true)) {
            return;
        }

        final Context context = TreasureData.getApplicationContext();
        if (context instanceof Application) {
            Application application = (Application) context;
            application.registerActivityLifecycleCallbacks(callbacks);
            List<ResolveInfo> intentServices = context.getPackageManager().queryIntentServices(serviceIntent, 0);
            if (intentServices != null && !intentServices.isEmpty()) {
                // Service available to handle that Intent
                context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            }
            else {
                Log.e(TAG, "Billing service is unavailable on device");
            }
        }
    }

    private static void initialize() {
        if (isInitialized()) {
            return;
        }

        hasBillingService = BillingDelegate.hasBillingService();

        if (!hasBillingService) {
            return;
        }

        try {
            Class.forName(BILLING_ACTIVITY_NAME);
            hasBillingActivity = true;
        } catch (ClassNotFoundException ignored) {
            hasBillingActivity = false;
        }

        PurchaseEventManager.clearAllSkuDetailsCache();

        serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND")
                .setPackage("com.android.vending");

        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                inAppBillingObj = BillingDelegate.asInterface(TreasureData.getApplicationContext(), service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };

        callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

            }

            @Override
            public void onActivityStarted(Activity activity) {

            }

            @Override
            public void onActivityResumed(Activity activity) {
                TreasureData.getExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        final Context context = TreasureData.getApplicationContext();

                        // Log Purchase In app type (One-time product) for the app using In-app Billing with AIDL
                        // (https://developer.android.com/google/play/billing/api)
                        List<String> purchasesInapp = PurchaseEventManager
                                .getPurchasesInapp(context, inAppBillingObj);
                        trackPurchases(context, purchasesInapp, INAPP);

                        // Log Purchase subscriptions type
                        List<String> purchasesSubs = PurchaseEventManager
                                .getPurchasesSubs(context, inAppBillingObj);
                        trackPurchases(context, purchasesSubs, SUBSCRIPTION);
                    }
                });
            }

            @Override
            public void onActivityPaused(Activity activity) {

            }

            @Override
            public void onActivityStopped(Activity activity) {
                // Log Purchase In app type (One-time product) for the app using the Google Play Billing Library
                // (https://developer.android.com/google/play/billing/billing_library_overview)
                if (hasBillingActivity
                        && activity.getLocalClassName().equals(BILLING_ACTIVITY_NAME)) {
                    TreasureData.getExecutor().execute(new Runnable() {
                        @Override
                        public void run() {
                            final Context context = TreasureData.getApplicationContext();

                            // First, retrieve the One-time products which have not been consumed
                            List<String> purchases = PurchaseEventManager
                                    .getPurchasesInapp(context, inAppBillingObj);

                            // Second, retrieve the One-time products which have been consumed
                            if (purchases.isEmpty()) {
                                purchases = PurchaseEventManager
                                        .getPurchaseHistoryInapp(context, inAppBillingObj);
                            }

                            trackPurchases(context, purchases, INAPP);
                        }
                    });
                }
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

            }

            @Override
            public void onActivityDestroyed(Activity activity) {

            }
        };
    }

    private static boolean isInitialized()
    {
        return hasBillingService != null;
    }

    private static void trackPurchases(final Context context, List<String> purchases, String type) {
        if (purchases.isEmpty()) {
            return;
        }

        final Map<String, String> purchaseMap = new HashMap<>();
        List<String> skuList = new ArrayList<>();
        for (String purchase : purchases) {
            try {
                JSONObject purchaseJson = new JSONObject(purchase);
                String sku = purchaseJson.getString("productId");
                purchaseMap.put(sku, purchase);

                skuList.add(sku);
            }
            catch (JSONException e){
                Log.e(TAG, "Unable to parse purchase, not a json object:.", e);
            }
        }

        final Map<String, String> skuDetailsMap = PurchaseEventManager.getAndCacheSkuDetails(
                context, inAppBillingObj, skuList, type);

        for (Map.Entry<String, String> entry : skuDetailsMap.entrySet()) {
            String purchase = purchaseMap.get(entry.getKey());
            String skuDetails = entry.getValue();
            trackPurchases(purchase, skuDetails);
        }
    }

    private static void trackPurchases(String purchase, String skuDetails) {
        try {
            JSONObject purchaseJSON = new JSONObject(purchase);
            JSONObject skuDetailsJSON = new JSONObject(skuDetails);
            Map<String, Object> record = new HashMap<>();
            record.put(PurchaseConstants.EVENT_KEY, PurchaseConstants.IAP_EVENT_NAME);

            String productId = purchaseJSON.getString("productId");
            String orderId = purchaseJSON.optString("orderId");
            String title = skuDetailsJSON.optString("title");
            String price = skuDetailsJSON.getString("price");
            Long priceAmountMicros = skuDetailsJSON.getLong("price_amount_micros");
            String currency = skuDetailsJSON.getString("price_currency_code");
            String description = skuDetailsJSON.optString("description");
            String type = skuDetailsJSON.optString("type");
            Integer purchaseState = purchaseJSON.optInt("purchaseState");
            String developerPayload = purchaseJSON.optString("developerPayload");
            Long purchaseTime = purchaseJSON.getLong("purchaseTime");
            String purchaseToken = purchaseJSON.getString("purchaseToken");
            String packageName = purchaseJSON.optString("packageName");

            record.put(PurchaseConstants.IAP_PRODUCT_ID, productId);
            record.put(PurchaseConstants.IAP_ORDER_ID, orderId);
            record.put(PurchaseConstants.IAP_PRODUCT_TITLE, title);
            record.put(PurchaseConstants.IAP_PRODUCT_PRICE, price);
            record.put(PurchaseConstants.IAP_PRODUCT_PRICE_AMOUNT_MICROS, priceAmountMicros);
            record.put(PurchaseConstants.IAP_PRODUCT_CURRENCY, currency);

            // Quantity is always 1 for Android IAP purchase
            record.put(PurchaseConstants.IAP_QUANTITY, 1);
            record.put(PurchaseConstants.IAP_PRODUCT_TYPE, type);
            record.put(PurchaseConstants.IAP_PRODUCT_DESCRIPTION, description);
            record.put(PurchaseConstants.IAP_PURCHASE_STATE, purchaseState);
            record.put(PurchaseConstants.IAP_PURCHASE_DEVELOPER_PAYLOAD, developerPayload);
            record.put(PurchaseConstants.IAP_PURCHASE_TIME, purchaseTime);
            record.put(PurchaseConstants.IAP_PURCHASE_TOKEN, purchaseToken);
            record.put(PurchaseConstants.IAP_PACKAGE_NAME, packageName);

            if (type.equals(SUBSCRIPTION)) {
                String subcriptionType = purchaseJSON.optString("subscriptionType");
                Boolean autoRenewing = purchaseJSON.optBoolean("autoRenewing",
                                false);
                String subscriptionPeriod = skuDetailsJSON.optString("subscriptionPeriod");
                String freeTrialPeriod = skuDetailsJSON.optString("freeTrialPeriod");
                String introductoryPricePeriod = skuDetailsJSON.optString("introductoryPricePeriod");

                record.put(PurchaseConstants.IAP_SUBSCRIPTION_TYPE, subcriptionType);
                record.put(PurchaseConstants.IAP_SUBSCRIPTION_AUTORENEWING, autoRenewing);
                record.put(PurchaseConstants.IAP_SUBSCRIPTION_PERIOD, subscriptionPeriod);
                record.put(PurchaseConstants.IAP_FREE_TRIAL_PERIOD, freeTrialPeriod);

                if (!introductoryPricePeriod.isEmpty()) {
                    record.put(IAP_INTRO_PRICE_PERIOD, introductoryPricePeriod);
                    Long introductoryPriceCycles = skuDetailsJSON.optLong("introductoryPriceCycles");
                    record.put(PurchaseConstants.IAP_INTRO_PRICE_CYCLES, introductoryPriceCycles);
                    Long introductoryPriceAmountMicros = skuDetailsJSON.getLong("introductoryPriceAmountMicros");
                    record.put(PurchaseConstants.IAP_INTRO_PRICE_AMOUNT_MICROS, introductoryPriceAmountMicros);
                }
            }

            record.put(TreasureData.EVENT_KEY_IN_APP_PURCHASE_EVENT_PRIVATE, true);

            String targetDatabase = TreasureData.getTdDefaultDatabase();
            if (treasureData.getDefaultDatabase() == null) {
                Log.w(TAG, "Default database is not set, iap event will be uploaded to " + targetDatabase);
            } else {
                targetDatabase = treasureData.getDefaultDatabase();
            }

            String targetTable = TreasureData.getTdDefaultTable();
            if (treasureData.getDefaultTable() == null) {
                Log.w(TAG, "Default table is not set, iap event will be uploaded to " + targetTable);
            } else {
                targetTable = treasureData.getDefaultTable();
            }
            treasureData.addEvent(targetDatabase, targetTable, record);
        } catch (JSONException e) {
            Log.e(TAG, "Unable to parse purchase, not a json object:", e);
        }
    }
}
