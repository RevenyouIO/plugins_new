package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import static io.flutter.plugins.camera.CameraUtils.computeBestPreviewSize;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect; 
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import androidx.annotation.NonNull;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Camera {
  private final SurfaceTextureEntry flutterTexture;
  private final CameraManager cameraManager;
  private final OrientationEventListener orientationEventListener;
  private final boolean isFrontFacing;
  private final int sensorOrientation;
  private final String cameraName;
  private final Size captureSize;
  private final Size previewSize;
  private final boolean enableAudio;

  private CameraDevice cameraDevice;
  private CameraCaptureSession cameraCaptureSession;
  private ImageReader pictureImageReader;
  private ImageReader imageStreamReader;
  private DartMessenger dartMessenger;
  private CaptureRequest.Builder captureRequestBuilder;
  private CaptureRequest mPreviewRequest;
  private MediaRecorder mediaRecorder;
  private boolean recordingVideo;
  private CamcorderProfile recordingProfile;
  private int currentOrientation = ORIENTATION_UNKNOWN;
  private Handler mBackgroundHandler;
  private HandlerThread mBackgroundThread;
  private static final int STATE_PREVIEW = 0;
  private static final int STATE_WAITING_LOCK = 1;
  private static final int STATE_WAITING_PRECAPTURE = 2;
  private static final int STATE_WAITING_NON_PRECAPTURE = 3;
  private static final int STATE_PICTURE_TAKEN = 4;
  private int mState = STATE_PREVIEW;
  

  // Mirrors camera.dart
  public enum ResolutionPreset {
    low,
    medium,
    high,
    veryHigh,
    ultraHigh,
    max,
  }

  public Camera(
      final Activity activity,
      final SurfaceTextureEntry flutterTexture,
      final DartMessenger dartMessenger,
      final String cameraName,
      final String resolutionPreset,
      final boolean enableAudio)
      throws CameraAccessException {
    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }

    this.cameraName = cameraName;
    this.enableAudio = enableAudio;
    this.flutterTexture = flutterTexture;
    this.dartMessenger = dartMessenger;
    this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    orientationEventListener =
        new OrientationEventListener(activity.getApplicationContext()) {
          @Override
          public void onOrientationChanged(int i) {
            if (i == ORIENTATION_UNKNOWN) {
              return;
            }
            // Convert the raw deg angle to the nearest multiple of 90.
            currentOrientation = (int) Math.round(i / 90.0) * 90;
          }
        };
    orientationEventListener.enable();

    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
    StreamConfigurationMap streamConfigurationMap =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    //noinspection ConstantConditions
    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    //noinspection ConstantConditions
    isFrontFacing =
        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
    ResolutionPreset preset = ResolutionPreset.valueOf(resolutionPreset);
    recordingProfile =
        CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset);
    captureSize = new Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    previewSize = computeBestPreviewSize(cameraName, preset);
  }

	


  private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                /*case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }*/
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };




  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    if (mediaRecorder != null) {
      mediaRecorder.release();
    }
    mediaRecorder = new MediaRecorder();

    // There's a specific order that mediaRecorder expects. Do not change the order
    // of these function calls.
    if (enableAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    mediaRecorder.setOutputFormat(recordingProfile.fileFormat);
    if (enableAudio) mediaRecorder.setAudioEncoder(recordingProfile.audioCodec);
    mediaRecorder.setVideoEncoder(recordingProfile.videoCodec);
    mediaRecorder.setVideoEncodingBitRate(recordingProfile.videoBitRate);
    if (enableAudio) mediaRecorder.setAudioSamplingRate(recordingProfile.audioSampleRate);
    mediaRecorder.setVideoFrameRate(recordingProfile.videoFrameRate);
    mediaRecorder.setVideoSize(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    mediaRecorder.setOutputFile(outputFilePath);
    mediaRecorder.setOrientationHint(getMediaOrientation());

    mediaRecorder.prepare();
  }

  @SuppressLint("MissingPermission")
  public void open(@NonNull final Result result) throws CameraAccessException {
    pictureImageReader =
        ImageReader.newInstance(
            captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

    // Used to steam image byte data to dart side.
    imageStreamReader =
        ImageReader.newInstance(
            previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

    cameraManager.openCamera(
        cameraName,
        new CameraDevice.StateCallback() {
          @Override
          public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = device;
            try {
              startPreview();
            } catch (CameraAccessException e) {
              result.error("CameraAccess", e.getMessage(), null);
              close();
              return;
            }
            Map<String, Object> reply = new HashMap<>();
            reply.put("textureId", flutterTexture.id());
            reply.put("previewWidth", previewSize.getWidth());
            reply.put("previewHeight", previewSize.getHeight());
            result.success(reply);
          }

          @Override
          public void onClosed(@NonNull CameraDevice camera) {
            dartMessenger.sendCameraClosingEvent();
            super.onClosed(camera);
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            close();
            dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
          }

          @Override
          public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
            close();
            String errorDescription;
            switch (errorCode) {
              case ERROR_CAMERA_IN_USE:
                errorDescription = "The camera device is in use already.";
                break;
              case ERROR_MAX_CAMERAS_IN_USE:
                errorDescription = "Max cameras in use";
                break;
              case ERROR_CAMERA_DISABLED:
                errorDescription = "The camera device could not be opened due to a device policy.";
                break;
              case ERROR_CAMERA_DEVICE:
                errorDescription = "The camera device has encountered a fatal error";
                break;
              case ERROR_CAMERA_SERVICE:
                errorDescription = "The camera service has encountered a fatal error.";
                break;
              default:
                errorDescription = "Unknown camera error";
            }
            dartMessenger.send(DartMessenger.EventType.ERROR, errorDescription);
          }
        },
        null);
  }

  private void writeToFile(ByteBuffer buffer, File file) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      while (0 < buffer.remaining()) {
        outputStream.getChannel().write(buffer);
      }
    }
  }

  SurfaceTextureEntry getFlutterTexture() {
    return flutterTexture;
  }

  public void takePicture(String filePath, @NonNull final Result result) {
    final File file = new File(filePath);

    if (file.exists()) {
      result.error(
          "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
      return;
    }

    pictureImageReader.setOnImageAvailableListener(
        reader -> {
          try (Image image = reader.acquireLatestImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            writeToFile(buffer, file);
            result.success(null);
          } catch (IOException e) {
            result.error("IOError", "Failed saving image", null);
          }
        },
        null);

    try {
      final CaptureRequest.Builder captureBuilder =
          cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      captureBuilder.addTarget(pictureImageReader.getSurface());
      captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());

      cameraCaptureSession.capture(
          captureBuilder.build(),
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureFailed(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull CaptureFailure failure) {
              String reason;
              switch (failure.getReason()) {
                case CaptureFailure.REASON_ERROR:
                  reason = "An error happened in the framework";
                  break;
                case CaptureFailure.REASON_FLUSHED:
                  reason = "The capture has failed due to an abortCaptures() call";
                  break;
                default:
                  reason = "Unknown reason";
              }
              result.error("captureFailure", reason, null);
            }
          },
          null);
    } catch (CameraAccessException e) {
      result.error("cameraAccess", e.getMessage(), null);
    }
  }

  private void createCaptureSession(int templateType, Surface... surfaces)
      throws CameraAccessException {
    createCaptureSession(templateType, null, surfaces);
  }

  private void createCaptureSession(
      int templateType, Runnable onSuccessCallback, Surface... surfaces)
      throws CameraAccessException {
    // Close any existing capture session.
    closeCaptureSession();

    // Create a new capture builder.
    captureRequestBuilder = cameraDevice.createCaptureRequest(templateType);

    // Build Flutter surface to render to
    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
    Surface flutterSurface = new Surface(surfaceTexture);
    captureRequestBuilder.addTarget(flutterSurface);

    List<Surface> remainingSurfaces = Arrays.asList(surfaces);
    if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
      // If it is not preview mode, add all surfaces as targets.
      for (Surface surface : remainingSurfaces) {
        captureRequestBuilder.addTarget(surface);
      }
    }

    // Prepare the callback
    CameraCaptureSession.StateCallback callback =
        new CameraCaptureSession.StateCallback() {
          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            try {
              if (cameraDevice == null) {
                dartMessenger.send(
                    DartMessenger.EventType.ERROR, "The camera was closed during configuration.");
                return;
              }
              cameraCaptureSession = session;
              captureRequestBuilder.set(
                  CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
              cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
              if (onSuccessCallback != null) {
                onSuccessCallback.run();
              }
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
              dartMessenger.send(DartMessenger.EventType.ERROR, e.getMessage());
            }
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            dartMessenger.send(
                DartMessenger.EventType.ERROR, "Failed to configure camera session.");
          }
        };

    // Collect all surfaces we want to render to.
    List<Surface> surfaceList = new ArrayList<>();
    surfaceList.add(flutterSurface);
    surfaceList.addAll(remainingSurfaces);
    // Start the session
    cameraDevice.createCaptureSession(surfaceList, callback, null);
  }

  public void startVideoRecording(String filePath, Result result) {
    if (new File(filePath).exists()) {
      result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
      return;
    }
    try {
      prepareMediaRecorder(filePath);
      recordingVideo = true;
      createCaptureSession(
          CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface());
      result.success(null);
    } catch (CameraAccessException | IOException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      recordingVideo = false;
      mediaRecorder.stop();
      mediaRecorder.reset();
      startPreview();
      result.success(null);
    } catch (CameraAccessException | IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.resume();
      } else {
        result.error(
            "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void startPreview() throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());
  }

  public void startPreviewWithImageStream(EventChannel imageStreamChannel)
      throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_RECORD, imageStreamReader.getSurface());

    imageStreamChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
            setImageStreamImageAvailableListener(imageStreamSink);
          }

          @Override
          public void onCancel(Object o) {
            imageStreamReader.setOnImageAvailableListener(null, null);
          }
        });
  }

   public void handleFocus(double x, double y) throws CameraAccessException { //MotionEvent event) {
    //int pointerId = event.getPointerId(0);
    //int pointerIndex = event.findPointerIndex(pointerId);

    // Get the pointer's current position
    //float x = event.getX(pointerIndex);
    //float y = event.getY(pointerIndex);
	

	Rect touchRect = new Rect(
        (int)(x), 
        (int)(y), 
        (int)(x + 200), 
        (int)(y + 200));

	
    if (cameraName == null) return;
    //CameraManager cm = (CameraManager)this.getSystemService(Context.CAMERA_SERVICE);
    CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraName);
    //try {
  //      cc = cameraManager.getCameraCharacteristics(cameraName);
  //  } catch (CameraAccessException e) {
  //      e.printStackTrace();
  //  }

	final boolean isMeteringAreaAFSupported = false;
	if (CameraCharacteristics.CONTROL_MAX_REGIONS_AF >= 1) {
		isMeteringAreaAFSupported = true;
	}

	final boolean isMeteringAreaAESupported = false;
	if (CameraCharacteristics.CONTROL_MAX_REGIONS_AE >= 1) {
		isMeteringAreaAFSupported = true;
	}


    MeteringRectangle focusArea = new MeteringRectangle(touchRect,MeteringRectangle.METERING_WEIGHT_DONT_CARE);

	cameraCaptureSession.stopRepeating();
	captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);









    //captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
    try {
        cameraCaptureSession.capture(captureRequestBuilder.build(), mCaptureCallback,
                mBackgroundHandler);
        // After this, the camera will go back to the normal state of preview.
        mState = STATE_PREVIEW;
    } catch (CameraAccessException e){
        // log
    }

    if (isMeteringAreaAESupported) {
     captureRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                new MeteringRectangle[]{focusArea});
    }
    if (isMeteringAreaAFSupported) {
        captureRequestBuilder
                .set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

	cameraCaptureSession.capture(captureRequestBuilder.build(), mCaptureCallback,
                mBackgroundHandler);

	captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
	captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
	captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);

	cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);

}

protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

  private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
    imageStreamReader.setOnImageAvailableListener(
        reader -> {
          Image img = reader.acquireLatestImage();
          if (img == null) return;

          List<Map<String, Object>> planes = new ArrayList<>();
          for (Image.Plane plane : img.getPlanes()) {
            ByteBuffer buffer = plane.getBuffer();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes, 0, bytes.length);

            Map<String, Object> planeBuffer = new HashMap<>();
            planeBuffer.put("bytesPerRow", plane.getRowStride());
            planeBuffer.put("bytesPerPixel", plane.getPixelStride());
            planeBuffer.put("bytes", bytes);

            planes.add(planeBuffer);
          }

          Map<String, Object> imageBuffer = new HashMap<>();
          imageBuffer.put("width", img.getWidth());
          imageBuffer.put("height", img.getHeight());
          imageBuffer.put("format", img.getFormat());
          imageBuffer.put("planes", planes);

          imageStreamSink.success(imageBuffer);
          img.close();
        },
        null);
  }

  private void closeCaptureSession() {
    if (cameraCaptureSession != null) {
      cameraCaptureSession.close();
      cameraCaptureSession = null;
    }
  }

  public void close() {
    closeCaptureSession();

    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }
    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }
  }

  public void dispose() {
    close();
    flutterTexture.release();
    orientationEventListener.disable();
  }

  private int getMediaOrientation() {
    final int sensorOrientationOffset =
        (currentOrientation == ORIENTATION_UNKNOWN)
            ? 0
            : (isFrontFacing) ? -currentOrientation : currentOrientation;
    return (sensorOrientationOffset + sensorOrientation + 360) % 360;
  }
}
