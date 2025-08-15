# Android Background Blur Implementation Guide

This guide explains how to use the background blur functionality on Android, which mirrors the iOS implementation.

## Prerequisites

1. **MediaPipe Model**: Download the SelfieSegmenter model file (`selfie_segmenter.tflite`) and place it in your app's cache directory at runtime.

2. **Permissions**: Ensure your app has camera permissions in `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.CAMERA" />
```

## Usage

### 1. Enable Background Blur

Use the same constraint format as iOS:

```dart
final Map<String, dynamic> constraints = {
  'audio': true,
  'video': {
    'optional': [
      {'source': 'blur'}  // Enable background blur
    ]
  }
};

final MediaStream stream = await navigator.mediaDevices.getUserMedia(constraints);
```

### 2. Enable Virtual Background

```dart
final Map<String, dynamic> constraints = {
  'audio': true,
  'video': {
    'optional': [
      {'source': 'image'}  // Enable virtual background
    ]
  }
};

final MediaStream stream = await navigator.mediaDevices.getUserMedia(constraints);
```

### 3. Disable Background Processing

```dart
final Map<String, dynamic> constraints = {
  'audio': true,
  'video': true  // Regular video without processing
};

final MediaStream stream = await navigator.mediaDevices.getUserMedia(constraints);
```

## Model Setup

Before using background blur, you need to copy the MediaPipe model to the device:

```dart
// Example: Copy model from assets to cache directory
Future<void> setupSegmentationModel() async {
  final ByteData data = await rootBundle.load('assets/models/selfie_segmenter.tflite');
  final Directory cacheDir = await getTemporaryDirectory();
  final File modelFile = File('${cacheDir.path}/selfie_segmenter.tflite');
  
  await modelFile.writeAsBytes(data.buffer.asUint8List());
  print('Model copied to: ${modelFile.path}');
}
```

## Implementation Details

### Architecture

The Android implementation follows the same pattern as iOS:

1. **MediaPipeSegmenter**: Handles MediaPipe model loading and segmentation
2. **SegmentationProcessor**: Implements video frame processing with blur/virtual background
3. **VideoFrameUtils**: Utilities for converting between VideoFrame and Bitmap formats
4. **GetUserMediaImpl**: Integration with WebRTC getUserMedia flow

### Key Components

- **Background Blur**: Uses Android's native blur APIs (RenderEffect for API 31+, fallback box blur for older versions)
- **Virtual Background**: Supports custom background images
- **Video Processing**: Implements `ExternalVideoFrameProcessing` interface for frame-by-frame processing
- **Memory Management**: Proper bitmap recycling and resource cleanup

### Performance Optimizations

1. **YUV to RGB Conversion**: Efficient conversion between WebRTC VideoFrame and Android Bitmap
2. **Blur Implementation**: Optimized blur using native Android APIs when available
3. **Memory Management**: Automatic cleanup of bitmaps and resources
4. **Thread Safety**: Synchronized access to MediaPipe segmenter

## Error Handling

The implementation includes comprehensive error handling:

- Model file existence checks
- MediaPipe initialization validation
- Frame conversion error handling
- Resource cleanup on failures

## Supported Android Versions

- **Minimum**: API 21 (Android 5.0) - Basic blur support
- **Optimal**: API 31+ (Android 12) - Hardware-accelerated blur with RenderEffect

## Troubleshooting

### Common Issues

1. **Model Not Found**: Ensure `selfie_segmenter.tflite` is in the app's cache directory
2. **Poor Performance**: Consider reducing video resolution for better performance
3. **Memory Issues**: Ensure proper cleanup by stopping video tracks when done

### Debug Logs

Enable logging to troubleshoot issues:
```java
// Check logcat for these tags:
// - "MediaPipeSegmenter"
// - "SegmentationProcessor" 
// - "VideoFrameUtils"
// - "GetUserMediaImpl"
```

## Comparison with iOS

The Android implementation provides feature parity with iOS:

- ✅ Background blur with smooth edges
- ✅ Virtual background support
- ✅ Proper resource cleanup
- ✅ High-quality video processing
- ✅ Same constraint API

## Next Steps

1. Copy the MediaPipe model file to your app's assets
2. Set up model copying logic in your Flutter app
3. Use the same constraint format as documented above
4. Test background blur functionality

The implementation is now ready for production use with the same API as the iOS version!
