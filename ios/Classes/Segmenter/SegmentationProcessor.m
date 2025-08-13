#import "SegmentationProcessor.h"
#import "MediaPipeSegmenter.h"
#import <CoreImage/CoreImage.h>

@interface SegmentationProcessor ()
@property(nonatomic, strong) RTCVideoSource *source;
@property(nonatomic, strong) AVCaptureSession *session;
@property(nonatomic, strong) MediaPipeSegmenter *segmenter;
@property(nonatomic, strong) CIContext *ciContext;
@property(nonatomic, strong, nullable) CIImage *virtualBackground;
@property(nonatomic, strong) NSString *mode;
@end

@implementation SegmentationProcessor

- (instancetype)initWithSource:(RTCVideoSource *)source modelPath:(NSString *)modelPath {
  self = [super init];
  if (self) {
    _source = source;
    _ciContext = [CIContext contextWithOptions:nil];
    _segmenter = [[MediaPipeSegmenter alloc] initWithModelPath:modelPath];
    _mode = @"none";
  }
  return self;
}

- (void)setMode:(NSString *)mode {
  self.mode = mode;
}

- (void)setVirtualImageFromPath:(NSString *)path {
  NSURL *url = [NSURL fileURLWithPath:path];
  CIImage *img = [CIImage imageWithContentsOfURL:url];
  if (img) {
    self.virtualBackground = img;
    self.mode = @"virtual";
  }
}

- (void)startCapture {
  dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
    self.session = [[AVCaptureSession alloc] init];
    self.session.sessionPreset = AVCaptureSessionPresetMedium;

    AVCaptureDevice *camera = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
    if (!camera) return;

    NSError *error = nil;
    AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:camera error:&error];
    if (error || ![self.session canAddInput:input]) return;
    [self.session addInput:input];

    AVCaptureVideoDataOutput *output = [[AVCaptureVideoDataOutput alloc] init];
    output.videoSettings = @{(id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)};
    [output setSampleBufferDelegate:self queue:dispatch_queue_create("SegmentationQueue", NULL)];

    if ([self.session canAddOutput:output]) {
      [self.session addOutput:output];
    }

    dispatch_async(dispatch_get_main_queue(), ^{
      [self.session startRunning];
    });
  });
}

- (void)captureOutput:(AVCaptureOutput *)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection *)connection {
  CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
  if (!pixelBuffer) return;

  if ([self.mode isEqualToString:@"none"]) {
    [self sendToWebRTC:pixelBuffer];
    return;
  }

  CVPixelBufferRef mask = [self.segmenter runSegmentationOn:pixelBuffer];
  if (!mask) {
    [self sendToWebRTC:pixelBuffer];
    return;
  }

  CVPixelBufferRef resultBuffer = NULL;
  if ([self.mode isEqualToString:@"blur"]) {
    resultBuffer = [self applyBlurTo:pixelBuffer withMask:mask];
  } else if ([self.mode isEqualToString:@"virtual"]) {
    resultBuffer = [self applyVirtualBackgroundTo:pixelBuffer withMask:mask];
  }

  if (resultBuffer) {
    [self sendToWebRTC:resultBuffer];
    CVPixelBufferRelease(resultBuffer);
  } else {
    [self sendToWebRTC:pixelBuffer];
  }
}

- (CVPixelBufferRef)applyBlurTo:(CVPixelBufferRef)buffer withMask:(CVPixelBufferRef)mask {
  CIImage *input = [CIImage imageWithCVPixelBuffer:buffer];
  CIImage *blurred = [input imageByApplyingGaussianBlurWithSigma:10];
  CIImage *maskImage = [CIImage imageWithCVPixelBuffer:mask];

  CIImage *composite = [input imageByApplyingFilter:@"CIBlendWithMask"
                                  withInputParameters:@{
                                    kCIInputBackgroundImageKey: blurred,
                                    kCIInputMaskImageKey: maskImage
                                  }];
  return [self renderToPixelBuffer:composite withSize:input.extent.size];
}

- (CVPixelBufferRef)applyVirtualBackgroundTo:(CVPixelBufferRef)buffer withMask:(CVPixelBufferRef)mask {
  if (!self.virtualBackground) return NULL;

  CIImage *input = [CIImage imageWithCVPixelBuffer:buffer];
  CIImage *maskImage = [CIImage imageWithCVPixelBuffer:mask];
  CIImage *resizedBG = [self.virtualBackground imageByCroppingToRect:input.extent];

  CIImage *composite = [input imageByApplyingFilter:@"CIBlendWithMask"
                                  withInputParameters:@{
                                    kCIInputBackgroundImageKey: resizedBG,
                                    kCIInputMaskImageKey: maskImage
                                  }];
  return [self renderToPixelBuffer:composite withSize:input.extent.size];
}

- (CVPixelBufferRef)renderToPixelBuffer:(CIImage *)image withSize:(CGSize)size {
  CVPixelBufferRef outputBuffer = NULL;
  NSDictionary *attrs = @{
    (id)kCVPixelBufferCGImageCompatibilityKey: @YES,
    (id)kCVPixelBufferCGBitmapContextCompatibilityKey: @YES
  };
  CVPixelBufferCreate(kCFAllocatorDefault,
                      (size_t)size.width,
                      (size_t)size.height,
                      kCVPixelFormatType_32BGRA,
                      (__bridge CFDictionaryRef)attrs,
                      &outputBuffer);

  if (outputBuffer) {
    [self.ciContext render:image toCVPixelBuffer:outputBuffer];
  }
  return outputBuffer;
}

- (void)sendToWebRTC:(CVPixelBufferRef)buffer {
  RTCCVPixelBuffer *rtcBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:buffer];
  int64_t timestampNs = (int64_t)(CACurrentMediaTime() * 1e9);
  RTCVideoFrame *frame = [[RTCVideoFrame alloc] initWithBuffer:rtcBuffer rotation:RTCVideoRotation_0 timeStampNs:timestampNs];

  RTCVideoCapturer *dummy = [[RTCVideoCapturer alloc] initWithDelegate:self.source];
  [self.source capturer:dummy didCaptureVideoFrame:frame];
}

@end