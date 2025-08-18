package com.cloudwebrtc.webrtc.segmentation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.cloudwebrtc.webrtc.video.LocalVideoTrack;

import org.webrtc.VideoFrame;

/**
 * Segmentation processor that applies background blur or virtual backgrounds
 * using MediaPipe selfie segmentation.
 */
public class SegmentationProcessor implements LocalVideoTrack.ExternalVideoFrameProcessing {
    private static final String TAG = "SegmentationProcessor";
    
    public enum Mode {
        NONE,
        BLUR,
        VIRTUAL_BACKGROUND
    }
    
    private final Context context;
    private final MediaPipeSegmenter segmenter;
    private Mode currentMode = Mode.NONE;
    private Bitmap virtualBackgroundBitmap;
    private final Paint blurPaint;
    private final Paint compositePaint;
    
    public SegmentationProcessor(@NonNull Context context) {
        this.context = context;
        this.segmenter = new MediaPipeSegmenter(context);
        
        // Initialize paint objects for image processing
        blurPaint = new Paint();
        blurPaint.setAntiAlias(true);
        blurPaint.setFilterBitmap(true);
        
        compositePaint = new Paint();
        compositePaint.setAntiAlias(true);
        compositePaint.setFilterBitmap(true);
    }
    
    /**
     * Initialize the segmentation processor with a model file.
     * @param modelPath Path to the SelfieSegmenter model file
     * @return true if initialization successful
     */
    public boolean initialize(@NonNull String modelPath) {
        return segmenter.initialize(modelPath);
    }
    
    /**
     * Set the processing mode.
     * @param mode Processing mode (NONE, BLUR, VIRTUAL_BACKGROUND)
     */
    public void setMode(@NonNull Mode mode) {
        this.currentMode = mode;
        // Reset failure counter when mode changes
        consecutiveFailures = 0;
        Log.i(TAG, "Segmentation mode set to: " + mode);
    }
    
    /**
     * Get current processing mode.
     */
    @NonNull
    public Mode getMode() {
        return currentMode;
    }
    
    /**
     * Reset failure counter and re-enable processing.
     */
    public void resetFailures() {
        consecutiveFailures = 0;
        Log.i(TAG, "Segmentation failure counter reset");
    }
    
    /**
     * Set virtual background image from bitmap.
     * @param backgroundBitmap Virtual background bitmap
     */
    public void setVirtualBackground(@Nullable Bitmap backgroundBitmap) {
        this.virtualBackgroundBitmap = backgroundBitmap;
        if (backgroundBitmap != null) {
            this.currentMode = Mode.VIRTUAL_BACKGROUND;
        }
    }
    
    private long lastProcessTime = 0;
    private static final long PROCESS_INTERVAL_MS = 50; // ~20 FPS processing (balanced performance)
    private int frameSkipCount = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private int consecutiveFailures = 0;
    
    @Override
    public VideoFrame onFrame(VideoFrame frame) {
        if (currentMode == Mode.NONE) {
            return frame;
        }
        
        // Skip processing if too soon (performance optimization)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastProcessTime < PROCESS_INTERVAL_MS) {
            frameSkipCount++;
            return frame; // Return original frame without processing
        }
        
