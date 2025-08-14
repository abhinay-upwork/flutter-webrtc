import Foundation
import MediaPipeTasksVision

@objc class MediaPipeSegmenterWrapper: NSObject {
  private var segmenter: ImageSegmenter?

  @objc func setup(modelPath: String) -> Bool {
    let options = ImageSegmenterOptions()
    options.baseOptions.modelAssetPath = modelPath
    options.shouldOutputCategoryMask = true
    options.runningMode = .video

    do {
      segmenter = try ImageSegmenter(options: options)
      return true
    } catch {
      print("Failed to create ImageSegmenter: \(error)")
      return false
    }
  }

  @objc func segment(pixelBuffer: CVPixelBuffer) -> MPImage? {
    guard let segmenter else { return nil }
    let mpImage = MPImage(pixelBuffer: pixelBuffer, orientation: .up)
    let result = try? segmenter.segment(videoFrame: mpImage)
    return result?.categoryMask
  }
}
