// import Foundation
// import MediaPipeTasksVision

// @objc(MediaPipeSegmenterWrapper)
// public class MediaPipeSegmenterWrapper: NSObject {
//     private var segmenter: ImageSegmenter?
    
//     @objc
//     public init(modelPath: String) {
//         super.init()
//         let options = ImageSegmenterOptions()
//         options.baseOptions.modelAssetPath = modelPath
//         options.shouldOutputCategoryMask = true
//         options.runningMode = .video
        
//         do {
//             segmenter = try ImageSegmenter(options: options)
//         } catch {
//             print("Failed to create ImageSegmenter: \(error)")
//         }
//     }
    
//     @objc
//     public func segment(pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
//         guard let segmenter else { return nil }
        
//         let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        
//         do {
//             let mpImage = try MPImage(pixelBuffer: pixelBuffer, orientation: .up)
//             let result = try segmenter.segment(videoFrame: mpImage, timestampInMilliseconds: timestamp)
//             guard let mask = result.categoryMask else { return nil }
            
//             let width = mask.width
//             let height = mask.height
            
//             var outputPixelBuffer: CVPixelBuffer?
//             let attrs: [CFString: Any] = [
//                 kCVPixelBufferCGImageCompatibilityKey: true,
//                 kCVPixelBufferCGBitmapContextCompatibilityKey: true
//             ]
            
//             let status = CVPixelBufferCreate(
//                 kCFAllocatorDefault,
//                 width,
//                 height,
//                 kCVPixelFormatType_OneComponent8,
//                 attrs as CFDictionary,
//                 &outputPixelBuffer
//             )
            
//             guard status == kCVReturnSuccess, let buffer = outputPixelBuffer else { return nil }
            
//             CVPixelBufferLockBaseAddress(buffer, [])
//             guard let baseAddress = CVPixelBufferGetBaseAddress(buffer) else {
//                 CVPixelBufferUnlockBaseAddress(buffer, [])
//                 return nil
//             }
            
//             let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
//             let dest = baseAddress.assumingMemoryBound(to: UInt8.self)
            
//             // Rebind raw memory to Float32
//             let floatData = mask.float32Data
//             floatData.withMemoryRebound(to: Float32.self, capacity: width * height) { floatBuffer in
//                 for y in 0..<height {
//                     for x in 0..<width {
//                         let index = y * width + x
//                         let confidence = floatBuffer[index]
//                         let clamped = UInt8(clamping: Int(confidence * 255.0))
//                         dest[y * bytesPerRow + x] = clamped
//                     }
//                 }
//             }
            
//             CVPixelBufferUnlockBaseAddress(buffer, [])
//             return buffer
            
//         } catch {
//             print("Segmentation failed: \(error)")
//             return nil
//         }
//     }
    
// }
