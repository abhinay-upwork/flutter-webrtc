#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <WebRTC/WebRTC.h>

NS_ASSUME_NONNULL_BEGIN

@interface SegmentationProcessor : NSObject <AVCaptureVideoDataOutputSampleBufferDelegate>

- (instancetype)initWithSource:(RTCVideoSource *)source modelPath:(NSString *)modelPath mode:(NSString *)mode;
- (void)setVirtualImageFromPath:(NSString *)path;
- (void)startCapture;
- (void)stopCapture;

@end

NS_ASSUME_NONNULL_END
