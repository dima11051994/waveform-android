package com.semantive.waveformandroid.waveform.view;

/*
 * Copyright (C) 2008 Google Inc.
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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.semantive.waveformandroid.R;
import com.semantive.waveformandroid.waveform.Segment;
import com.semantive.waveformandroid.waveform.soundfile.CheapSoundFile;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * WaveformView is an Android view that displays a visual representation
 * of an audio waveform.  It retrieves the frame gains from a CheapSoundFile
 * object and recomputes the shape contour at several zoom levels.
 * <p/>
 * This class doesn't handle selection or any of the touch interactions
 * directly, so it exposes a listener interface.  The class that embeds
 * this view should add itself as a listener and make the view scroll
 * and respond to other events appropriately.
 * <p/>
 * WaveformView doesn't actually handle selection, but it will just display
 * the selected part of the waveform in a different color.
 * <p>
 * Modified by Anna Stępień <anna.stepien@semantive.com>
 */
public class WaveformView extends View {

    public static final String TAG = "WaveformView";

    public interface WaveformListener {
        public void waveformClick();

        public void waveformDraw();
    }

    // Colors
    protected Paint mGridPaint;
    protected Paint mSoundWavePaint;
    protected Paint mBorderLinePaint;
    protected Paint mPlaybackLinePaint;

    protected CheapSoundFile mSoundFile;
    protected int mLength;
    protected float mZoomFactor;
    protected int mSampleRate;
    protected int mSamplesPerFrame;
    protected int mPlaybackPos;
    protected float mDensity;
    protected WaveformListener mListener;
    protected boolean mInitialized;

    protected float range;
    protected float scaleFactor;
    protected float minGain;

    protected NavigableMap<Double, Segment> segmentsMap;
    protected Segment nextSegment;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // We don't want keys, the markers get these
        setFocusable(false);

        mGridPaint = new Paint();
        mGridPaint.setAntiAlias(false);
        mGridPaint.setColor(getResources().getColor(R.color.grid_line));
        mSoundWavePaint = new Paint();
        mSoundWavePaint.setAntiAlias(false);
        mSoundWavePaint.setColor(getResources().getColor(R.color.waveform_selected));
        mBorderLinePaint = new Paint();
        mBorderLinePaint.setAntiAlias(true);
        mBorderLinePaint.setStrokeWidth(1.5f);
        mBorderLinePaint.setPathEffect(new DashPathEffect(new float[]{3.0f, 2.0f}, 0.0f));
        mBorderLinePaint.setColor(getResources().getColor(R.color.selection_border));
        mPlaybackLinePaint = new Paint();
        mPlaybackLinePaint.setAntiAlias(false);
        mPlaybackLinePaint.setColor(getResources().getColor(R.color.playback_indicator));

