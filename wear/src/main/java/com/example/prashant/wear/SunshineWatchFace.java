/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.prashant.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import android.text.format.DateFormat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
                        Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        private static final String TAG = "WatchFace_Service";

        private static final String KEY_HIGH_TEMP = "max_temp";
        private static final String KEY_LOW_TEMP = "min_temp";
        private static final String KEY_WEATHER_ID = "weather_id";
        private static final String KEY_PATH = "/wearable";

        GoogleApiClient googleApiClient;

        private int mWeatherId = 0;
        private String mMaxTemperature;
        private String mMinTemperature;

        Paint mBackgroundPaint;
        //Paint mTextPaint;
        //boolean mAmbient;
        Calendar mCalendar;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mDatePaint;
        Paint mColonPaint;
        Paint mMeridiemPaint;
        Paint mDividerLinePaint;
        Paint mWeatherIconPaint;
        Paint mLowTempPaint;
        Paint mHighTempPaint;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormat();
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mLineHeight;
        String mAmString;
        String mPmString;

        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        Bitmap weatherIcon;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    //.setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mXOffset = resources.getDimension(R.dimen.digital_x_offset);

            int timeColor = resources.getColor(R.color.digital_time_color);
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            //mTextPaint = new Paint();
            //mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_date_color));
            mHourPaint = createTextPaint(timeColor, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(timeColor, BOLD_TYPEFACE);
            mColonPaint = createTextPaint(timeColor);
            mMeridiemPaint = createTextPaint(resources.getColor(R.color.digital_Meridiem_color));

            mDividerLinePaint = new Paint();
            mDividerLinePaint.setColor(resources.getColor(R.color.digital_divider_color));

            mWeatherIconPaint = new Paint();
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_high_temp_color));
            mLowTempPaint = createTextPaint(resources.getColor(R.color.digital_low_temp_color));

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            googleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API).addOnConnectionFailedListener(this)
                    .addConnectionCallbacks(this).build();

            initFormat();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        private void initFormat() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                googleApiClient.connect();

                // Update time date and zone in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormat();
                invalidate();
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            /*mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);*/
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float MeridiemSize = resources.getDimension(isRound
                    ? R.dimen.digital_Meridiem_size_round : R.dimen.digital_Meridiem_size);

            float tempSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_size_round : R.dimen.digital_temp_size);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mMeridiemPaint.setTextSize(MeridiemSize);
            mHighTempPaint.setTextSize(tempSize);
            mLowTempPaint.setTextSize(tempSize);

            //mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            mMeridiemPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_Meridiem_color_ambient) :
                    getResources().getColor(R.color.digital_Meridiem_color));

            mDatePaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_date_color_ambient) :
                    getResources().getColor(R.color.digital_date_color));

            mDividerLinePaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_divider_color_ambient) :
                    getResources().getColor(R.color.digital_divider_color));

            mHighTempPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_high_temp_color_ambient) :
                    getResources().getColor(R.color.digital_high_temp_color));

            mLowTempPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_low_temp_color_ambient) :
                    getResources().getColor(R.color.digital_low_temp_color));


            if (mLowBitAmbient) {
                mDatePaint.setAntiAlias(!inAmbientMode);
                mHourPaint.setAntiAlias(!inAmbientMode);
                mMinutePaint.setAntiAlias(!inAmbientMode);
                mColonPaint.setAntiAlias(!inAmbientMode);
                mDividerLinePaint.setAntiAlias(!inAmbientMode);
                mWeatherIconPaint.setAntiAlias(!inAmbientMode);
                mHighTempPaint.setAntiAlias(!inAmbientMode);
                mLowTempPaint.setAntiAlias(!inAmbientMode);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getMeridiemString(int Meridiem) {
            return Meridiem == Calendar.AM ? mAmString : mPmString;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.

            /*String text = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);*/

            int centerAdjust = 10;
            String colonString =":";

            //onDraw hours
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }

                hourString = String.valueOf(hour);
            }
            canvas.drawText(hourString,
                    bounds.centerX() - (mHourPaint.measureText(hourString) + centerAdjust),
                    mYOffset,
                    mHourPaint);

            //onDraw Colon
            canvas.drawText(colonString,
                    bounds.centerX() -centerAdjust,
                    mYOffset,
                    mColonPaint);

            //onDraw minutes
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString,
                    bounds.centerX() + mColonPaint.measureText(colonString) - centerAdjust,
                    mYOffset,
                    mMinutePaint);

            // In unmuted interactive mode, draw a second blinking colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!is24Hour) {
                canvas.drawText(getMeridiemString(mCalendar.get(Calendar.AM_PM)),
                        bounds.centerX() + mMinutePaint.measureText(minuteString) + centerAdjust,
                        mYOffset,
                        mMeridiemPaint);
            }


            String formattedDate = mDayOfWeekFormat.format(mDate).toUpperCase();
            // Day of week
            canvas.drawText(formattedDate,
                    bounds.centerX() - (mDatePaint.measureText(formattedDate)) / 2,
                    mYOffset + mLineHeight,
                    mDatePaint);

            // onDraw horizontal divider
            int lineWidth = 70;
            canvas.drawLine(bounds.centerX() - lineWidth / 2,
                    mYOffset + (mLineHeight * 2),
                    bounds.centerX() + lineWidth / 2,
                    mYOffset + (mLineHeight * 2),
                    mDividerLinePaint);

            //onDraw weather Icon
            weatherIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_clear);
            float xImage = (bounds.width() / 6 + (bounds.width() / 6 - weatherIcon.getHeight()) / 2);
            if (isInAmbientMode()) {
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
                mWeatherIconPaint.setColorFilter(filter);
                canvas.drawBitmap(weatherIcon, xImage,
                        mYOffset + (mLineHeight * 2.5f),
                        mWeatherIconPaint);
            } else {
                mWeatherIconPaint.setColorFilter(null);
                canvas.drawBitmap(weatherIcon,
                        xImage,
                        mYOffset + (mLineHeight * 2.5f),
                        mWeatherIconPaint);
            }

            //onDraw weather detail
            String highTempText = String.format(getString(R.string.format_temperature), parseFloat(mMaxTemperature));
            String lowTempText = String.format(getString(R.string.format_temperature), parseFloat(mMinTemperature));
            canvas.drawText(highTempText,
                    bounds.centerX() - 30,
                    mYOffset + (mLineHeight * 3.6f),
                    mHighTempPaint);

            canvas.drawText(lowTempText,
                    bounds.centerX() + 35,
                    mYOffset + (mLineHeight * 3.6f),
                    mLowTempPaint);
        }

        public void processItem(DataItem dataItem)
        {
            if(KEY_PATH.equals(dataItem.getUri().getPath()))
            {
                DataMap map = DataMapItem.fromDataItem(dataItem).getDataMap();
                mMaxTemperature = map.getString(KEY_HIGH_TEMP);
                mMinTemperature = map.getString(KEY_LOW_TEMP);
                mWeatherId = map.getInt(KEY_WEATHER_ID);

                Log.d(TAG, "here is high temperature - " + mMaxTemperature);
                Log.d(TAG, "here is low temperature - " + mMinTemperature);
                Log.d(TAG, "here is weather id temperature - " + mWeatherId);

                invalidate();
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG, String.valueOf(connectionResult.getErrorCode()));
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "connected GoogleAPI");
            Wearable.DataApi.addListener(googleApiClient, onDataChangedListener);
            Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(OnConnectedResultCallback);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Suspended GoogleAPI");
        }

        private void releaseGoogleApiClient() {
            if (googleApiClient != null && googleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(googleApiClient, onDataChangedListener);
                googleApiClient.disconnect();
            }
        }

        DataApi.DataListener onDataChangedListener=new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEventBuffer) {

                Log.d(TAG, "On Data Changed Count ------" + dataEventBuffer.getCount());
                for(DataEvent dataEvent:dataEventBuffer)
                {
                    Log.d(TAG, "On Data Changed Event Type ----" + dataEvent.getType());
                    if(dataEvent.getType()==DataEvent.TYPE_CHANGED) {
                        DataItem dataItem = dataEvent.getDataItem();
                        Log.d(TAG, "On Data Changed Data Event Details -----" + dataEvent.toString());
                        processItem(dataItem);
                    }
                }
                dataEventBuffer.release();
                invalidate();
            }
        };

        ResultCallback<DataItemBuffer> OnConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                Log.d(TAG, "On Connected Result Callback " + dataItems.getCount());
                for(DataItem item:dataItems)
                {
                    processItem(item);

                }
                dataItems.release();
                invalidate();
            }
        };
    }
}
