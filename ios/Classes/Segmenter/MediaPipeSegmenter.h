#import <Foundation/Foundation.h>
#import <CoreVideo/CoreVideo.h>

NS_ASSUME_NONNULL_BEGIN

@interface MediaPipeSegmenter : NSObject

- (instancetype)initWithModelPath:(NSString *)modelPath;
- (nullable CVPixelBufferRef)runSegmentationOn:(CVPixelBufferRef)pixelBuffer;

@end

NS_ASSUME_NONNULL_END
