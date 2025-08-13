#import "MediaPipeSegmenter.h"
#import <TensorFlowLiteC/TensorFlowLiteC.h>
#import <CoreImage/CoreImage.h>

@interface MediaPipeSegmenter ()
@property(nonatomic) TfLiteModel *model;
@property(nonatomic) TfLiteInterpreterOptions *options;
@property(nonatomic) TfLiteInterpreter *interpreter;
@property(nonatomic) int inputWidth;
@property(nonatomic) int inputHeight;
@end

@implementation MediaPipeSegmenter

- (instancetype)initWithModelPath:(NSString *)modelPath {
  self = [super init];
  if (self) {
    _model = TfLiteModelCreateFromFile([modelPath UTF8String]);
    _options = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(_options, 2);
    _interpreter = TfLiteInterpreterCreate(_model, _options);

    if (TfLiteInterpreterAllocateTensors(_interpreter) != kTfLiteOk) {
      NSLog(@"Failed to allocate tensors.");
      return nil;
    }

    const TfLiteTensor *inputTensor = TfLiteInterpreterGetInputTensor(_interpreter, 0);
    if (!inputTensor) {
      NSLog(@"Failed to get input tensor.");
      return nil;
    }

    const TfLiteIntArray *dims = TfLiteTensorDims(inputTensor);
    _inputHeight = dims->data[1];
    _inputWidth = dims->data[2];
  }
  return self;
}

- (CVPixelBufferRef)runSegmentationOn:(CVPixelBufferRef)pixelBuffer {
  CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
  size_t width = CVPixelBufferGetWidth(pixelBuffer);
  size_t height = CVPixelBufferGetHeight(pixelBuffer);

  // Resize pixel buffer to model input size
  CIImage *image = [CIImage imageWithCVPixelBuffer:pixelBuffer];
  CIImage *resized = [image imageByApplyingFilter:@"CILanczosScaleTransform"
                                withInputParameters:@{
                                  kCIInputScaleKey: @((float)_inputWidth / width),
                                  kCIInputAspectRatioKey: @(1.0)
                                }];

  CIContext *context = [CIContext context];
  CVPixelBufferRef resizedBuffer = NULL;
  NSDictionary *attrs = @{
    (id)kCVPixelBufferCGImageCompatibilityKey: @YES,
    (id)kCVPixelBufferCGBitmapContextCompatibilityKey: @YES,
  };
  CVPixelBufferCreate(kCFAllocatorDefault,
                      _inputWidth,
                      _inputHeight,
                      kCVPixelFormatType_32BGRA,
                      (__bridge CFDictionaryRef)attrs,
                      &resizedBuffer);

  if (!resizedBuffer) {
    NSLog(@"Failed to create resized pixel buffer");
    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
    return nil;
  }

  [context render:resized toCVPixelBuffer:resizedBuffer];

  // Prepare input buffer
  size_t byteCount = _inputWidth * _inputHeight * 3; // RGB
  uint8_t *inputBuffer = malloc(byteCount);
  if (!inputBuffer) {
    CVPixelBufferRelease(resizedBuffer);
    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
    return nil;
  }

  // Extract RGB from resizedBuffer (BGRA)
  CVPixelBufferLockBaseAddress(resizedBuffer, kCVPixelBufferLock_ReadOnly);
  uint8_t *base = CVPixelBufferGetBaseAddress(resizedBuffer);
  size_t stride = CVPixelBufferGetBytesPerRow(resizedBuffer);

  for (int y = 0; y < _inputHeight; y++) {
    for (int x = 0; x < _inputWidth; x++) {
      size_t index = y * stride + x * 4;
      size_t offset = (y * _inputWidth + x) * 3;
      inputBuffer[offset] = base[index + 2];     // R
      inputBuffer[offset + 1] = base[index + 1]; // G
      inputBuffer[offset + 2] = base[index];     // B
    }
  }
  CVPixelBufferUnlockBaseAddress(resizedBuffer, kCVPixelBufferLock_ReadOnly);
  CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);

  // Set input tensor
  TfLiteTensor *inputTensor = TfLiteInterpreterGetInputTensor(_interpreter, 0);
  TfLiteTensorCopyFromBuffer(inputTensor, inputBuffer, byteCount);
  free(inputBuffer);

  // Run inference
  if (TfLiteInterpreterInvoke(_interpreter) != kTfLiteOk) {
    NSLog(@"TFLite interpreter invoke failed.");
    CVPixelBufferRelease(resizedBuffer);
    return nil;
  }

  // Get output tensor
  const TfLiteTensor *outputTensor = TfLiteInterpreterGetOutputTensor(_interpreter, 0);
  int outputSize = _inputWidth * _inputHeight;
  float *outputData = malloc(sizeof(float) * outputSize);
  TfLiteTensorCopyToBuffer(outputTensor, outputData, sizeof(float) * outputSize);

  // Convert mask to binary grayscale CVPixelBuffer
  CVPixelBufferRef maskBuffer = NULL;
  CVPixelBufferCreate(kCFAllocatorDefault,
                      _inputWidth,
                      _inputHeight,
                      kCVPixelFormatType_OneComponent8,
                      (__bridge CFDictionaryRef)attrs,
                      &maskBuffer);

  if (maskBuffer) {
    CVPixelBufferLockBaseAddress(maskBuffer, 0);
    uint8_t *maskPixels = CVPixelBufferGetBaseAddress(maskBuffer);
    for (int i = 0; i < outputSize; i++) {
      float confidence = outputData[i];
      maskPixels[i] = confidence > 0.5f ? 255 : 0;
    }
    CVPixelBufferUnlockBaseAddress(maskBuffer, 0);
  }

  free(outputData);
  CVPixelBufferRelease(resizedBuffer);
  return maskBuffer;
}

- (void)dealloc {
  if (_interpreter) TfLiteInterpreterDelete(_interpreter);
  if (_options) TfLiteInterpreterOptionsDelete(_options);
  if (_model) TfLiteModelDelete(_model);
}

@end