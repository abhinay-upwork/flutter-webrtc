#import "MediaPipeSegmenter.h"
#import <MediaPipeTasksVision/MediaPipeTasksVision.h>
#import <CoreImage/CoreImage.h>

@interface MediaPipeSegmenter ()
@property(nonatomic, strong) MPPImageSegmenter *segmenter;
@property(nonatomic, strong) CIContext *ciContext;
@property(nonatomic) NSInteger frameCount;
@end

@implementation MediaPipeSegmenter

- (instancetype)initWithModelPath:(NSString *)modelPath {
  self = [super init];
  if (self) {
    // Initialize MediaPipe ImageSegmenter with the SelfieSegmenter model
    MPPImageSegmenterOptions *options = [[MPPImageSegmenterOptions alloc] init];
    options.baseOptions.modelAssetPath = modelPath;
    options.shouldOutputCategoryMask = true;
    options.shouldOutputConfidenceMasks = false;
    options.runningMode = MPPRunningModeVideo;
    
    NSError *error = nil;
    _segmenter = [[MPPImageSegmenter alloc] initWithOptions:options error:&error];
    
    if (error) {
      NSLog(@"Failed to create MediaPipe ImageSegmenter: %@", error.localizedDescription);
      return nil;
    }
    
    if (!_segmenter) {
      NSLog(@"Failed to initialize MediaPipe ImageSegmenter");
      return nil;
    }
    
    // Initialize cached CIContext for better performance
    _ciContext = [CIContext contextWithOptions:@{
      kCIContextWorkingColorSpace: [NSNull null],
      kCIContextOutputColorSpace: [NSNull null]
    }];
    
    _frameCount = 0;
    
    NSLog(@"MediaPipe ImageSegmenter initialized successfully with model: %@", modelPath);
  }
  return self;
}

- (CVPixelBufferRef)runSegmentationOn:(CVPixelBufferRef)pixelBuffer {
  CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
  size_t originalWidth = CVPixelBufferGetWidth(pixelBuffer);
  size_t originalHeight = CVPixelBufferGetHeight(pixelBuffer);

  @try {
    // Create MPImage from CVPixelBuffer
    NSError *error = nil;
    MPPImage *mpImage = [[MPPImage alloc] initWithPixelBuffer:pixelBuffer error:&error];
    
    if (error) {
      NSLog(@"Failed to create MPPImage: %@", error.localizedDescription);
      CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
      return nil;
    }
    
    // Generate timestamp for video mode (MediaPipe requires timestamps for video mode)
    NSInteger timestampMs = _frameCount * 33; // ~30 FPS (33ms per frame)
    _frameCount++;
    
    // Run MediaPipe segmentation
    MPPImageSegmenterResult *result = [self.segmenter segmentVideoFrame:mpImage 
                                                     timestampInMilliseconds:timestampMs 
                                                                       error:&error];
    
    if (error) {
      NSLog(@"MediaPipe segmentation failed: %@", error.localizedDescription);
      CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
      return nil;
    }
    
    if (!result || !result.categoryMask) {
      NSLog(@"No segmentation result or category mask returned");
      CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
      return nil;
    }
    
    // Extract mask data from MediaPipe result
    MPPMask *categoryMask = result.categoryMask;
    NSInteger maskWidth = categoryMask.width;
    NSInteger maskHeight = categoryMask.height;
    
    // Create raw mask buffer at MediaPipe's output resolution
    CVPixelBufferRef rawMaskBuffer = NULL;
    NSDictionary *attrs = @{
      (id)kCVPixelBufferCGImageCompatibilityKey: @YES,
      (id)kCVPixelBufferCGBitmapContextCompatibilityKey: @YES,
    };
    
    CVReturn cvResult = CVPixelBufferCreate(kCFAllocatorDefault,
                                           maskWidth,
                                           maskHeight,
                                           kCVPixelFormatType_OneComponent8,
                                           (__bridge CFDictionaryRef)attrs,
                                           &rawMaskBuffer);
    
    if (cvResult != kCVReturnSuccess || !rawMaskBuffer) {
      NSLog(@"Failed to create raw mask pixel buffer");
      CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
      return nil;
    }
    
    // Copy mask data with smooth processing
    CVPixelBufferLockBaseAddress(rawMaskBuffer, 0);
    uint8_t *maskPixels = CVPixelBufferGetBaseAddress(rawMaskBuffer);
    size_t maskBytesPerRow = CVPixelBufferGetBytesPerRow(rawMaskBuffer);
    
    const UInt8 *maskData = categoryMask.uint8Data;
    
    for (NSInteger y = 0; y < maskHeight; y++) {
      for (NSInteger x = 0; x < maskWidth; x++) {
        UInt8 maskValue = maskData[y * maskWidth + x];
        
        // IMPORTANT: MediaPipe SelfieSegmenter outputs 0 for person, 1 for background
        // But CIBlendWithMask expects 255 for person (show original), 0 for background (show blurred)
        // So we need to INVERT the mask
        float normalized = (255 - maskValue) / 255.0f; // Invert the mask
        
        // Soft sigmoid around 0.5 threshold for smoother transitions
        float threshold = 0.5f;
        float softness = 0.15f;
        float smoothed = 1.0f / (1.0f + expf(-(normalized - threshold) / softness));
        
        // Convert back to 0-255 range
        maskPixels[y * maskBytesPerRow + x] = (uint8_t)(smoothed * 255.0f);
      }
    }
    CVPixelBufferUnlockBaseAddress(rawMaskBuffer, 0);
    
    // Apply edge smoothing and scale to original resolution
    CIImage *rawMaskImage = [CIImage imageWithCVPixelBuffer:rawMaskBuffer];
    
    // Light gaussian blur for anti-aliasing
    CIImage *smoothedMask = [rawMaskImage imageByApplyingGaussianBlurWithSigma:0.6];
    
    // Scale to original video resolution using high-quality interpolation
    float scaleX = (float)originalWidth / maskWidth;
    float scaleY = (float)originalHeight / maskHeight;
    
    CIImage *finalMask = [smoothedMask imageByApplyingFilter:@"CILanczosScaleTransform"
                                        withInputParameters:@{
                                          kCIInputScaleKey: @(scaleX),
                                          kCIInputAspectRatioKey: @(scaleY / scaleX)
                                        }];
    
    // Create final mask buffer at original resolution
    CVPixelBufferRef finalMaskBuffer = NULL;
    CVPixelBufferCreate(kCFAllocatorDefault,
                        originalWidth,
                        originalHeight,
                        kCVPixelFormatType_OneComponent8,
                        (__bridge CFDictionaryRef)attrs,
                        &finalMaskBuffer);
    
    if (finalMaskBuffer) {
      CGRect renderRect = CGRectMake(0, 0, originalWidth, originalHeight);
      [self.ciContext render:finalMask toCVPixelBuffer:finalMaskBuffer bounds:renderRect colorSpace:nil];
    }
    
    CVPixelBufferRelease(rawMaskBuffer);
    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
    
    return finalMaskBuffer;
    
  } @catch (NSException *exception) {
    NSLog(@"Exception in MediaPipe segmentation: %@", exception.reason);
    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
    return nil;
  }
}

- (void)dealloc {
  // MediaPipe objects are automatically managed by ARC
  NSLog(@"MediaPipeSegmenter deallocated");
}

@end