# iOS Background Blur Implementation Guide

This guide explains how to use the background blur functionality on iOS, providing high-quality real-time background segmentation and blur effects.

## Prerequisites

1. **MediaPipe Model**: Download the SelfieSegmenter model file (`selfie_segmenter.tflite`) and place it in your app's temporary directory at runtime.

2. **iOS Version**: Minimum iOS 13.0 for MediaPipe Tasks Vision support.

3. **Camera Permissions**: Ensure your app has camera permissions in `Info.plist`:
```xml
<key>NSCameraUsageDescription</key>
<string>This app needs camera access for video calls with background blur</string>
```

4. **Dependencies**: The iOS implementation uses MediaPipe Tasks Vision framework, which is automatically included in the podspec.

## Usage

### 1. Enable Background Blur

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

### 4. Clean Stream Cleanup

```dart
void closeStream() async {
  // This properly stops the camera and cleans up segmentation processor
  _stream.getTracks().forEach((track) async => await track.stop());
}
```

## Model Setup

Before using background blur, you need to copy the MediaPipe model to the device:

```dart
// Example: Copy model from assets to temporary directory
Future<void> setupSegmentationModel() async {
  final ByteData data = await rootBundle.load('assets/models/selfie_segmenter.tflite');
  final Directory tempDir = await getTemporaryDirectory();
  final File modelFile = File('${tempDir.path}/selfie_segmenter.tflite');
  
  await modelFile.writeAsBytes(data.buffer.asUint8List());
  print('Model copied to: ${modelFile.path}');
}
```

## Implementation Details

### Architecture

The iOS implementation consists of several key components:

1. **MediaPipeSegmenter**: Core segmentation using MediaPipe Tasks Vision
2. **SegmentationProcessor**: Camera capture and video processing pipeline
3. **FlutterRTCMediaStream**: Integration with WebRTC getUserMedia flow

### Key Components

#### **MediaPipeSegmenter.m**
- **Model Loading**: Loads SelfieSegmenter model using MediaPipe iOS SDK
- **Video Processing**: Handles frame-by-frame segmentation with timestamps
- **Mask Generation**: Creates high-quality masks with smooth edges
- **Resolution Scaling**: Scales masks from model resolution to video resolution

#### **SegmentationProcessor.m**
- **Camera Capture**: Independent AVCaptureSession for segmentation processing
- **Background Effects**: Applies blur or virtual background using Core Image
- **Video Mirroring**: Disabled for natural video orientation
- **Resource Management**: Proper cleanup of camera sessions

#### **Core Image Integration**
- **High-Quality Blur**: Uses `CIGaussianBlur` with configurable sigma
- **Mask Blending**: `CIBlendWithMask` for seamless foreground/background compositing
- **Edge Smoothing**: Gaussian blur on mask edges for anti-aliasing
- **Color Space**: Optimized color space handling for better performance

### Performance Optimizations

1. **Cached CIContext**: Reuses Core Image context across frames
2. **Optimized Scaling**: High-quality Lanczos scaling for mask upsampling
3. **Memory Management**: Proper CVPixelBuffer lifecycle management
4. **Thread Optimization**: MediaPipe processing on dedicated thread
5. **Soft Thresholding**: Smooth sigmoid curves instead of hard binary masks

### Background Blur Quality Features

1. **Resolution Independence**: Mask generated at model resolution, scaled to video resolution
2. **Edge Smoothing**: Gaussian blur (σ=0.8) applied to mask edges
3. **Soft Transitions**: Sigmoid-based soft thresholding for natural boundaries
4. **High-Quality Blur**: Configurable Gaussian blur (σ=12) on background
5. **Anti-Aliasing**: Smooth mask edges prevent pixelation

## Supported iOS Versions

- **Minimum**: iOS 13.0 - MediaPipe Tasks Vision support
- **Optimal**: iOS 14.0+ - Enhanced Core Image performance
- **Latest**: iOS 17.0+ - Best performance and latest MediaPipe features

## Error Handling

The implementation includes comprehensive error handling:

### Model and Initialization
```objective-c
// Model validation
if (![[NSFileManager defaultManager] fileExistsAtPath:modelPath]) {
    NSLog(@"Segmentation model not found at: %@", modelPath);
    return;
}

// MediaPipe initialization
if (error) {
    NSLog(@"Failed to create MediaPipe ImageSegmenter: %@", error.localizedDescription);
    return nil;
}
```

### Runtime Error Recovery
- Segmentation failures fall back to original video
- Camera session errors are logged and recovered
- Memory allocation failures are handled gracefully
- Resource cleanup on all failure paths

## Video Quality Settings

### Optimal Settings for Background Blur
```dart
final constraints = {
  'video': {
    'width': 1280,      // Good balance of quality and performance
    'height': 720,      // HD resolution
    'frameRate': 30,    // Smooth video
    'optional': [
      {'source': 'blur'}
    ]
  }
};
```

