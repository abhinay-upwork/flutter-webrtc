import Foundation
import AVFoundation
import CoreImage
import WebRTC

@objc public class SegmentationProcessor: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
  private let source: RTCVideoSource
  private let ciContext = CIContext()
  private var session: AVCaptureSession?
  private var mode: String = "none"
  private var virtualImage: CIImage?
  private var segmenter: MediaPipeSegmenter?

  @objc public init(source: RTCVideoSource, modelPath: String) {
    self.source = source
    self.segmenter = MediaPipeSegmenter(modelPath: modelPath)
    super.init()
  }

  @objc public func setMode(_ newMode: String) {
    self.mode = newMode
  }

  @objc public func setVirtualImage(fromPath path: String) {
    let url = URL(fileURLWithPath: path)
    self.virtualImage = CIImage(contentsOf: url)
    self.mode = "virtual"
  }

  @objc public func startCapture() {
    DispatchQueue.global(qos: .userInitiated).async {
      let session = AVCaptureSession()
      session.sessionPreset = .medium

      guard let camera = AVCaptureDevice.default(for: .video),
            let input = try? AVCaptureDeviceInput(device: camera),
            session.canAddInput(input) else {
        print("SegmentationProcessor: Failed to set up input")
        return
      }

      session.addInput(input)

      let output = AVCaptureVideoDataOutput()
      output.videoSettings = [
        kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
      ]
      output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "SegmentationQueue"))

      guard session.canAddOutput(output) else {
        print("SegmentationProcessor: Failed to add output")
        return
      }

      session.addOutput(output)
      DispatchQueue.main.async {
        self.session = session
      }
      session.startRunning()
    }
  }

  public func captureOutput(_ output: AVCaptureOutput,
                            didOutput sampleBuffer: CMSampleBuffer,
                            from connection: AVCaptureConnection) {
    guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

    if mode == "none" {
      sendToWebRTC(pixelBuffer: pixelBuffer)
      return
    }

    guard let maskBuffer = segmenter?.runSegmentation(on: pixelBuffer) else {
      sendToWebRTC(pixelBuffer: pixelBuffer)
      return
    }

    let processedBuffer = (mode == "blur")
      ? applyBlur(pixelBuffer: pixelBuffer, mask: maskBuffer)
      : applyVirtualBackground(pixelBuffer: pixelBuffer, mask: maskBuffer)

    sendToWebRTC(pixelBuffer: processedBuffer)
  }

  private func applyBlur(pixelBuffer: CVPixelBuffer, mask: CVPixelBuffer) -> CVPixelBuffer {
    let inputImage = CIImage(cvPixelBuffer: pixelBuffer)
    let blurred = inputImage.applyingGaussianBlur(sigma: 10)
    let maskImage = CIImage(cvPixelBuffer: mask)

    let composite = inputImage.applyingFilter("CIBlendWithMask", parameters: [
      kCIInputBackgroundImageKey: blurred,
      kCIInputMaskImageKey: maskImage
    ])

    return renderToPixelBuffer(composite, size: inputImage.extent.size) ?? pixelBuffer
  }

  private func applyVirtualBackground(pixelBuffer: CVPixelBuffer, mask: CVPixelBuffer) -> CVPixelBuffer {
    guard let background = virtualImage else { return pixelBuffer }

    let inputImage = CIImage(cvPixelBuffer: pixelBuffer)
    let resizedBG = background.cropped(to: inputImage.extent)
    let maskImage = CIImage(cvPixelBuffer: mask)

    let composite = inputImage.applyingFilter("CIBlendWithMask", parameters: [
      kCIInputBackgroundImageKey: resizedBG,
      kCIInputMaskImageKey: maskImage
    ])

    return renderToPixelBuffer(composite, size: inputImage.extent.size) ?? pixelBuffer
  }

  private func renderToPixelBuffer(_ image: CIImage, size: CGSize) -> CVPixelBuffer? {
    var buffer: CVPixelBuffer?
    let attrs = [
      kCVPixelBufferCGImageCompatibilityKey: true,
      kCVPixelBufferCGBitmapContextCompatibilityKey: true
    ] as CFDictionary

    CVPixelBufferCreate(kCFAllocatorDefault,
                        Int(size.width),
                        Int(size.height),
                        kCVPixelFormatType_32BGRA,
                        attrs,
                        &buffer)

    guard let outputBuffer = buffer else { return nil }

    ciContext.render(image, to: outputBuffer)
    return outputBuffer
  }

  private func sendToWebRTC(pixelBuffer: CVPixelBuffer) {
    let rtcPixelBuffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
    let frame = RTCVideoFrame(buffer: rtcPixelBuffer,
                              rotation: ._0,
                              timeStampNs: Int64(CACurrentMediaTime() * 1_000_000_000))

    let dummyCapturer = RTCVideoCapturer(delegate: source)
    source.capturer(dummyCapturer, didCapture: frame)
  }
}
