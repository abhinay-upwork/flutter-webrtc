package com.cloudwebrtc.webrtc.segmentation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cloudwebrtc.webrtc.video.LocalVideoTrack;

import org.webrtc.VideoFrame;
import org.webrtc.JavaI420Buffer;

import java.nio.ByteBuffer;

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
        // Clear cached frame when mode changes
        if (lastProcessedFrame != null) {
            lastProcessedFrame.release();
            lastProcessedFrame = null;
        }
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
    private static final long PROCESS_INTERVAL_MS = 100; // ~10 FPS processing (better performance)
    private int frameSkipCount = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private int consecutiveFailures = 0;
    private VideoFrame lastProcessedFrame = null; // Cache last processed frame to prevent flickering
    
    @Nullable
    private VideoFrame applyBackgroundBlurEfficient(@NonNull VideoFrame frame) {
        try {
            // Convert VideoFrame to Bitmap for MediaPipe processing
            VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
            Bitmap srcBitmap = convertI420ToBitmap(i420Buffer);
            
            if (srcBitmap == null) {
                return null;
            }
            
            int w = srcBitmap.getWidth();
            int h = srcBitmap.getHeight();
            
            // Performance optimization: downscale for processing if image is large
            Bitmap processingBitmap = srcBitmap;
            float scaleFactor = 1.0f;
            
            // Downscale large images to improve performance
            if (w * h > 300000) { // For images larger than ~550x550
                scaleFactor = (float) Math.sqrt(300000.0 / (w * h));
                int newW = (int) (w * scaleFactor);
                int newH = (int) (h * scaleFactor);
                processingBitmap = Bitmap.createScaledBitmap(srcBitmap, newW, newH, true);
            }
            
            // Get confidence mask as float array (much more efficient)
            float[] alpha = segmenter.runSegmentationForConfidenceMask(processingBitmap);
            
            if (alpha == null || alpha.length != processingBitmap.getWidth() * processingBitmap.getHeight()) {
                cleanupBitmap(srcBitmap);
                if (processingBitmap != srcBitmap) cleanupBitmap(processingBitmap);
                return null;
            }
            
            // Create blurred background with smaller radius for performance
            Bitmap blurred = processingBitmap.copy(Bitmap.Config.ARGB_8888, true);
            applyFastBlur(blurred, 6); // Further reduced radius for better performance
            
            // Scale processed results back to original size if needed
            if (scaleFactor < 1.0f) {
                // Scale blurred image back to original size
                Bitmap scaledBlurred = Bitmap.createScaledBitmap(blurred, w, h, true);
                cleanupBitmap(blurred);
                blurred = scaledBlurred;
                
                // Scale alpha mask back to original size
                float[] scaledAlpha = scaleAlphaMask(alpha, processingBitmap.getWidth(), processingBitmap.getHeight(), w, h);
                alpha = scaledAlpha;
            }
            
            // Composite: out = fg*mask + blurred*(1-mask) (simple per-pixel mix)
            int[] srcPx = new int[w * h];
            int[] blurPx = new int[w * h];
            srcBitmap.getPixels(srcPx, 0, w, 0, 0, w, h);
            blurred.getPixels(blurPx, 0, w, 0, 0, w, h);
            
            int[] outPx = new int[w * h];
            for (int i = 0; i < outPx.length; i++) {
                float a = alpha[i]; // person probability
                int s = srcPx[i];
                int b = blurPx[i];
                int sr = (s >> 16) & 0xFF, sg = (s >> 8) & 0xFF, sb = s & 0xFF;
                int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
                int r = (int) (sr * a + br * (1f - a));
                int g = (int) (sg * a + bg * (1f - a));
                int bl = (int) (sb * a + bb * (1f - a));
                outPx[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            
            // Convert directly to I420 and VideoFrame (skip intermediate bitmap)
            int chromaW = (w + 1) / 2;
            int chromaH = (h + 1) / 2;
            ByteBuffer y = ByteBuffer.allocateDirect(w * h);
            ByteBuffer u = ByteBuffer.allocateDirect(chromaW * chromaH);
            ByteBuffer v = ByteBuffer.allocateDirect(chromaW * chromaH);
            
            // Simple ARGB -> I420 conversion - Fill Y plane
            for (int j = 0; j < h; j++) {
                for (int i2 = 0; i2 < w; i2++) {
                    int c = outPx[j * w + i2];
                    int R = (c >> 16) & 0xFF, G = (c >> 8) & 0xFF, B = c & 0xFF;
                    int Y = (int)(0.257*R + 0.504*G + 0.098*B + 16);
                    y.put((byte) (Math.max(0, Math.min(255, Y))));
                }
            }
            
            // Subsampled U/V (4:2:0)
            for (int j = 0; j < h; j += 2) {
                for (int i2 = 0; i2 < w; i2 += 2) {
                    int idx = j * w + i2;
                    int c1 = outPx[idx];
                    int c2 = (i2 + 1 < w) ? outPx[idx + 1] : c1;
                    int c3 = (j + 1 < h) ? outPx[idx + w] : c1;
                    int c4 = (j + 1 < h && i2 + 1 < w) ? outPx[idx + w + 1] : c1;
                    int R = ((c1>>16)&0xFF)+((c2>>16)&0xFF)+((c3>>16)&0xFF)+((c4>>16)&0xFF);
                    int G = ((c1>>8)&0xFF)+((c2>>8)&0xFF)+((c3>>8)&0xFF)+((c4>>8)&0xFF);
                    int B = (c1&0xFF)+(c2&0xFF)+(c3&0xFF)+(c4&0xFF);
                    R >>= 2; G >>= 2; B >>= 2;
                    int Uv = (int)(-0.148*R - 0.291*G + 0.439*B + 128);
                    int Vv = (int)( 0.439*R - 0.368*G - 0.071*B + 128);
                    u.put((byte) (Math.max(0, Math.min(255, Uv))));
                    v.put((byte) (Math.max(0, Math.min(255, Vv))));
                }
            }
            
            y.rewind(); u.rewind(); v.rewind();
            
            VideoFrame.I420Buffer outBuf = JavaI420Buffer.wrap(
                w, h,
                y, w,
                u, chromaW,
                v, chromaW,
                () -> { /* NOP: GC frees buffers */ });
            
            VideoFrame outFrame = new VideoFrame(outBuf, frame.getRotation(), frame.getTimestampNs());
            
            // Clean up
            cleanupBitmap(srcBitmap);
            if (processingBitmap != srcBitmap) cleanupBitmap(processingBitmap);
            cleanupBitmap(blurred);
            
            return outFrame;
            
        } catch (Exception e) {
            return null;
        }
    }
    
    private float[] scaleAlphaMask(float[] originalMask, int originalW, int originalH, int targetW, int targetH) {
        if (originalW == targetW && originalH == targetH) {
            return originalMask;
        }
        
        float[] scaledMask = new float[targetW * targetH];
        float xScale = (float) originalW / targetW;
        float yScale = (float) originalH / targetH;
        
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                // Simple nearest neighbor sampling
                int srcX = Math.min((int) (x * xScale), originalW - 1);
                int srcY = Math.min((int) (y * yScale), originalH - 1);
                scaledMask[y * targetW + x] = originalMask[srcY * originalW + srcX];
            }
        }
        
        return scaledMask;
    }
    
    private void applyFastBlur(@NonNull Bitmap bitmap, int radius) {
        try {
            // Use optimized blur for better performance
            applyOptimizedBlur(bitmap, Math.min(radius, 12)); // Reduced max radius
        } catch (Exception e) {
            // Blur failed, bitmap remains unchanged
        }
    }
    
    private void applyOptimizedBlur(@NonNull Bitmap bitmap, int radius) {
        if (radius <= 1) return;
        
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        
        // Use smaller sampling for large images to improve performance
        int sampleRadius = radius;
        if (w * h > 200000) { // For images larger than ~450x450
            sampleRadius = Math.max(2, radius / 2);
        }
        
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        
        // Optimized horizontal blur with running sum
        blurHorizontal(pixels, w, h, sampleRadius);
        // Optimized vertical blur with running sum  
        blurVertical(pixels, w, h, sampleRadius);
        
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }
    
    private void blurHorizontal(int[] pixels, int w, int h, int radius) {
        for (int y = 0; y < h; y++) {
            int rowStart = y * w;
            
            // Running sum approach - much faster than nested loops
            int sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            
            // Initialize sum with first radius+1 pixels
            for (int x = 0; x <= Math.min(radius, w - 1); x++) {
                int pixel = pixels[rowStart + x];
                sumR += (pixel >> 16) & 0xFF;
                sumG += (pixel >> 8) & 0xFF;
                sumB += pixel & 0xFF;
                count++;
            }
            
            // Process each pixel using sliding window
            for (int x = 0; x < w; x++) {
                // Add new pixel to the right
                if (x + radius < w) {
                    int pixel = pixels[rowStart + x + radius];
                    sumR += (pixel >> 16) & 0xFF;
                    sumG += (pixel >> 8) & 0xFF;
                    sumB += pixel & 0xFF;
                    count++;
                }
                
                // Remove pixel from the left
                if (x - radius - 1 >= 0) {
                    int pixel = pixels[rowStart + x - radius - 1];
                    sumR -= (pixel >> 16) & 0xFF;
                    sumG -= (pixel >> 8) & 0xFF;
                    sumB -= pixel & 0xFF;
                    count--;
                }
                
                // Store averaged result
                pixels[rowStart + x] = 0xFF000000 | 
                    ((sumR / count) << 16) | 
                    ((sumG / count) << 8) | 
                    (sumB / count);
            }
        }
    }
    
    private void blurVertical(int[] pixels, int w, int h, int radius) {
        for (int x = 0; x < w; x++) {
            // Running sum approach for vertical pass
            int sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            
            // Initialize sum with first radius+1 pixels
            for (int y = 0; y <= Math.min(radius, h - 1); y++) {
                int pixel = pixels[y * w + x];
                sumR += (pixel >> 16) & 0xFF;
                sumG += (pixel >> 8) & 0xFF;
                sumB += pixel & 0xFF;
                count++;
            }
            
            // Process each pixel using sliding window
            for (int y = 0; y < h; y++) {
                // Add new pixel below
                if (y + radius < h) {
                    int pixel = pixels[(y + radius) * w + x];
                    sumR += (pixel >> 16) & 0xFF;
                    sumG += (pixel >> 8) & 0xFF;
                    sumB += pixel & 0xFF;
                    count++;
                }
                
                // Remove pixel above
                if (y - radius - 1 >= 0) {
                    int pixel = pixels[(y - radius - 1) * w + x];
                    sumR -= (pixel >> 16) & 0xFF;
                    sumG -= (pixel >> 8) & 0xFF;
                    sumB -= pixel & 0xFF;
                    count--;
                }
                
                // Store averaged result
                pixels[y * w + x] = 0xFF000000 | 
                    ((sumR / count) << 16) | 
                    ((sumG / count) << 8) | 
                    (sumB / count);
            }
        }
    }
    
    @Override
    public VideoFrame onFrame(VideoFrame frame) {
        if (currentMode == Mode.NONE) {
            return frame;
        }
        
        // Skip processing if too soon (performance optimization)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastProcessTime < PROCESS_INTERVAL_MS) {
            frameSkipCount++;
            // Return last processed frame if available to prevent flickering
            if (lastProcessedFrame != null) {
                return lastProcessedFrame;
            } else {
                return frame;
            }
        }
        
        // Skip processing if too many consecutive failures (system protection)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // Reset after some time to allow recovery
            if (currentTime - lastProcessTime > 2000) { // 2 seconds
                consecutiveFailures = 0;
            } else {
                return frame;
            }
        }
        
        try {
            lastProcessTime = currentTime;
            long startTime = System.nanoTime();
            
            VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
            Bitmap inputBitmap = convertI420ToBitmap(i420Buffer);
            
            if (inputBitmap == null) {
                consecutiveFailures++;
                return frame;
            }
            
            // Run segmentation to get mask
            Bitmap maskBitmap = segmenter.runSegmentation(inputBitmap);
            if (maskBitmap == null) {
                cleanupBitmap(inputBitmap);
                consecutiveFailures++;
                return frame;
            }
            
            long segmentationTime = System.nanoTime() - startTime;
            
            // Apply processing based on mode
            if (currentMode == Mode.BLUR) {
                // Use new efficient confidence mask approach for BLUR mode
                cleanupBitmap(inputBitmap);
                cleanupBitmap(maskBitmap);
                
                VideoFrame efficientResult = applyBackgroundBlurEfficient(frame);
                if (efficientResult != null) {
                    // Reset failure counter on success
                    consecutiveFailures = 0;
                    
                    // Cache the processed frame to prevent flickering
                    if (lastProcessedFrame != null) {
                        lastProcessedFrame.release();
                    }
                    efficientResult.retain();
                    lastProcessedFrame = efficientResult;
                    
                    return efficientResult;
                } else {
                    // Efficient method failed, increment failures
                    consecutiveFailures++;
                    return frame;
                }
            }
            
            // For other modes, use the traditional approach
            Bitmap processedBitmap;
            switch (currentMode) {
                case VIRTUAL_BACKGROUND:
                    processedBitmap = applyVirtualBackground(inputBitmap, maskBitmap);
                    break;
                default:
                    processedBitmap = inputBitmap;
                    break;
            }
            
            if (processedBitmap == null) {
                cleanupBitmap(inputBitmap);
                cleanupBitmap(maskBitmap);
                consecutiveFailures++;
                return frame;
            }
            
            // Convert processed bitmap back to VideoFrame
            if (processedBitmap != inputBitmap) {
                VideoFrame processedFrame = convertBitmapToVideoFrame(processedBitmap, frame.getTimestampNs(), frame.getRotation());
                
                if (processedFrame == null) {
                    cleanupBitmap(inputBitmap);
                    cleanupBitmap(maskBitmap);
                    cleanupBitmap(processedBitmap);
                    consecutiveFailures++;
                    return frame;
                }
                
                // Clean up bitmaps aggressively
                cleanupBitmap(inputBitmap);
                cleanupBitmap(maskBitmap);
                cleanupBitmap(processedBitmap);
                
                // Reset failure counter on success
                consecutiveFailures = 0;
                
                // Cache the processed frame to prevent flickering
                if (lastProcessedFrame != null) {
                    lastProcessedFrame.release();
                }
                if (processedFrame != null) {
                    processedFrame.retain();
                    lastProcessedFrame = processedFrame;
                }
                
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
            // Ignore bitmap recycling errors
        }
    }
    
    /**
     * Apply background blur effect using the segmentation mask.
     * Uses pixel-by-pixel blending for reliable results.
     */
    @NonNull
    private Bitmap applyBackgroundBlur(@NonNull Bitmap input, @NonNull Bitmap mask) {
        int width = input.getWidth();
        int height = input.getHeight();
        
        // Create blurred version of input
        Bitmap blurredBitmap = createBlurredBitmap(input);
        if (blurredBitmap == null) {
            return input;
        }
        
        // Create result bitmap
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        // Get pixel arrays for processing
        int[] inputPixels = new int[width * height];
        int[] blurredPixels = new int[width * height];
        int[] maskPixels = new int[width * height];
        
        input.getPixels(inputPixels, 0, width, 0, 0, width, height);
        blurredBitmap.getPixels(blurredPixels, 0, width, 0, 0, width, height);
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height);
        
        int[] resultPixels = new int[width * height];
        
        // Pixel-by-pixel blending - MediaPipe mask: high values = person, low values = background
        int personPixels = 0, backgroundPixels = 0;
        int minMask = 255, maxMask = 0;
        
        for (int i = 0; i < inputPixels.length; i++) {
            int maskValue = (maskPixels[i] >> 8) & 0xFF;
            minMask = Math.min(minMask, maskValue);
            maxMask = Math.max(maxMask, maskValue);
            
            float maskConfidence = maskValue / 255.0f;
            boolean isPerson = maskConfidence > 0.5f; // Simple threshold for now
            
            if (isPerson) {
                resultPixels[i] = inputPixels[i];
                personPixels++;
            } else {
                resultPixels[i] = blurredPixels[i];
                backgroundPixels++;
            }
        }
        
        // Set result pixels
        result.setPixels(resultPixels, 0, width, 0, 0, width, height);
        
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
     * Dispose and clean up resources.
     */
    public void dispose() {
        if (lastProcessedFrame != null) {
            lastProcessedFrame.release();
            lastProcessedFrame = null;
        }
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
    }
}
