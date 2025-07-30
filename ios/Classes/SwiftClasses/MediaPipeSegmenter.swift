// MediaPipeSegmenter.swift
import TensorFlowLite
import CoreVideo

class MediaPipeSegmenter {
  private var interpreter: Interpreter
  private let inputWidth = 256
  private let inputHeight = 256
  
  init?(modelPath: String) {
    do {
      interpreter = try Interpreter(modelPath: modelPath)
      try interpreter.allocateTensors()
    } catch {
      print("MediaPipeSegmenter: Failed to create interpreter - \(error)")
      return nil
    }
  }
  
  func runSegmentation(on pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
    guard let inputData = preprocess(pixelBuffer: pixelBuffer) else {
      print("MediaPipeSegmenter: Failed to preprocess frame")
      return nil
    }
    
    do {
      try interpreter.copy(inputData, toInputAt: 0)
      try interpreter.invoke()
      let outputTensor = try interpreter.output(at: 0)
      return postprocess(outputTensor: outputTensor)
    } catch {
      print("MediaPipeSegmenter: Inference failed - \(error)")
      return nil
    }
  }
  
  private func preprocess(pixelBuffer: CVPixelBuffer) -> Data? {
    CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
    
    let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
    let context = CIContext()
    let resized = ciImage.transformed(by: CGAffineTransform(scaleX: CGFloat(inputWidth) / ciImage.extent.width,
                                                            y: CGFloat(inputHeight) / ciImage.extent.height))
    guard let resizedBuffer = renderToPixelBuffer(image: resized, width: inputWidth, height: inputHeight) else {
      return nil
    }
    
    CVPixelBufferLockBaseAddress(resizedBuffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(resizedBuffer, .readOnly) }
    
    let baseAddress = CVPixelBufferGetBaseAddress(resizedBuffer)!
    let byteCount = CVPixelBufferGetDataSize(resizedBuffer)
    return Data(bytes: baseAddress, count: byteCount)
  }
  
  private func postprocess(outputTensor: Tensor) -> CVPixelBuffer? {
    let outputData = outputTensor.data
    let outputLength = outputTensor.shape.dimensions.reduce(1, *)
    var outputBuffer: CVPixelBuffer?
    let attrs = [
      kCVPixelBufferCGImageCompatibilityKey: true,
      kCVPixelBufferCGBitmapContextCompatibilityKey: true
    ] as CFDictionary
    CVPixelBufferCreate(kCFAllocatorDefault, inputWidth, inputHeight, kCVPixelFormatType_OneComponent8, attrs, &outputBuffer)
    
    guard let buffer = outputBuffer else { return nil }
    CVPixelBufferLockBaseAddress(buffer, [])
    let dst = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
    _ = outputData.copyBytes(to: dst, count: outputLength)
    CVPixelBufferUnlockBaseAddress(buffer, [])
    return buffer
  }
  
  private func renderToPixelBuffer(image: CIImage, width: Int, height: Int) -> CVPixelBuffer? {
    var buffer: CVPixelBuffer?
    let attrs: [CFString: Any] = [
      kCVPixelBufferCGImageCompatibilityKey: true,
      kCVPixelBufferCGBitmapContextCompatibilityKey: true,
      kCVPixelBufferWidthKey: width,
      kCVPixelBufferHeightKey: height,
      kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA
    ]
    
    CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, attrs as CFDictionary, &buffer)
    guard let bufferUnwrapped = buffer else { return nil }
    
    let context = CIContext()
    context.render(image, to: bufferUnwrapped)
    return bufferUnwrapped
  }
}
