package com.cloudwebrtc.webrtc;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.RendererCommon;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoFrame;

import java.util.concurrent.CountDownLatch;

import io.flutter.view.TextureRegistry;

/**
 * Display the video stream on a Surface.
 * renderFrame() is asynchronous to avoid blocking the calling thread.
 * This class is thread safe and handles access from potentially three different threads:
 * Interaction from the main app in init, release and setMirror.
 * Interaction from C++ rtc::VideoSinkInterface in renderFrame.
 * Interaction from SurfaceHolder lifecycle in surfaceCreated, surfaceChanged, and surfaceDestroyed.
 */
public class SurfaceTextureRenderer extends EglRenderer {
  // Callback for reporting renderer events. Read-only after initilization so no lock required.
  private RendererCommon.RendererEvents rendererEvents;
  private final Object layoutLock = new Object();
  private boolean isRenderingPaused;
  private boolean isFirstFrameRendered;
  private int rotatedFrameWidth;
  private int rotatedFrameHeight;
  private int frameRotation;

  /**
   * In order to render something, you must first call init().
   */
  public SurfaceTextureRenderer(String name) {
    super(name);
  }

  public void init(final EglBase.Context sharedContext,
                   RendererCommon.RendererEvents rendererEvents) {
    init(sharedContext, rendererEvents, EglBase.CONFIG_PLAIN, new GlRectDrawer());
  }

  /**
   * Initialize this class, sharing resources with |sharedContext|. The custom |drawer| will be used
   * for drawing frames on the EGLSurface. This class is responsible for calling release() on
   * |drawer|. It is allowed to call init() to reinitialize the renderer after a previous
   * init()/release() cycle.
   */
  public void init(final EglBase.Context sharedContext,
                   RendererCommon.RendererEvents rendererEvents, final int[] configAttributes,
                   RendererCommon.GlDrawer drawer) {
    ThreadUtils.checkIsOnMainThread();
    this.rendererEvents = rendererEvents;
    synchronized (layoutLock) {
      isFirstFrameRendered = false;
      rotatedFrameWidth = 0;
      rotatedFrameHeight = 0;
      frameRotation = -1;
    }
    super.init(sharedContext, configAttributes, drawer);
  }
  @Override
  public void init(final EglBase.Context sharedContext, final int[] configAttributes,
                   RendererCommon.GlDrawer drawer) {
    init(sharedContext, null /* rendererEvents */, configAttributes, drawer);
  }
  /**
   * Limit render framerate.
   *
   * @param fps Limit render framerate to this value, or use Float.POSITIVE_INFINITY to disable fps
   *            reduction.
   */
  @Override
  public void setFpsReduction(float fps) {
    synchronized (layoutLock) {
      isRenderingPaused = fps == 0f;
    }
    super.setFpsReduction(fps);
  }
  @Override
  public void disableFpsReduction() {
    synchronized (layoutLock) {
      isRenderingPaused = false;
    }
    super.disableFpsReduction();
  }
  @Override
  public void pauseVideo() {
    synchronized (layoutLock) {
      isRenderingPaused = true;
    }
    super.pauseVideo();
  }
  // VideoSink interface.
  @Override
  public void onFrame(VideoFrame frame) {
    if(surface == null) {
      // Use original buffer dimensions to prevent automatic rotation
      producer.setSize(frame.getBuffer().getWidth(), frame.getBuffer().getHeight());
      surface = producer.getSurface();
      createEglSurface(surface);
    }
    updateFrameDimensionsAndReportEvents(frame);
    super.onFrame(frame);
  }

  private Surface surface = null;

  private TextureRegistry.SurfaceProducer producer;

  public void surfaceCreated(final TextureRegistry.SurfaceProducer producer) {
    ThreadUtils.checkIsOnMainThread();
    this.producer = producer;
    this.producer.setCallback(
            new TextureRegistry.SurfaceProducer.Callback() {
              @Override
              public void onSurfaceAvailable() {
                // Do surface initialization here, and draw the current frame.
              }

              @Override
              public void onSurfaceCleanup() {
                surfaceDestroyed();
              }
            }
    );
  }

  public void surfaceDestroyed() {
    ThreadUtils.checkIsOnMainThread();
    final CountDownLatch completionLatch = new CountDownLatch(1);
    releaseEglSurface(completionLatch::countDown);
    ThreadUtils.awaitUninterruptibly(completionLatch);
    surface = null;
  }

  // Update frame dimensions and report any changes to |rendererEvents|.
  private void updateFrameDimensionsAndReportEvents(VideoFrame frame) {
    synchronized (layoutLock) {
      if (isRenderingPaused) {
        return;
      }
      if (!isFirstFrameRendered) {
        isFirstFrameRendered = true;
        if (rendererEvents != null) {
          rendererEvents.onFirstFrameRendered();
        }
      }
      
      // Use original buffer dimensions to prevent automatic rotation
      int bufferWidth = frame.getBuffer().getWidth();
      int bufferHeight = frame.getBuffer().getHeight();
      
      if (rotatedFrameWidth != bufferWidth
              || rotatedFrameHeight != bufferHeight
              || frameRotation != 0) { // Force rotation to 0
        if (rendererEvents != null) {
          rendererEvents.onFrameResolutionChanged(
                  bufferWidth, bufferHeight, 0); // Report 0 rotation
        }
        rotatedFrameWidth = bufferWidth;
        rotatedFrameHeight = bufferHeight;
        producer.setSize(rotatedFrameWidth, rotatedFrameHeight);
        frameRotation = 0; // Force no rotation
      }
    }
  }
}