        // Skip processing if too many consecutive failures (system protection)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "Too many segmentation failures (" + consecutiveFailures + "), temporarily disabling processing");
            // Reset after some time to allow recovery
            if (currentTime - lastProcessTime > 2000) { // 2 seconds
                Log.i(TAG, "Resetting failure counter after timeout");
                consecutiveFailures = 0;
            } else {
                return frame;
            }
        }
        
        try {
            lastProcessTime = currentTime;
            long startTime = System.nanoTime();
            
            // Convert VideoFrame to Bitmap with memory check
            VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
            Bitmap inputBitmap = convertI420ToBitmap(i420Buffer);
            
            if (inputBitmap == null) {
                Log.w(TAG, "Failed to convert VideoFrame to Bitmap, returning original");
                consecutiveFailures++;
                return frame;
            }
            
            // Run segmentation to get mask with timeout protection
            Bitmap maskBitmap = segmenter.runSegmentation(inputBitmap);
            if (maskBitmap == null) {
                Log.w(TAG, "Segmentation failed (attempt " + (consecutiveFailures + 1) + "/" + MAX_CONSECUTIVE_FAILURES + "), returning original frame");
                cleanupBitmap(inputBitmap);
                consecutiveFailures++;
                return frame;
            }
            
            long segmentationTime = System.nanoTime() - startTime;
            
            // Apply processing based on mode
            Bitmap processedBitmap;
            switch (currentMode) {
                case BLUR:
                    processedBitmap = applyBackgroundBlur(inputBitmap, maskBitmap);
                    break;
                case VIRTUAL_BACKGROUND:
                    processedBitmap = applyVirtualBackground(inputBitmap, maskBitmap);
                    break;
                default:
                    processedBitmap = inputBitmap;
                    break;
            }
            
            if (processedBitmap == null) {
                Log.w(TAG, "Failed to process frame, returning original");
                cleanupBitmap(inputBitmap);
                cleanupBitmap(maskBitmap);
                consecutiveFailures++;
                return frame;
            }
            
            // Convert processed bitmap back to VideoFrame
            if (processedBitmap != inputBitmap) {
                // Always create frames with 0 rotation to prevent orientation issues
                VideoFrame processedFrame = convertBitmapToVideoFrame(processedBitmap, frame.getTimestampNs(), 0);
                
                // Performance logging (reduced frequency)
                long totalTime = System.nanoTime() - startTime;
                if (frameSkipCount > 100) { // Log every ~10 seconds now
                    Log.d(TAG, String.format("Processing stats: Segmentation: %.1fms, Total: %.1fms, Skipped: %d frames, Failures: %d", 
                            segmentationTime / 1_000_000.0, totalTime / 1_000_000.0, frameSkipCount, consecutiveFailures));
                    frameSkipCount = 0;
                }
                
                // Clean up bitmaps aggressively
                cleanupBitmap(inputBitmap);
                cleanupBitmap(maskBitmap);
                cleanupBitmap(processedBitmap);
                
                // Reset failure counter on success
                consecutiveFailures = 0;
                
                return processedFrame != null ? processedFrame : frame;
            }
            
            // Clean up bitmaps
            cleanupBitmap(inputBitmap);
            cleanupBitmap(maskBitmap);
            
            // Reset failure counter on success
            consecutiveFailures = 0;
            
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of memory during frame processing - forcing GC and skipping", oom);
            System.gc(); // Force garbage collection
            consecutiveFailures = MAX_CONSECUTIVE_FAILURES; // Disable processing temporarily
        } catch (Exception e) {
            Log.e(TAG, "Error processing video frame - returning original to prevent camera issues", e);
            consecutiveFailures++;
        }
        
        return frame;
    }
    
    /**
     * Safely clean up bitmap to prevent memory leaks.
     */
    private void cleanupBitmap(Bitmap bitmap) {
        try {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error recycling bitmap", e);
        }
    }
    
    /**
     * Apply background blur effect using the segmentation mask.
     */
    @NonNull
    private Bitmap applyBackgroundBlur(@NonNull Bitmap input, @NonNull Bitmap mask) {
        int width = input.getWidth();
        int height = input.getHeight();
        
        // Create blurred version of input
        Bitmap blurredBitmap = createBlurredBitmap(input);
        
        // Create result bitmap
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        
        // Draw blurred background
        canvas.drawBitmap(blurredBitmap, 0, 0, blurPaint);
        
        // Apply mask to blend original person over blurred background
        compositePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        canvas.drawBitmap(mask, 0, 0, compositePaint);
        
        compositePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
        canvas.drawBitmap(input, 0, 0, compositePaint);
        
        // Reset xfermode
        compositePaint.setXfermode(null);
        
        // Clean up
        if (!blurredBitmap.isRecycled()) blurredBitmap.recycle();
        
        return result;
    }
    
    /**
     * Apply virtual background effect using the segmentation mask.
     */
    @NonNull
    private Bitmap applyVirtualBackground(@NonNull Bitmap input, @NonNull Bitmap mask) {
        if (virtualBackgroundBitmap == null) {
            Log.w(TAG, "No virtual background set, applying blur instead");
            return applyBackgroundBlur(input, mask);
        }
        
        int width = input.getWidth();
        int height = input.getHeight();
        
        // Scale virtual background to match input size
        Bitmap scaledBackground = Bitmap.createScaledBitmap(virtualBackgroundBitmap, width, height, true);
        
        // Create result bitmap
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        
        // Draw virtual background
        canvas.drawBitmap(scaledBackground, 0, 0, blurPaint);
        
        // Apply mask to blend original person over virtual background
        compositePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        canvas.drawBitmap(mask, 0, 0, compositePaint);
        
        compositePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
        canvas.drawBitmap(input, 0, 0, compositePaint);
        
        // Reset xfermode
        compositePaint.setXfermode(null);
        
        // Clean up
        if (!scaledBackground.isRecycled()) scaledBackground.recycle();
        
        return result;
    }
    
    /**
     * Create a blurred version of the input bitmap.
     */
    @NonNull
    private Bitmap createBlurredBitmap(@NonNull Bitmap input) {
        // Use legacy blur method for compatibility across all Android versions
        return createBlurredBitmapLegacy(input);
    }
    
    // Note: Advanced blur using RenderEffect (API 31+) removed for compatibility
    // The legacy blur method provides good quality for all Android versions
    
    /**
     * Create blurred bitmap using legacy method (simple box blur).
     */
    @NonNull
    private Bitmap createBlurredBitmapLegacy(@NonNull Bitmap input) {
        // For older Android versions, apply a simple blur effect
        // This is a basic implementation - for production, consider using RenderScript or other blur libraries
        
        int width = input.getWidth();
        int height = input.getHeight();
        
        // Scale down for blur performance
        int blurWidth = width / 4;
        int blurHeight = height / 4;
        
        Bitmap smallBitmap = Bitmap.createScaledBitmap(input, blurWidth, blurHeight, false);
        Bitmap blurredSmall = applyBoxBlur(smallBitmap, 2);
        
        // Scale back up
        Bitmap result = Bitmap.createScaledBitmap(blurredSmall, width, height, true);
        
        // Clean up
        if (!smallBitmap.isRecycled()) smallBitmap.recycle();
        if (!blurredSmall.isRecycled()) blurredSmall.recycle();
        
        return result;
    }
    
    /**
     * Apply simple box blur to bitmap.
     */
    @NonNull
    private Bitmap applyBoxBlur(@NonNull Bitmap input, int radius) {
        int width = input.getWidth();
        int height = input.getHeight();
        
        int[] pixels = new int[width * height];
        input.getPixels(pixels, 0, width, 0, 0, width, height);
        
        // Apply horizontal blur
        int[] temp = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0, g = 0, b = 0, count = 0;
                
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < width) {
                        int pixel = pixels[y * width + nx];
                        r += (pixel >> 16) & 0xFF;
                        g += (pixel >> 8) & 0xFF;
                        b += pixel & 0xFF;
                        count++;
                    }
                }
                
                r /= count;
                g /= count;
                b /= count;
                
                temp[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        
        // Apply vertical blur
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int r = 0, g = 0, b = 0, count = 0;
                
                for (int dy = -radius; dy <= radius; dy++) {
                    int ny = y + dy;
                    if (ny >= 0 && ny < height) {
                        int pixel = temp[ny * width + x];
                        r += (pixel >> 16) & 0xFF;
                        g += (pixel >> 8) & 0xFF;
                        b += pixel & 0xFF;
                        count++;
                    }
                }
                
                r /= count;
                g /= count;
                b /= count;
                
                pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }
    
    /**
     * Convert I420Buffer to Bitmap.
     */
    @Nullable
    private Bitmap convertI420ToBitmap(@NonNull VideoFrame.I420Buffer i420Buffer) {
        return VideoFrameUtils.i420ToBitmap(i420Buffer);
    }
    
    /**
     * Convert Bitmap to VideoFrame.
     */
    @Nullable
    private VideoFrame convertBitmapToVideoFrame(@NonNull Bitmap bitmap, long timestampNs, int rotation) {
        return VideoFrameUtils.createVideoFrameFromBitmap(bitmap, timestampNs, rotation);
    }
    
    /**
     * Release resources and cleanup.
     */
    public void release() {
        segmenter.release();
        
        if (virtualBackgroundBitmap != null && !virtualBackgroundBitmap.isRecycled()) {
            virtualBackgroundBitmap.recycle();
            virtualBackgroundBitmap = null;
        }
        
        Log.i(TAG, "SegmentationProcessor released");
    }
}
