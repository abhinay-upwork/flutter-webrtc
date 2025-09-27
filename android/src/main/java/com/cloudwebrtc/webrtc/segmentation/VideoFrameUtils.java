package com.cloudwebrtc.webrtc.segmentation;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.VideoFrame;
import org.webrtc.YuvHelper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Utility class for converting between VideoFrame and Bitmap objects.
 * Handles YUV to RGB conversion and vice versa for segmentation processing.
 */
public class VideoFrameUtils {
    private static final String TAG = "VideoFrameUtils";
    
    /**
     * Convert VideoFrame.I420Buffer to Bitmap.
     * @param i420Buffer The I420 buffer from VideoFrame
     * @return Bitmap representation of the frame, or null if conversion fails
     */
    @Nullable
    public static Bitmap i420ToBitmap(@NonNull VideoFrame.I420Buffer i420Buffer) {
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            
            // Get Y, U, V planes
            ByteBuffer yBuffer = i420Buffer.getDataY();
            ByteBuffer uBuffer = i420Buffer.getDataU();
            ByteBuffer vBuffer = i420Buffer.getDataV();
            
            int yStride = i420Buffer.getStrideY();
            int uStride = i420Buffer.getStrideU();
            int vStride = i420Buffer.getStrideV();
            
            // Create byte arrays for Y, U, V data
            byte[] yBytes = new byte[yBuffer.remaining()];
            byte[] uBytes = new byte[uBuffer.remaining()];
            byte[] vBytes = new byte[vBuffer.remaining()];
            
            yBuffer.get(yBytes);
            uBuffer.get(uBytes);
            vBuffer.get(vBytes);
            
            // Convert YUV420 to RGB
            int[] rgbArray = new int[width * height];
            convertYuv420ToRgb(yBytes, uBytes, vBytes, width, height, yStride, uStride, vStride, rgbArray);
            
            // Create bitmap from RGB array
            return Bitmap.createBitmap(rgbArray, width, height, Bitmap.Config.ARGB_8888);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert I420Buffer to Bitmap", e);
            return null;
        }
    }
    
    /**
     * Convert Bitmap to VideoFrame.I420Buffer.
     * @param bitmap Input bitmap
     * @return I420Buffer representation, or null if conversion fails
     */
    @Nullable
    public static VideoFrame.I420Buffer bitmapToI420(@NonNull Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            // Get RGB pixel data
            int[] rgbArray = new int[width * height];
            bitmap.getPixels(rgbArray, 0, width, 0, 0, width, height);
            
            // Convert RGB to YUV420
            ByteBuffer yBuffer = ByteBuffer.allocateDirect(width * height);
            ByteBuffer uBuffer = ByteBuffer.allocateDirect(width * height / 4);
            ByteBuffer vBuffer = ByteBuffer.allocateDirect(width * height / 4);
            
            convertRgbToYuv420(rgbArray, width, height, yBuffer, uBuffer, vBuffer);
            
            // Create I420Buffer
            VideoFrame.I420Buffer i420Buffer = new VideoFrame.I420Buffer() {
                @Override
                public int getWidth() {
                    return width;
                }
                
                @Override
                public int getHeight() {
                    return height;
                }
                
                @Override
                public ByteBuffer getDataY() {
                    return yBuffer.asReadOnlyBuffer();
                }
                
                @Override
                public ByteBuffer getDataU() {
                    return uBuffer.asReadOnlyBuffer();
                }
                
                @Override
                public ByteBuffer getDataV() {
                    return vBuffer.asReadOnlyBuffer();
                }
                
                @Override
                public int getStrideY() {
                    return width;
                }
                
                @Override
                public int getStrideU() {
                    return width / 2;
                }
                
                @Override
                public int getStrideV() {
                    return width / 2;
                }
                
                @Override
                public VideoFrame.I420Buffer toI420() {
                    return this;
                }
                
                @Override
                public void retain() {
                    // No-op for this implementation
                }
                
                @Override
                public void release() {
                    // No-op for this implementation
                }
                
                @Override
                public VideoFrame.Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight, int scaleWidth, int scaleHeight) {
                    // Basic implementation - can be enhanced
                    return this;
                }
            };
            
            return i420Buffer;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert Bitmap to I420Buffer", e);
            return null;
        }
    }
    
    /**
     * Create VideoFrame from Bitmap with original frame rotation preserved.
     * @param bitmap Input bitmap
     * @param timestampNs Timestamp in nanoseconds
     * @param originalRotation Original frame rotation to preserve
     * @return VideoFrame or null if conversion fails
     */
    @Nullable
    public static VideoFrame createVideoFrameFromBitmap(@NonNull Bitmap bitmap, long timestampNs, int originalRotation) {
        // Convert bitmap to VideoFrame with rotation
        
        VideoFrame.I420Buffer i420Buffer = bitmapToI420(bitmap);
        if (i420Buffer == null) {
            Log.e(TAG, "Failed to convert Bitmap to I420Buffer");
            return null;
        }
        
        VideoFrame videoFrame = new VideoFrame(i420Buffer, originalRotation, timestampNs);
        return videoFrame;
    }
    
    /**
     * Create VideoFrame from Bitmap with no rotation.
     * @param bitmap Input bitmap
     * @param timestampNs Timestamp in nanoseconds
     * @return VideoFrame or null if conversion fails
     */
    @Nullable
    public static VideoFrame createVideoFrameFromBitmap(@NonNull Bitmap bitmap, long timestampNs) {
        return createVideoFrameFromBitmap(bitmap, timestampNs, 0);
    }
    
    /**
     * Convert YUV420 to RGB array.
     * Uses standard YUV to RGB conversion formulas.
     */
    private static void convertYuv420ToRgb(@NonNull byte[] yData, @NonNull byte[] uData, @NonNull byte[] vData,
                                          int width, int height, int yStride, int uStride, int vStride,
                                          @NonNull int[] rgbArray) {
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int yIndex = y * yStride + x;
                int uvIndex = (y / 2) * uStride + (x / 2);
                
                if (yIndex >= yData.length || uvIndex >= uData.length || uvIndex >= vData.length) {
                    continue;
                }
                
                int Y = (yData[yIndex] & 0xFF) - 16;
                int U = (uData[uvIndex] & 0xFF) - 128;
                int V = (vData[uvIndex] & 0xFF) - 128;
                
                // YUV to RGB conversion
                int R = (int) (1.164f * Y + 1.596f * V);
                int G = (int) (1.164f * Y - 0.392f * U - 0.813f * V);
                int B = (int) (1.164f * Y + 2.017f * U);
                
                // Clamp values to 0-255 range
                R = Math.max(0, Math.min(255, R));
                G = Math.max(0, Math.min(255, G));
                B = Math.max(0, Math.min(255, B));
                
                // Pack RGB into int
                rgbArray[y * width + x] = 0xFF000000 | (R << 16) | (G << 8) | B;
            }
        }
    }
    
    /**
     * Convert RGB array to YUV420 buffers.
     * Uses standard RGB to YUV conversion formulas.
     */
    private static void convertRgbToYuv420(@NonNull int[] rgbArray, int width, int height,
                                          @NonNull ByteBuffer yBuffer, @NonNull ByteBuffer uBuffer, @NonNull ByteBuffer vBuffer) {
        
        yBuffer.clear();
        uBuffer.clear();
        vBuffer.clear();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = rgbArray[y * width + x];
                
                int R = (rgb >> 16) & 0xFF;
                int G = (rgb >> 8) & 0xFF;
                int B = rgb & 0xFF;
                
                // RGB to YUV conversion
                int Y = (int) (0.257f * R + 0.504f * G + 0.098f * B + 16);
                int U = (int) (-0.148f * R - 0.291f * G + 0.439f * B + 128);
                int V = (int) (0.439f * R - 0.368f * G - 0.071f * B + 128);
                
                // Clamp values
                Y = Math.max(0, Math.min(255, Y));
                U = Math.max(0, Math.min(255, U));
                V = Math.max(0, Math.min(255, V));
                
                // Store Y value
                yBuffer.put((byte) Y);
                
                // Store U and V values (subsampled)
                if (x % 2 == 0 && y % 2 == 0) {
                    uBuffer.put((byte) U);
                    vBuffer.put((byte) V);
                }
            }
        }
        
        yBuffer.flip();
        uBuffer.flip();
        vBuffer.flip();
    }
    
    /**
     * Alternative method using YuvImage for YUV to RGB conversion.
     * May be more efficient on some devices.
     */
    @Nullable
    public static Bitmap i420ToBitmapUsingYuvImage(@NonNull VideoFrame.I420Buffer i420Buffer) {
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            
            // Convert I420 to NV21 format for YuvImage
            byte[] nv21 = i420ToNv21(i420Buffer);
            if (nv21 == null) {
                return null;
            }
            
            // Create YuvImage and convert to bitmap
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 80, stream);
            
            byte[] jpegArray = stream.toByteArray();
            return android.graphics.BitmapFactory.decodeByteArray(jpegArray, 0, jpegArray.length);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert I420Buffer to Bitmap using YuvImage", e);
            return null;
        }
    }
    
    /**
     * Convert I420 to NV21 format.
     */
    @Nullable
    private static byte[] i420ToNv21(@NonNull VideoFrame.I420Buffer i420Buffer) {
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            
            ByteBuffer yBuffer = i420Buffer.getDataY();
            ByteBuffer uBuffer = i420Buffer.getDataU();
            ByteBuffer vBuffer = i420Buffer.getDataV();
            
            int ySize = width * height;
            int uvSize = width * height / 4;
            
            byte[] nv21 = new byte[ySize + uvSize * 2];
            
            // Copy Y data
            yBuffer.get(nv21, 0, ySize);
            
            // Interleave U and V data for NV21 format
            byte[] uBytes = new byte[uvSize];
            byte[] vBytes = new byte[uvSize];
            uBuffer.get(uBytes);
            vBuffer.get(vBytes);
            
            for (int i = 0; i < uvSize; i++) {
                nv21[ySize + i * 2] = vBytes[i];     // V first in NV21
                nv21[ySize + i * 2 + 1] = uBytes[i]; // U second in NV21
            }
            
            return nv21;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert I420 to NV21", e);
            return null;
        }
    }
}