### Performance vs Quality Trade-offs
- **High Quality**: 1920x1080 @ 30fps (requires powerful devices)
- **Balanced**: 1280x720 @ 30fps (recommended)
- **Performance**: 640x480 @ 30fps (older devices)

## Troubleshooting

### Common Issues

#### 1. **Model Not Found**
```
Error: "Segmentation model not found at: /path/to/model"
```
**Solution**: Ensure `selfie_segmenter.tflite` is copied to the temporary directory before calling getUserMedia.

#### 2. **Camera Remains Active After Stream Close**
```
Symptom: Camera indicator remains on after ending call
```
**Solution**: Implemented automatic cleanup in `dealloc` and `cleanup` methods.

#### 3. **Poor Segmentation Quality**
```
Symptom: Pixelated or harsh edges around person
```
**Solution**: Using optimized mask processing with soft thresholding and edge smoothing.

#### 4. **Performance Issues**
```
Symptom: Low frame rate or choppy video
```
**Solution**: Reduce video resolution or disable segmentation on older devices.

### Debug Logs

Enable logging to troubleshoot issues:
```objective-c
// Check console for these logs:
// [WebRTC] MediaPipe ImageSegmenter initialized successfully
// [WebRTC] Applying blur / virtual background
// [WebRTC] Segmentation processor stopped and released
```

### Memory Management

The implementation automatically handles:
- CVPixelBuffer reference counting
- MediaPipe model lifecycle
- CIContext caching and cleanup
- AVCaptureSession resource management

## Advanced Configuration

### Custom Blur Intensity
```objective-c
// In SegmentationProcessor.m, modify blur sigma
CIImage *blurred = [input imageByApplyingGaussianBlurWithSigma:15.0]; // Stronger blur
```

### Custom Soft Thresholding
```objective-c
// In MediaPipeSegmenter.m, adjust threshold parameters
float threshold = 0.5f;
float softness = 0.1f;  // Sharper edges
float softness = 0.3f;  // Softer edges
```

### Virtual Background from URL
```objective-c
// Set virtual background from network image
- (void)setVirtualBackgroundFromURL:(NSString *)imageURL {
    NSURL *url = [NSURL URLWithString:imageURL];
    CIImage *img = [CIImage imageWithContentsOfURL:url];
    if (img) {
        self.virtualBackground = img;
        self.mode = @"image";
    }
}
```

## Comparison with Android

The iOS implementation provides feature parity with Android:

| Feature | iOS | Android | Notes |
|---------|-----|---------|-------|
| Background Blur | ✅ | ✅ | Core Image vs RenderEffect |
| Virtual Background | ✅ | ✅ | CIImage vs Bitmap |
| Edge Smoothing | ✅ | ✅ | Gaussian blur on both |
| Resource Cleanup | ✅ | ✅ | Automatic lifecycle management |
| Video Mirroring Control | ✅ | ✅ | Natural orientation |
| Same Constraint API | ✅ | ✅ | Unified interface |

### iOS-Specific Advantages
- **Core Image Pipeline**: Hardware-accelerated image processing
- **Better Memory Management**: Automatic reference counting
- **MediaPipe Integration**: Native iOS SDK with optimizations
- **Camera Control**: Fine-grained AVCaptureSession control

## Performance Benchmarks

### iPhone Performance (Approximate)
- **iPhone 12 Pro+**: 1080p @ 30fps with blur
- **iPhone 11 Pro**: 720p @ 30fps with blur  
- **iPhone XS**: 720p @ 25fps with blur
- **iPhone X**: 640p @ 30fps with blur

### Optimization Tips
1. **Model Caching**: Keep model loaded between sessions
2. **Resolution Scaling**: Start with lower resolution for older devices
3. **Background Scheduling**: Process on background queue when possible
4. **Memory Monitoring**: Watch for memory warnings and adjust accordingly

## Next Steps

1. Add the SelfieSegmenter model to your app bundle or download it at runtime
2. Implement model setup logic in your Flutter app
3. Use the constraint API as documented above
4. Test background blur on target devices
5. Monitor performance and adjust video settings as needed

The iOS implementation is production-ready and provides professional-quality background effects for video calls and recording applications!

## Additional Resources

- [MediaPipe Tasks Vision Guide](https://developers.google.com/mediapipe/solutions/vision/image_segmenter/ios)
- [Core Image Programming Guide](https://developer.apple.com/library/archive/documentation/GraphicsImaging/Conceptual/CoreImaging/ci_intro/ci_intro.html)
- [AVFoundation Camera Capture](https://developer.apple.com/documentation/avfoundation/cameras_and_media_capture)
- [WebRTC iOS Documentation](https://webrtc.googlesource.com/src/+/main/docs/native-code/ios/)
