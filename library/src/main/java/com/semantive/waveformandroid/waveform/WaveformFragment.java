package com.semantive.waveformandroid.waveform;

import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.semantive.waveformandroid.R;
import com.semantive.waveformandroid.waveform.soundfile.CheapSoundFile;
import com.semantive.waveformandroid.waveform.view.WaveformView;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

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

/**
 * Keeps track of the waveform display, current horizontal offset, marker handles,
 * start / end text boxes, and handles all of the buttons and controls
 * <p>
 * Modified by Anna Stępień <anna.stepien@semantive.com>
 * Modified by Dmitry Redkovolosov <dmitry.redkovolosov@dsr-company.com>
 */
public abstract class WaveformFragment extends Fragment implements WaveformView.WaveformListener {

    public static final String TAG = "WaveformFragment";

    protected long mLoadingLastUpdateTime;
    protected boolean mLoadingKeepGoing;
    protected ProgressDialog mProgressDialog;
    protected CheapSoundFile mSoundFile;
    protected File mFile;
    protected String mFilename;
    protected WaveformView mWaveformView;
    protected ImageButton mPlayButton;
    protected String mCaption = "";
    protected int mWidth;
    protected int mMaxPos;
    protected int mStartPos;
    protected int mEndPos;
    protected int mLastDisplayedStartPos;
    protected int mLastDisplayedEndPos;
    protected int mOffset;
    protected int mOffsetGoal;
    protected int mPlayStartMsec;
    protected int mPlayStartOffset;
    protected int mPlayEndMsec;
    protected Handler mHandler;
    protected boolean mIsPlaying;
    protected MediaPlayer mPlayer;
    protected float mDensity;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.fragment_waveform, container, false);
        loadGui(view);
        return view;
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setRetainInstance(true);

        mPlayer = null;
        mIsPlaying = false;

        mFilename = null;
        mSoundFile = null;

        mHandler = new Handler();
    }

    @Override
    public void onDestroy() {
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }

        mSoundFile = null;
        mWaveformView = null;
        super.onDestroy();
    }

    //
    // WaveformListener
    //

    /**
     * Every time we get a message that our waveform drew, see if we need to
     * animate and trigger another redraw.
     */
    public void waveformDraw() {
        mWidth = mWaveformView.getMeasuredWidth();
        if (mOffsetGoal != mOffset)
            updateDisplay();
        else if (mIsPlaying) {
            updateDisplay();
        }
    }

    public void waveformClick() {
        if (mIsPlaying) {
            handlePause();
        }
    }

    //
    // Internal methods
    //

    protected void loadGui(View view) {
        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        mPlayButton = (ImageButton) view.findViewById(R.id.play);
        mPlayButton.setOnClickListener(mPlayListener);

        enableDisableButtons();

        mWaveformView = (WaveformView) view.findViewById(R.id.waveform);
        mWaveformView.setListener(this);
        mWaveformView.setSegments(getSegments());
        mWaveformView.setPlaybackColor(getPlaybackColor());
        mWaveformView.setSoundWaveColor(getSoundWaveColor());

        mMaxPos = 0;
        mLastDisplayedStartPos = -1;
        mLastDisplayedEndPos = -1;

        if (mSoundFile != null && !mWaveformView.hasSoundFile()) {
            mWaveformView.setSoundFile(mSoundFile);
            mWaveformView.recomputeHeights(mDensity);
            mMaxPos = mWaveformView.maxPos();
        }

        updateDisplay();
    }

    protected void loadFromFile() {
        mFile = new File(mFilename);
        mLoadingLastUpdateTime = System.currentTimeMillis();
        mLoadingKeepGoing = true;
        mProgressDialog = new ProgressDialog(getActivity());
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgressDialog.setTitle(R.string.progress_dialog_loading);
        mProgressDialog.setCancelable(true);
        mProgressDialog.setOnCancelListener((DialogInterface dialog) -> mLoadingKeepGoing = false);
        mProgressDialog.show();

        final CheapSoundFile.ProgressListener listener = (double fractionComplete) -> {
            long now = System.currentTimeMillis();
            if (now - mLoadingLastUpdateTime > 100) {
                mProgressDialog.setProgress(
                        (int) (mProgressDialog.getMax() * fractionComplete));
                mLoadingLastUpdateTime = now;
            }
            return mLoadingKeepGoing;
        };

        // Create the MediaPlayer in a background thread
        new Thread() {
            public void run() {
                try {
                    MediaPlayer player = new MediaPlayer();
                    player.setDataSource(mFile.getAbsolutePath());
                    player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    player.prepare();
                    mPlayer = player;
                } catch (final java.io.IOException e) {
                    Log.e(TAG, "Error while creating media player", e);
                }
            }
        }.start();

        // Load the sound file in a background thread
        new Thread() {
            public void run() {
                try {
                    mSoundFile = CheapSoundFile.create(mFile.getAbsolutePath(), listener);
                } catch (final Exception e) {
                    Log.e(TAG, "Error while loading sound file", e);
                    mProgressDialog.dismiss();
                    return;
                }
                if (mLoadingKeepGoing) {
                    mHandler.post(() -> finishOpeningSoundFile());
                }
            }
        }.start();
    }

    protected void finishOpeningSoundFile() {
        mWaveformView.setSoundFile(mSoundFile);
        mWaveformView.recomputeHeights(mDensity);

        mMaxPos = mWaveformView.maxPos();
        mLastDisplayedStartPos = -1;
        mLastDisplayedEndPos = -1;

        mOffset = 0;
        mOffsetGoal = 0;
        resetPositions();

        mCaption = mSoundFile.getFiletype() + ", " +
                mSoundFile.getSampleRate() + " Hz, " +
                mSoundFile.getAvgBitrateKbps() + " kbps, " +
                formatTime(mMaxPos) + " " + getResources().getString(R.string.time_seconds);
        mProgressDialog.dismiss();
        updateDisplay();
        onPlay(mStartPos);
    }

    protected synchronized void updateDisplay() {
        if (mIsPlaying) {
            int now = mPlayer.getCurrentPosition() + mPlayStartOffset;
            int frames = mWaveformView.millisecsToPixels(now);
            mWaveformView.setPlayback(frames);
            setOffsetGoalNoUpdate(frames - mWidth / 2);
            if (now >= mPlayEndMsec) {
                handlePause();
            }
        }

        int offsetDelta = mOffsetGoal - mOffset;

        if (offsetDelta > 10)
            offsetDelta = offsetDelta / 10;
        else if (offsetDelta > 0)
            offsetDelta = 1;
        else if (offsetDelta < -10)
            offsetDelta = offsetDelta / 10;
        else if (offsetDelta < 0)
            offsetDelta = -1;
        else
            offsetDelta = 0;

        mOffset += offsetDelta;

        mWaveformView.invalidate();
    }

    protected void enableDisableButtons() {
        if (mIsPlaying) {
            mPlayButton.setVisibility(View.GONE);
        } else {
            mPlayButton.setVisibility(View.VISIBLE);
            mPlayButton.setImageResource(getPlayButtonImageResource());
        }
    }

    protected void resetPositions() {
        mStartPos = 0;
        mEndPos = mMaxPos;
    }

    protected void setOffsetGoalNoUpdate(int offset) {
        mOffsetGoal = offset;
        if (mOffsetGoal + mWidth / 2 > mMaxPos)
            mOffsetGoal = mMaxPos - mWidth / 2;
        if (mOffsetGoal < 0)
            mOffsetGoal = 0;
    }

    protected String formatTime(int pixels) {
        if (mWaveformView != null && mWaveformView.isInitialized()) {
            return formatDecimal(mWaveformView.pixelsToSeconds(pixels));
        } else {
            return "";
        }
    }

    protected String formatDecimal(double x) {
        int xWhole = (int) x;
        int xFrac = (int) (100 * (x - xWhole) + 0.5);

        if (xFrac >= 100) {
            xWhole++; //Round up
            xFrac -= 100; //Now we need the remainder after the round up
            if (xFrac < 10) {
                xFrac *= 10; //we need a fraction that is 2 digits long
            }
        }

        if (xFrac < 10)
            return xWhole + ".0" + xFrac;
        else
            return xWhole + "." + xFrac;
    }

    protected synchronized void handlePause() {
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.pause();
        }
        mIsPlaying = false;
        enableDisableButtons();
    }

    protected synchronized void onPlay(int startPosition) {
        if (mIsPlaying) {
            handlePause();
            return;
        }

        //sound file is not loaded yet
        if (mSoundFile == null) {
            mFilename = getFileName();
            loadFromFile();
        }

        try {
            mPlayStartMsec = mWaveformView.pixelsToMillisecs(startPosition);
            if (startPosition < mStartPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mStartPos);
            } else if (startPosition > mEndPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mMaxPos);
            } else {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mEndPos);
            }

            mPlayStartOffset = 0;

            int startFrame = mWaveformView.secondsToFrames(mPlayStartMsec * 0.001);
            int endFrame = mWaveformView.secondsToFrames(mPlayEndMsec * 0.001);
            int startByte = mSoundFile.getSeekableFrameOffset(startFrame);
            int endByte = mSoundFile.getSeekableFrameOffset(endFrame);
            if (startByte >= 0 && endByte >= 0) {
                try {
                    mPlayer.reset();
                    mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    FileInputStream subsetInputStream = new FileInputStream(mFile.getAbsolutePath());
                    mPlayer.setDataSource(subsetInputStream.getFD(), startByte, endByte - startByte);
                    mPlayer.prepare();
                    mPlayStartOffset = mPlayStartMsec;
                } catch (Exception e) {
                    Log.e(TAG, "Exception trying to play file subset", e);
                    mPlayer.reset();
                    mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    mPlayer.setDataSource(mFile.getAbsolutePath());
                    mPlayer.prepare();
                    mPlayStartOffset = 0;
                }
            }

            mPlayer.setOnCompletionListener((MediaPlayer mediaPlayer) -> handlePause());
            mIsPlaying = true;

            mPlayer.start();
            updateDisplay();
            enableDisableButtons();
        } catch (Exception e) {
            Log.e(TAG, "Exception while playing file", e);
        }
    }

    protected OnClickListener mPlayListener = new OnClickListener() {
        public void onClick(View sender) {
            onPlay(mStartPos);
        }
    };

    protected abstract String getFileName();

    protected List<Segment> getSegments() {
        return null;
    }

    /**
     * Get color of playback line. Override it for customizing
     */
    protected int getPlaybackColor() {
        return getResources().getColor(R.color.playback_indicator);
    }

    /**
     * Get color of sound wave. Override it for customizing
     */
    protected int getSoundWaveColor() {
        return getResources().getColor(R.color.waveform_selected);
    }

    /**
     * Get resource for play button. Override it for customizing
     */
    protected int getPlayButtonImageResource() {
        return android.R.drawable.ic_media_play;
    }
}