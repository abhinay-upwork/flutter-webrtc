#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'flutter_webrtc'
  s.version          = '1.2.0'
  s.summary          = 'Flutter WebRTC plugin for iOS.'
  s.description      = <<-DESC
A new flutter plugin project.
                       DESC
  s.homepage         = 'https://github.com/abhinay-upwork/flutter-webrtc'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'WebRTC-Fork' => 'abhinayvangipuram@upwork.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*.{h,m,mm,swift}'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'
  s.dependency 'WebRTC-SDK', '137.7151.02'
  s.dependency 'TensorFlowLiteC'
  s.dependency 'TensorFlowLiteSwift'
  s.dependency 'MediaPipeTasksVision'
  s.ios.deployment_target = '13.0'
  s.static_framework = true
  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++14',
    'USER_HEADER_SEARCH_PATHS' => 'Classes/**/*.h',
    'DEFINES_MODULE' => 'YES',
    'SWIFT_OBJC_BRIDGING_HEADER' => 'Classes/BridgingHeader/flutter_webrtc-Bridging-Header.h'
  }
  s.libraries = 'c++'
  s.swift_version = '5.0'
end