        mSoundFile = null;
        mPlaybackPos = -1;
        mDensity = 1.0f;
        mInitialized = false;
        segmentsMap = new TreeMap<>();
        nextSegment = null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
                mListener.waveformClick();
                break;
        }
        return true;
    }

    public boolean hasSoundFile() {
        return mSoundFile != null;
    }

    public void setSoundFile(CheapSoundFile soundFile) {
        mSoundFile = soundFile;
        mSampleRate = mSoundFile.getSampleRate();
        mSamplesPerFrame = mSoundFile.getSamplesPerFrame();
        computeDoublesForAllZoomLevels();
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public int maxPos() {
        return mLength;
    }

    public int secondsToFrames(double seconds) {
        return (int) (1.0 * seconds * mSampleRate / mSamplesPerFrame + 0.5);
    }

    public int secondsToPixels(double seconds) {
        double z = mZoomFactor;
        return (int) (z * seconds * mSampleRate / mSamplesPerFrame + 0.5);
    }

    public double pixelsToSeconds(int pixels) {
        double z = mZoomFactor;
        return (pixels * (double) mSamplesPerFrame / (mSampleRate * z));
    }

    public int millisecsToPixels(int msecs) {
        double z = mZoomFactor;
        return (int) ((msecs * 1.0 * mSampleRate * z) / (1000.0 * mSamplesPerFrame) + 0.5);
    }

    public int pixelsToMillisecs(int pixels) {
        double z = mZoomFactor;
        return (int) (pixels * (1000.0 * mSamplesPerFrame) / (mSampleRate * z) + 0.5);
    }

    public void setPlayback(int pos) {
        mPlaybackPos = pos;
    }

    public void setListener(WaveformListener listener) {
        mListener = listener;
    }

    public void setSegments(final List<Segment> segments) {
        if (segments != null) {
            for (Segment segment : segments) {
                segmentsMap.put(segment.getStop(), segment);
            }
        }
    }

    /**
     * Set color of playback line
     *
     * @param color color from resources
     */
    public void setPlaybackColor(int color) {
        mPlaybackLinePaint.setColor(color);
    }

    /**
     * Set color of sound wave
     *
     * @param color color from resources
     */
    public void setSoundWaveColor(int color) {
        mSoundWavePaint.setColor(color);
    }

    public void recomputeHeights(float density) {
        mDensity = density;
        invalidate();
    }

    protected void drawWaveformLine(Canvas canvas, int x, int y0, int y1, Paint paint) {
        canvas.drawLine(x, y0, x, y1, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mSoundFile == null)
            return;

        int measuredWidth = getMeasuredWidth();
        int measuredHeight = getMeasuredHeight();
        int start = 0;
        int width = mLength - start;
        int ctr = measuredHeight / 2;

        if (width > measuredWidth)
            width = measuredWidth;

        double onePixelInSecs = pixelsToSeconds(1);

        double timecodeIntervalSecs = 1.0;

        int factor = 1;
        while (timecodeIntervalSecs / onePixelInSecs < 50) {
            timecodeIntervalSecs = 5.0 * factor;
            factor++;
        }
        int i = 0;
        while (i < width) {

            // Draw waveform
            drawWaveform(canvas, i, start, measuredHeight, ctr, selectWaveformPaint(0));

            i++;
        }

        // Draw borders
        canvas.drawLine(
                0.5f, 30,
                0.5f, measuredHeight,
                mBorderLinePaint);
        canvas.drawLine(
                0.5f, 0,
                0.5f, measuredHeight - 30,
                mBorderLinePaint);

        if (mListener != null) {
            mListener.waveformDraw();
        }
    }

    protected void drawWaveform(final Canvas canvas, final int i, final int start, final int measuredHeight, final int ctr, final Paint paint) {
        int h = (int) (getScaledHeight(mZoomFactor, start + i) * getMeasuredHeight() / 2);
        drawWaveformLine(
                canvas, i,
                ctr - h,
                ctr + 1 + h,
                paint);

        if (i + start == mPlaybackPos) {
            canvas.drawLine(i, 0, i, measuredHeight, mPlaybackLinePaint);
        }
    }

    protected Paint selectWaveformPaint(final double fractionalSecs) {
        Paint paint = mSoundWavePaint;

        if (segmentsMap != null && !segmentsMap.isEmpty()) {
            if (nextSegment == null) {
                Double key = segmentsMap.ceilingKey(fractionalSecs);
                if (key != null) {
                    nextSegment = segmentsMap.get(segmentsMap.ceilingKey(fractionalSecs));
                }
            }

            if (nextSegment != null) {
                if (nextSegment.getStart().compareTo(fractionalSecs) <= 0 && nextSegment.getStop().compareTo(fractionalSecs) >= 0) {
                    paint = new Paint();
                    paint.setAntiAlias(false);
                    paint.setColor(nextSegment.getColor());
                    return paint;
                } else {
                    Double key = segmentsMap.ceilingKey(fractionalSecs);
                    if (key != null) {
                        nextSegment = segmentsMap.get(segmentsMap.ceilingKey(fractionalSecs));
                    }
                }
            }
        }

        return paint;
    }

    /**
     * Return gain by given index
     * @param i index of gain in array, may be non-integer number
     * @param numFrames number of frames(frameGains length)
     * @param frameGains array of gains
     * @return gain
     */
    protected float getGain(float i, int numFrames, int[] frameGains) {
        //if i is integer number, then return value from array
        if (i % 1 == 0) {
            //if i is greater then frames count, get last frame value
            int x = Math.min(Math.round(i), numFrames - 1);
            return frameGains[x];
        }
        //if i < 0 then get value of first frame with coefficient |i|
        if (i < 0) {
            return frameGains[0] * Math.abs(i);
        }
        //if i is greater then frames count, get last frame value
        if (i > numFrames - 1) {
            return frameGains[numFrames - 1];
        }
        //get interpolated value between two frames
        int x = Math.round(i);
        return frameGains[x] + (frameGains[x + 1] - frameGains[x]) * (i - x);
    }

    protected float getHeight(float i, int numFrames, int[] frameGains, float scaleFactor, float minGain, float range) {
        float value = (getGain(i, numFrames, frameGains) * scaleFactor - minGain) / range;
        if (value < 0.0)
            value = 0.0f;
        if (value > 1.0)
            value = 1.0f;
        return value;
    }

    /**
     * Called once when a new sound file is added
     */
    protected void computeDoublesForAllZoomLevels() {
        int numFrames = mSoundFile.getNumFrames();

        // Make sure the range is no more than 0 - 255
        float maxGain = 1.0f;
        for (int i = 0; i < numFrames; i++) {
            float gain = getGain(i, numFrames, mSoundFile.getFrameGains());
            if (gain > maxGain) {
                maxGain = gain;
            }
        }
        scaleFactor = 1.0f;
        if (maxGain > 255.0) {
            scaleFactor = 255 / maxGain;
        }

        // Build histogram of 256 bins and figure out the new scaled max
        maxGain = 0;
        int gainHist[] = new int[256];
        for (int i = 0; i < numFrames; i++) {
            int smoothedGain = (int) (getGain(i, numFrames, mSoundFile.getFrameGains()) * scaleFactor);
            if (smoothedGain < 0)
                smoothedGain = 0;
            if (smoothedGain > 255)
                smoothedGain = 255;

            if (smoothedGain > maxGain)
                maxGain = smoothedGain;

            gainHist[smoothedGain]++;
        }

        // Re-calibrate the min to be 5%
        minGain = 0;
        int sum = 0;
        while (minGain < 255 && sum < numFrames * 1.0f / 20) {
            sum += gainHist[(int) minGain];
            minGain++;
        }

        // Re-calibrate the max to be 99%
        sum = 0;
        while (maxGain > 2 && sum < numFrames * 1.0f / 100) {
            sum += gainHist[(int) maxGain];
            maxGain--;
        }

        range = maxGain - minGain;

        float ratio = getMeasuredWidth() / (float) numFrames;
        mLength = Math.round(numFrames * ratio);
        mZoomFactor = ratio;
        mInitialized = true;
    }

    protected float getScaledHeight(float zoomLevel, int i) {
        return getHeight((i + 1) / zoomLevel - 1, mSoundFile.getNumFrames(),
                mSoundFile.getFrameGains(), scaleFactor, minGain, range);
    }
}

