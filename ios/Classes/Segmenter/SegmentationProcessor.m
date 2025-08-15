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
@property (nonatomic, strong) AVCaptureVideoDataOutput *videoOutput;
@end

@implementation SegmentationProcessor

- (instancetype)initWithSource:(RTCVideoSource *)source modelPath:(NSString *)modelPath mode:(NSString *)mode {
    self = [super init];
    if (self) {
        _source = source;
        _ciContext = [CIContext contextWithOptions:nil];
        _segmenter = [[MediaPipeSegmenter alloc] initWithModelPath:modelPath];
        _mode = mode;
    }
    return self;
}

- (void)setVirtualImageFromPath:(NSString *)path {
    NSURL *url = [NSURL fileURLWithPath:path];
    CIImage *img = [CIImage imageWithContentsOfURL:url];
    if (img) {
        self.virtualBackground = img;
        self.mode = @"image";
    }
}

- (void)startCapture {
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    if (status != AVAuthorizationStatusAuthorized) {
        NSLog(@"[WebRTC] Camera access not authorized");
        return;
    }
    
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        self.session = [[AVCaptureSession alloc] init];
        self.session.sessionPreset = AVCaptureSessionPresetMedium;
        AVCaptureDevice *camera = [self getCamera];
        
        NSError *error = nil;
        AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:camera error:&error];
        if (error || ![self.session canAddInput:input]) {
            NSLog(@"[WebRTC] Failed to add camera input: %@", error.localizedDescription);
            return;
        }
        
        [self.session addInput:input];
        
        self.videoOutput = [[AVCaptureVideoDataOutput alloc] init];
        self.videoOutput.videoSettings = @{(id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)};
        self.videoOutput.alwaysDiscardsLateVideoFrames = NO;
        
        dispatch_queue_t queue = dispatch_queue_create("SegmentationQueue", DISPATCH_QUEUE_SERIAL);
        [self.videoOutput setSampleBufferDelegate:self queue:queue];
        
        if ([self.session canAddOutput:self.videoOutput]) {
            [self.session addOutput:self.videoOutput];
            
            // Ensure the video orientation is set
            AVCaptureConnection *connection = [self.videoOutput connectionWithMediaType:AVMediaTypeVideo];
            if (connection.isVideoOrientationSupported) {
                connection.videoOrientation = AVCaptureVideoOrientationPortrait;
            }
        } else {
            NSLog(@"[WebRTC] Could not add video output");
        }
        
        NSLog(@"[WebRTC] startRunning");
        [self.session startRunning];
    });
}

- (void)stopCapture {
    NSLog(@"[WebRTC] stopCapture called");
    
    if (self.session) {
        if ([self.session isRunning]) {
            [self.session stopRunning];
        }
        
        // Remove all inputs
        for (AVCaptureInput *input in self.session.inputs) {
            [self.session removeInput:input];
        }
        
        // Remove all outputs
        for (AVCaptureOutput *output in self.session.outputs) {
            [self.session removeOutput:output];
        }
        
        self.session = nil;
    }
    
    self.segmenter = nil;
    
    NSLog(@"[WebRTC] session stopped and released");
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
    } else if ([self.mode isEqualToString:@"image"]) {
        resultBuffer = [self applyVirtualBackgroundTo:pixelBuffer withMask:mask];
    }
    
    if (resultBuffer) {
        NSLog(@"[WebRTC] Sending resultBuffer to webrtc");
        [self sendToWebRTC:resultBuffer];
        CVPixelBufferRelease(resultBuffer);
    } else {
        NSLog(@"[WebRTC] Sending pixelBuffer to webrtc");
        [self sendToWebRTC:pixelBuffer];
    }
}

- (CVPixelBufferRef)applyBlurTo:(CVPixelBufferRef)buffer withMask:(CVPixelBufferRef)mask {
    NSLog(@"[WebRTC] Applying blur");
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
    NSLog(@"[WebRTC] Sending buffer to webrtc");
    RTCCVPixelBuffer *rtcBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:buffer];
    int64_t timestampNs = (int64_t)(CACurrentMediaTime() * 1e9);
    RTCVideoFrame *frame = [[RTCVideoFrame alloc] initWithBuffer:rtcBuffer rotation:RTCVideoRotation_0 timeStampNs:timestampNs];
    
    RTCVideoCapturer *dummy = [[RTCVideoCapturer alloc] initWithDelegate:self.source];
    [self.source capturer:dummy didCaptureVideoFrame:frame];
}

- (AVCaptureDevice*)getCamera {
    AVCaptureDevicePosition desiredPosition = AVCaptureDevicePositionFront;
    AVCaptureDeviceDiscoverySession *discoverySession = [AVCaptureDeviceDiscoverySession
                                                         discoverySessionWithDeviceTypes:@[AVCaptureDeviceTypeBuiltInWideAngleCamera]
                                                         mediaType:AVMediaTypeVideo
                                                         position:desiredPosition];
    
    AVCaptureDevice *camera = nil;
    for (AVCaptureDevice *device in discoverySession.devices) {
        if (device.position == desiredPosition) {
            camera = device;
            break;
        }
    }
    
    if (!camera) {
        // Fallback to default if front camera not found
        camera = discoverySession.devices.firstObject;
    }
    
    return camera;
}

-(void) dealloc {
    [self stopCapture];
}

@end
