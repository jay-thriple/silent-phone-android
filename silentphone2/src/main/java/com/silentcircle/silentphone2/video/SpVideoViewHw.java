/*
Copyright (C) 2014-2017, Silent Circle, LLC.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Any redistribution, use, or modification is done solely for personal
      benefit and not for any commercial purpose or for monetary gain
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name Silent Circle nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL SILENT CIRCLE, LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.silentcircle.silentphone2.video;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import com.silentcircle.logs.Log;
import android.view.TextureView;

import com.silentcircle.silentphone2.util.ConfigurationUtilities;
import com.silentcircle.silentphone2.util.Utilities;


public class SpVideoViewHw extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = SpVideoViewHw.class.getSimpleName();

    private static GetVideoDataAndDraw videoDraw = new GetVideoDataAndDraw();

    private boolean mSurfaceOk;
    private int mSurfaceWidth, mSurfaceHeight;
    private int mPreviousWidth, mPreviousHeight;

    private Rect rectFS = new Rect(0, 0, 720, 480);

    private boolean firstPack = true;

    private static Paint paL = new Paint(Paint.ANTI_ALIAS_FLAG);

    // The framework usually calls the constructors
    @SuppressWarnings("unused")
    public SpVideoViewHw(Context context) {
        super(context);
        init();
    }

    @SuppressWarnings("unused")
    public SpVideoViewHw (Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressWarnings("unused")
    public SpVideoViewHw (Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        // Install a Callback so we get notified when the underlying surface is created and destroyed.
        setSurfaceTextureListener(this);
        paL.setTextSize(15);
        paL.setTextAlign(Paint.Align.LEFT);
        paL.setARGB(255, 200, 200, 200);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (ConfigurationUtilities.mTrace) Log.d(TAG, "onSurfaceTextureAvailable: width: " + width + ", height: " + height);
        mSurfaceOk = true;
        mSurfaceWidth = width;
        mSurfaceHeight = height;
    }

    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (ConfigurationUtilities.mTrace) Log.d(TAG, "Surface texture changed: width: " + width + ", height: " + height);
        mSurfaceOk = true;
        mSurfaceWidth = width;
        mSurfaceHeight = height;
    }

    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (ConfigurationUtilities.mTrace) Log.d(TAG, "onSurfaceTextureDestroyed");
        // setting mSurfaceOk to false stops further rendering calls. However, a rendering may
        // still be active (video handling runs in several threads). Thus wait until rendering
        // is done. Do this with a simple wait loop, using mutexes for every frame is an overkill.
        // Failing to wait for the last rendering to complete leads to crashes because this function
        // returns true and after this no rendering must access the buffers/canvas.
        mSurfaceOk = false;
        Utilities.Sleep(50);                // time to wait for ongoing rendering of last frame
        int waitCounter = 0;
        while (mRenderingActive && waitCounter < 3) {
            Log.w(TAG, "Long wait for rendering: " + waitCounter);
            Utilities.Sleep(50);
            waitCounter++;
        }
        return true;
    }

    public void clear() {
        videoDraw.clear();
        firstPack = true;
    }

    private int iPrevID;
    private boolean bCallIsActive = false;
    private boolean mRenderingActive;

    public void check(boolean bActive) {
        bCallIsActive = bActive;
        if (!bCallIsActive)
            return;

        if (!mSurfaceOk)
            return;

        if (!videoDraw.hasNewFrame())
            return;

        if (firstPack) {                        //clearing prev video img
            try {
                Canvas c = lockCanvas();
                if (c != null) {
                    mRenderingActive = true;
                    c.drawColor(Color.BLACK);
                    unlockCanvasAndPost(c);
                    mRenderingActive = false;
                }
            } catch (IllegalArgumentException ex) {
                Log.w(TAG, "Cannot clear video screen", ex);
            }
            iPrevID = GetVideoDataAndDraw.sxy[2];
            firstPack = false;
            return;
        }

        if (GetVideoDataAndDraw.sxy[2] != iPrevID) {
            videoDraw.fillNewBuf();
            iPrevID = GetVideoDataAndDraw.sxy[2];
            try {
                Canvas c = lockCanvas();
                if (c != null) {
                    mRenderingActive = true;
                    drawIt(c);
                    unlockCanvasAndPost(c);
                    mRenderingActive = false;
                }
            }
            catch (IllegalArgumentException ex) {
                Log.w(TAG, "Cannot update video", ex);
            }
        }
    }

    // The caller locks canvas, thus drawIt can draw onto the canvas
    private void drawIt(Canvas canvas) {

        if (!mSurfaceOk || mSurfaceHeight <= 0 || mSurfaceWidth <= 0 || videoDraw.mWidth <= 0 || videoDraw.mHeight <= 0)
            return;

        if (bCallIsActive) {
            // Currently not used, maybe late if we support landscape: boolean hor = mSurfaceWidth > mSurfaceHeight;

            if (GetVideoDataAndDraw.videoData != null) {
                // According to Android documentation we must draw every pixel on surface canvas.
                // Thus we fill it with background first, then draw the video rectangle inside the
                // letterbox
                canvas.drawColor(Color.BLACK);

                // Compute new letterbox if width or height of received video changed
                if (mPreviousWidth != videoDraw.mWidth || mPreviousHeight != videoDraw.mHeight) {
                    mPreviousWidth = videoDraw.mWidth;
                    mPreviousHeight = videoDraw.mHeight;

                    int ox, oy, wShow, hShow;

                    float fw = (float) mSurfaceWidth / (float) videoDraw.mWidth;
                    float fh = (float) mSurfaceHeight / (float) videoDraw.mHeight;

                    boolean landscape = mSurfaceWidth > mSurfaceHeight;

                    float multiplier = fw < fh ? fw : fh;

                    wShow = (int) ((float) videoDraw.mWidth * multiplier);
                    hShow = (int) ((float) videoDraw.mHeight * multiplier);

                    // Compute letter box offsets
                    if (!landscape) {
                        ox = (mSurfaceWidth - wShow) >> 1;
                        ox = (ox < 0) ? 0 : ox;
                    }
                    else
                        ox = 0;
                    if (landscape) {
                        oy = (mSurfaceHeight - hShow) >> 1;
                        oy = (oy < 0) ? 0 : oy;
                    }
                    else
                        oy = 0;
                    rectFS.set(ox, oy, ox + wShow, oy + hShow);
                }
                videoDraw.draw(canvas, rectFS.left, rectFS.top, rectFS, paL);         // slow?
            }
        }
    }
}
