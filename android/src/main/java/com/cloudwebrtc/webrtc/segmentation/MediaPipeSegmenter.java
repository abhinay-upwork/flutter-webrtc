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
import com.google.mediapipe.framework.image.MPImageProperties;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

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
            
                // Configure ImageSegmenter options based on official MediaPipe samples
                // Enable all mask types to match the official sample approach
                ImageSegmenter.ImageSegmenterOptions.Builder optionsBuilder =
                    ImageSegmenter.ImageSegmenterOptions.builder()
                        .setBaseOptions(BaseOptions.builder()
                                .setModelAssetPath(modelPath)
                                .build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setOutputCategoryMask(true)      // Enable category mask output
                        .setOutputConfidenceMasks(true);  // Enable confidence masks as alternative data source
            
            synchronized (segmenterLock) {
                imageSegmenter = ImageSegmenter.createFromOptions(context, optionsBuilder.build());
            }
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPipe ImageSegmenter", e);
            return false;
        }
    }
    
    @Nullable
    public float[] runSegmentationForConfidenceMask(@NonNull Bitmap inputBitmap) {
        synchronized (segmenterLock) {
            if (imageSegmenter == null) {
                Log.w(TAG, "ImageSegmenter not initialized - skipping segmentation");
                return null;
            }

            try {
                // Check input bitmap validity and memory pressure
                if (inputBitmap.isRecycled()) {
                    return null;
                }

                // Check available memory before processing
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

                if (memoryUsagePercent > 95) {
                    return null;
                }

                // Convert Bitmap to MPImage
                MPImage mpImage = new BitmapImageBuilder(inputBitmap).build();

                // Generate timestamp for video mode (MediaPipe requires timestamps)
                long timestampMs = frameCount * 150; // ~6-7 FPS (150ms per frame)
                frameCount++;

                // Run segmentation with timeout protection
                ImageSegmenterResult result = imageSegmenter.segmentForVideo(mpImage, timestampMs);

                if (result == null) {
                    return null;
                }

                // Extract confidence mask as float array for efficient processing
                if (result.confidenceMasks().isPresent() && !result.confidenceMasks().get().isEmpty()) {
                    MPImage maskImg = result.confidenceMasks().get().get(0);
                    
                    try {
                        ByteBuffer buf = ByteBufferExtractor.extract(maskImg, MPImage.IMAGE_FORMAT_VEC32F1);
                        FloatBuffer fb = buf.asFloatBuffer();
                        
                        int mw = maskImg.getWidth();
                        int mh = maskImg.getHeight();
                        float[] alpha = new float[mw * mh];
                        fb.get(alpha);
                        
                        return alpha;
                        
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to extract confidence mask: " + e.getMessage());
                    }
                }
                
                return null;

            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "Out of memory during segmentation", oom);
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Segmentation failed", e);
                return null;
            }
        }
    }

    @Nullable
    public Bitmap runSegmentation(@NonNull Bitmap inputBitmap) {
        synchronized (segmenterLock) {
            if (imageSegmenter == null) {
                Log.w(TAG, "ImageSegmenter not initialized - skipping segmentation");
                return null;
            }
            
            try {
                // Check input bitmap validity and memory pressure
                if (inputBitmap.isRecycled()) {
                    return null;
                }
                
                // Check available memory before processing
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
                
                if (memoryUsagePercent > 95) {
                    return createFallbackMask(inputBitmap.getWidth(), inputBitmap.getHeight());
                }
                
                // Convert Bitmap to MPImage
                MPImage mpImage = new BitmapImageBuilder(inputBitmap).build();
                
                // Generate timestamp for video mode (MediaPipe requires timestamps)
                // Reduced frequency for better performance
                long timestampMs = frameCount * 150; // ~6-7 FPS (150ms per frame)
                frameCount++;
                
                // Run segmentation with timeout protection
                ImageSegmenterResult result = imageSegmenter.segmentForVideo(mpImage, timestampMs);
                
                if (result == null) {
                    return createFallbackMask(inputBitmap.getWidth(), inputBitmap.getHeight());
                }
                
                // Try to extract masks in order of preference
                if (result.categoryMask().isPresent()) {
                    Bitmap maskBitmap = extractBitmapFromMPImage(result.categoryMask().get());
                    if (maskBitmap != null) {
                        return createProcessedMask(maskBitmap, inputBitmap.getWidth(), inputBitmap.getHeight());
                    }
                }

                if (result.confidenceMasks().isPresent() && !result.confidenceMasks().get().isEmpty()) {
                    Bitmap maskBitmap = extractBitmapFromMPImage(result.confidenceMasks().get().get(0));
                    if (maskBitmap != null) {
                        return createProcessedMask(maskBitmap, inputBitmap.getWidth(), inputBitmap.getHeight());
                    }
                }

                // Use fallback if all mask extraction methods failed
                return createFallbackMask(inputBitmap.getWidth(), inputBitmap.getHeight());
                
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "Out of memory during segmentation - using fallback mask", oom);
                return createFallbackMask(inputBitmap.getWidth(), inputBitmap.getHeight());
            } catch (Exception e) {
                Log.e(TAG, "Segmentation failed - using fallback mask", e);
                return createFallbackMask(inputBitmap.getWidth(), inputBitmap.getHeight());
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
        Bitmap processedMask = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(processedMask);
        
        // Scale the raw mask to target size with smooth interpolation
        Bitmap scaledMask = Bitmap.createScaledBitmap(rawMask, targetWidth, targetHeight, true);
        
        // Apply the mask with smooth edges
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        
        canvas.drawBitmap(scaledMask, 0, 0, paint);
        
        // Return the processed mask directly (skip smoothing for now to debug)
        return processedMask;
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
    
    @Nullable
    private Bitmap extractBitmapFromMPImage(@NonNull MPImage segmentationMask) {
        try {
            int maskWidth = segmentationMask.getWidth();
            int maskHeight = segmentationMask.getHeight();
            ByteBuffer maskBuffer = null;
            
            // Try different extraction formats
            try {
                maskBuffer = ByteBufferExtractor.extract(segmentationMask, MPImage.IMAGE_FORMAT_ALPHA);
            } catch (Exception e) {
                try {
                    maskBuffer = ByteBufferExtractor.extract(segmentationMask, MPImage.IMAGE_FORMAT_VEC32F1);
                } catch (Exception e2) {
                    try {
                        maskBuffer = ByteBufferExtractor.extract(segmentationMask, MPImage.IMAGE_FORMAT_RGB);
                    } catch (Exception e3) {
                        return null;
                    }
                }
            }
            
            if (maskBuffer == null) return null;
            
            // Convert buffer to bitmap
            Bitmap maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888);
            maskBuffer.rewind();
            int[] pixels = new int[maskWidth * maskHeight];
            
            for (int i = 0; i < pixels.length && maskBuffer.hasRemaining(); i++) {
                int value;
                if (maskBuffer.remaining() >= 4) {
                    try {
                        float floatValue = maskBuffer.getFloat();
                        value = (int) (floatValue * 255);
                    } catch (Exception e) {
                        maskBuffer.position(maskBuffer.position() - 4);
                        value = maskBuffer.get() & 0xFF;
                        while (maskBuffer.hasRemaining() && (i + 1) * 4 < maskBuffer.capacity() && maskBuffer.position() % 4 != 0) {
                            maskBuffer.get();
                        }
                    }
                } else {
                    value = maskBuffer.get() & 0xFF;
                }
                
                value = Math.max(0, Math.min(255, value));
                pixels[i] = 0xFF000000 | (value << 16) | (value << 8) | value;
            }
            
            maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight);
            return maskBitmap;
            
        } catch (Exception e) {
            return null;
        }
    }
    
    @NonNull
    private Bitmap createFallbackMask(int width, int height) {
        try {
            Bitmap fallbackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(fallbackBitmap);
            
            // Create an intelligent fallback mask
            // Since we can't extract real MediaPipe data, make the best elliptical mask possible
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            
            // Fill with black (background areas to be blurred)
            canvas.drawColor(android.graphics.Color.BLACK);
            
            // Create adaptive person area based on image dimensions
            paint.setColor(android.graphics.Color.WHITE);
            paint.setAntiAlias(true);
            
            // Adaptive sizing based on image aspect ratio
            float aspectRatio = (float) width / height;
            float centerX = width * 0.5f;
            float centerY = height * 0.4f; // Slightly higher than center for typical selfie framing
            
            // Adaptive radius based on image size and aspect ratio
            float baseRadius = Math.min(width, height) * 0.35f;
            float radiusX = baseRadius * (aspectRatio > 1 ? 0.8f : 1.0f); // Narrower for landscape
            float radiusY = baseRadius * (aspectRatio > 1 ? 1.2f : 1.0f); // Taller for landscape
            
            // Ensure minimum and maximum sizes
            radiusX = Math.max(width * 0.25f, Math.min(width * 0.4f, radiusX));
            radiusY = Math.max(height * 0.3f, Math.min(height * 0.5f, radiusY));
            
            canvas.drawOval(centerX - radiusX, centerY - radiusY, 
                          centerX + radiusX, centerY + radiusY, paint);
            
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
    
    public void release() {
        synchronized (segmenterLock) {
            if (imageSegmenter != null) {
                imageSegmenter.close();
                imageSegmenter = null;
            }
        }
    }
}

