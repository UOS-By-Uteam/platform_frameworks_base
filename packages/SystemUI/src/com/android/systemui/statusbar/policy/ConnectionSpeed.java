/*
 * Copyright (C) 2017 The UOS Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import java.text.DecimalFormat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

/*
*
* When enabled, it displays a text-view in the statusbar indicates the speed of the connection
* This is a re-worked from TrafficMonitor, as we see that this is too complicated, people just
* want to see their downloading speed, we cleaned up the code a little bit so that it doesn't
* confuse the developer.
*
*/
public class ConnectionSpeed extends TextView {
    private static final int KILOBYTE = 1024;

    private static DecimalFormat decimalFormat = new DecimalFormat("0.00");
    private static DecimalFormat mediumDecimalFormat = new DecimalFormat("##.0");
    private static DecimalFormat largeDecimalFormat = new DecimalFormat("###");
    static {
        decimalFormat.setMaximumIntegerDigits(1);
        decimalFormat.setMaximumFractionDigits(2);
        mediumDecimalFormat.setMaximumIntegerDigits(2);
        mediumDecimalFormat.setMaximumFractionDigits(1);
        largeDecimalFormat.setMaximumIntegerDigits(3);
        largeDecimalFormat.setMaximumFractionDigits(0);
    }

    private int mState = 0;
    private boolean mAttached;
    private long totalRxBytes;
    private long lastUpdateTime;
    private int txtSize;
    private int KB = KILOBYTE;
    private int MB = KB * KB;
    private int GB = MB * KB;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (timeDelta < getInterval(mState) * .95) {
                if (msg.what != 1) {
                    // we just updated the view, nothing further to do
                    return;
                }
                if (timeDelta < 1) {
                    // Can't div by 0 so make sure the value displayed is minimal
                    timeDelta = Long.MAX_VALUE;
                }
            }
            lastUpdateTime = SystemClock.elapsedRealtime();

            // Calculate the data rate from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long rxData = newTotalRxBytes - totalRxBytes;
            if (!getConnectAvailable()) {
                clearHandlerCallbacks();
                setVisibility(View.GONE);
            } else if(mState == 1) {
                String symbol = "/s";
                // Get information for uplink ready so the line return can be added
                String output = "";
                output = formatOutput(timeDelta, rxData, symbol);

                // Update view if there's anything new to show
                if (!output.contentEquals(getText())) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float)txtSize);
                    setText(output);
                }
                setVisibility(View.VISIBLE);
            } else {
                setText("");
                setVisibility(View.GONE);
            }

            // Post delayed message to refresh in ~1000ms
            totalRxBytes = newTotalRxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.postDelayed(mRunnable, getInterval(mState));
        }

        private String formatOutput(long timeDelta, long data, String symbol) {
            long speed = (long)(data / (timeDelta / 1000F));
            if (speed < 10 * KB) {
                return decimalFormat.format(speed / (float)KB) + 'K' + symbol;
            } else if (speed >= 10 * KB && speed < 100 * KB) {
                return mediumDecimalFormat.format(speed / (float)KB) + 'K' + symbol;
            } else if (speed >= 100 * KB && speed < MB) {
                return largeDecimalFormat.format(speed / (float)KB) + 'K' + symbol;
            } else if (speed < 10 * MB) {
                return decimalFormat.format(speed / (float)MB) + 'M' + symbol;
            } else if (speed >= 10 * MB && speed < 100 * MB) {
                return mediumDecimalFormat.format(speed / (float)MB) + 'M' + symbol;
            } else if (speed >= 100 * MB && speed < GB) {
                return largeDecimalFormat.format(speed / (float)MB) + 'M' + symbol;
            }
            return decimalFormat.format(speed / (float)GB) + 'G' + symbol;
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            Uri uri = Settings.System.getUriFor(Settings.System.CONNECTION_SPEED_STATE);
            resolver.registerContentObserver(uri, false,
                    this, UserHandle.USER_ALL);
        }

        /*
         *  @hide
         */
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    /*
     *  @hide
     */
    public ConnectionSpeed(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public ConnectionSpeed(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public ConnectionSpeed(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        txtSize = resources.getDimensionPixelSize(R.dimen.connection_speed_text_size);
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                updateSettings();
            }
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null && network.isConnected();
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mState = Settings.System.getInt(resolver, Settings.System.CONNECTION_SPEED_STATE, 0);

        if (mState == 1) {
            if (getConnectAvailable()) {
                if (mAttached) {
                    totalRxBytes = TrafficStats.getTotalRxBytes();
                    lastUpdateTime = SystemClock.elapsedRealtime();
                    mTrafficHandler.sendEmptyMessage(1);
                }
                setVisibility(View.VISIBLE);
                return;
            }
        } else {
            clearHandlerCallbacks();
        }
        setVisibility(View.GONE);
    }

    private static int getInterval(int intState) {
        int intInterval = intState >>> 16;
        return (intInterval >= 250 && intInterval <= 32750) ? intInterval : 1000;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable);
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }
}
