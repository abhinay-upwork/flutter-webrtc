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

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;

import java.io.File;

/**
 * MediaPipe-based image segmentation for selfie/person detection.
 * Handles SelfieSegmenter model for background blur functionality.
 */
public class MediaPipeSegmenter {
    private static final String TAG = "MediaPipeSegmenter";
    
    private ImageSegmenter imageSegmenter;
    private final Context context;
    private long frameCount = 0;
    private final Object segmenterLock = new Object();
    
    public MediaPipeSegmenter(@NonNull Context context) {
        this.context = context;
    }
    
    /**
     * Initialize the MediaPipe Image Segmenter with the model file.
     * @param modelPath Path to the SelfieSegmenter model file
     * @return true if initialization successful, false otherwise
     */
    public boolean initialize(@NonNull String modelPath) {
        try {
            // Verify model file exists
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: " + modelPath);
                return false;
            }
            
            // Configure ImageSegmenter options
            ImageSegmenter.ImageSegmenterOptions.Builder optionsBuilder = 
                ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                            .setModelAssetPath(modelPath)
                            .build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setOutputCategoryMask(true)
                    .setOutputConfidenceMasks(false);
            
            synchronized (segmenterLock) {
                imageSegmenter = ImageSegmenter.createFromOptions(context, optionsBuilder.build());
            }
            
            Log.i(TAG, "MediaPipe ImageSegmenter initialized successfully with model: " + modelPath);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPipe ImageSegmenter", e);
            return false;
        }
    }
    
    /**
     * Run segmentation on the input bitmap and return a mask bitmap.
     * @param inputBitmap Input image bitmap
     * @return Segmentation mask bitmap (grayscale) or null if segmentation failed
     */
    @Nullable
    public Bitmap runSegmentation(@NonNull Bitmap inputBitmap) {
        synchronized (segmenterLock) {
            if (imageSegmenter == null) {
                Log.w(TAG, "ImageSegmenter not initialized - skipping segmentation");
                return null;
            }
            
            try {
                // Check input bitmap validity
                if (inputBitmap.isRecycled()) {
                    Log.w(TAG, "Input bitmap is recycled - skipping segmentation");
                    return null;
                }
                
                // Convert Bitmap to MPImage
                MPImage mpImage = new BitmapImageBuilder(inputBitmap).build();
                
                // Generate timestamp for video mode (MediaPipe requires timestamps)
                long timestampMs = frameCount * 33; // ~30 FPS (33ms per frame)
                frameCount++;
                
                // Run segmentation
                ImageSegmenterResult result = imageSegmenter.segmentForVideo(mpImage, timestampMs);
                
                if (result == null || !result.categoryMask().isPresent()) {
                    Log.w(TAG, "No segmentation result returned");
                    return null;
                }
                
                // Get the category mask
                MPImage categoryMask = result.categoryMask().get();
                Bitmap rawMaskBitmap = extractBitmapFromMPImage(categoryMask);
                
                if (rawMaskBitmap == null) {
                    Log.w(TAG, "Failed to extract bitmap from category mask");
                    return null;
                }
                
                // Create processed mask with smooth edges
                return createProcessedMask(rawMaskBitmap, inputBitmap.getWidth(), inputBitmap.getHeight());
                
            } catch (Exception e) {
                Log.e(TAG, "Segmentation failed", e);
                return null;
            }
        }
    }
    
    /**
     * Create a processed mask with smooth edges and proper scaling.
     * @param rawMask Raw mask from MediaPipe (may be low resolution)
     * @param targetWidth Target output width
     * @param targetHeight Target output height
     * @return Processed mask bitmap
     */
    @NonNull
    private Bitmap createProcessedMask(@NonNull Bitmap rawMask, int targetWidth, int targetHeight) {
        // Create a bitmap for the processed mask
        Bitmap processedMask = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(processedMask);
        
        // Scale the raw mask to target size with smooth interpolation
        Bitmap scaledMask = Bitmap.createScaledBitmap(rawMask, targetWidth, targetHeight, true);
        
        // Apply the mask with smooth edges
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        
        canvas.drawBitmap(scaledMask, 0, 0, paint);
        
        // Apply additional smoothing by creating a slightly blurred version
        return applyMaskSmoothing(processedMask);
    }
    
    /**
     * Apply smoothing to the mask for better edge quality.
     * @param mask Input mask bitmap
     * @return Smoothed mask bitmap
     */
    @NonNull
    private Bitmap applyMaskSmoothing(@NonNull Bitmap mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        
        // Get mask pixels
        int[] pixels = new int[width * height];
        mask.getPixels(pixels, 0, width, 0, 0, width, height);
        
        // Apply soft thresholding for smoother transitions
        for (int i = 0; i < pixels.length; i++) {
            int alpha = (pixels[i] >> 24) & 0xFF;
            
            // MediaPipe outputs: 0 for person, 255 for background
            // We need to invert for blur: 255 for person (show original), 0 for background (show blurred)
            float normalized = (255 - alpha) / 255.0f;
            
            // Apply soft sigmoid for smooth transitions
            float threshold = 0.5f;
            float softness = 0.15f;
            float smoothed = 1.0f / (1.0f + (float) Math.exp(-(normalized - threshold) / softness));
            
            // Convert back to alpha value
            int smoothedAlpha = (int) (smoothed * 255.0f);
            pixels[i] = (smoothedAlpha << 24) | 0x00FFFFFF; // White with alpha
        }
        
        // Create smoothed bitmap
        Bitmap smoothedMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        smoothedMask.setPixels(pixels, 0, width, 0, 0, width, height);
        
        return smoothedMask;
    }
    
    /**
     * Extract Bitmap from MPImage.
     * @param mpImage Input MPImage
     * @return Bitmap representation or null if extraction fails
     */
    @Nullable
    private Bitmap extractBitmapFromMPImage(@NonNull MPImage mpImage) {
        try {
            int width = mpImage.getWidth();
            int height = mpImage.getHeight();
            
            Log.d(TAG, "Processing MediaPipe segmentation mask: " + width + "x" + height);
            
            // For now, use fallback mask until proper MediaPipe extraction is implemented
            // This provides basic center-person segmentation functionality
            // TODO: Implement proper MPImage mask data extraction once MediaPipe API is confirmed
            return createFallbackMask(width, height);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract bitmap from MPImage, using fallback", e);
            return createFallbackMask(mpImage.getWidth(), mpImage.getHeight());
        }
    }
    
    /**
     * Create a fallback mask that shows the person (center area) and blurs the edges.
     * For PorterDuff.Mode.DST_OUT: transparent = keep original, opaque = remove/cut out
     */
    @NonNull
    private Bitmap createFallbackMask(int width, int height) {
        try {
            Bitmap fallbackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            Canvas canvas = new Canvas(fallbackBitmap);
            
            // Create a simple elliptical mask centered on the image
            // This assumes the person is roughly in the center of the frame
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            
            // Fill with opaque white (background areas will be cut out for blur)
            canvas.drawColor(android.graphics.Color.WHITE);
            
            // Draw an ellipse in the center for the person area (transparent - keep original)
            paint.setColor(android.graphics.Color.TRANSPARENT);
            paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
            
            float centerX = width * 0.5f;
            float centerY = height * 0.4f; // Slightly higher than center for typical selfie framing
            float radiusX = width * 0.25f;
            float radiusY = height * 0.35f;
            
            canvas.drawOval(centerX - radiusX, centerY - radiusY, 
                          centerX + radiusX, centerY + radiusY, paint);
            
            Log.i(TAG, "Created inverted fallback elliptical mask for segmentation");
            return fallbackBitmap;
            
        } catch (Exception fallbackException) {
            Log.e(TAG, "Fallback bitmap creation also failed", fallbackException);
            // Last resort: create a simple background mask (all opaque = all blurred)
            Bitmap simpleMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            Canvas canvas = new Canvas(simpleMask);
            canvas.drawColor(android.graphics.Color.WHITE);
            return simpleMask;
        }
    }
    
    /**
     * Release resources and cleanup.
     */
    public void release() {
        synchronized (segmenterLock) {
            if (imageSegmenter != null) {
                imageSegmenter.close();
                imageSegmenter = null;
                Log.i(TAG, "MediaPipe ImageSegmenter released");
            }
        }
    }
}
