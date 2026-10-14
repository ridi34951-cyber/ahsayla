package com.ridiitols.gyrolock;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.util.Size;
import android.os.Environment;
import android.provider.MediaStore;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CaptureRequest;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.widget.SeekBar;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final int REQ = 41;
    private PreviewView previewView;
    private TextView status;
    private Button recordButton;
    private VideoCapture<Recorder> videoCapture;
    private ImageCapture imageCapture;
    private boolean motionPhotoPending = false;
    private File motionStillFile = null;
    private long shutterTimestampNs = 0L;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Camera camera;
    private Button zoom1Button, zoom2Button, zoom4Button, flashButton, hdrButton;
    private Button exposureLockButton;
    private SeekBar exposureSeekBar;
    private TextView exposureLabel;
    private FrameLayout previewContainer;
    private FocusRingView focusRing;
    private Camera2CameraControl camera2Control;
    private boolean hdrSceneSupported = false;
    private boolean hdrEnabled = true;
    private String motionPhotoStamp = null;
    private long recordingStopRequestedNs = 0L;
    private int minExposureIndex = 0, maxExposureIndex = 0, currentExposureIndex = 0;
    private boolean exposureLocked = false;
    private boolean updatingExposureUi = false;
    // Still-photo flash mode; CameraX fires the LED once, synchronized to the still exposure.
    private boolean photoFlashEnabled = false;
    // Remember the last touch-to-focus point for shutter-time AF.
    private float lastFocusX = -1f, lastFocusY = -1f;
    private boolean focusCaptureFinished = false;
    private long recordingStartedNs = 0L;
    private float selectedZoom = 1.0f;
    private Recording recording;
    private ExecutorService cameraExecutor;
    private SensorManager sensorManager;
    private Sensor gyro;
    private final List<GyroSample> gyroSamples = new ArrayList<>();
    private long gyroStartNs = 0L;
    private long lastSavedNs = 0L;
    private boolean collectingGyro = false;
    // Live gyro preview state. This transforms only the preview surface; the saved
    // recording is stabilized from the same gyro stream after recording stops.
    private long liveLastTimestampNs = 0L;
    private double liveRateX = 0, liveRateY = 0, liveRateZ = 0;
    private double liveAngleX = 0, liveAngleY = 0, liveAngleZ = 0;
    private double liveSmoothX = 0, liveSmoothY = 0, liveSmoothZ = 0;
    private double liveAnchorX = 0, liveAnchorY = 0, liveAnchorZ = 0;
    private boolean liveAnglesInitialized = false;
    private File sessionDir;
    private File pendingRawFile;
    private File pendingCsvFile;

    private final class FocusRingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cx, cy;
        private boolean visible = false;
        private final float density;
        FocusRingView(android.content.Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.0f * density);
            paint.setColor(Color.YELLOW);
        }
        void showAt(float x, float y) {
            cx = x; cy = y; visible = true; invalidate();
            removeCallbacks(hideRing);
            postDelayed(hideRing, 1800L);
        }
        private final Runnable hideRing = () -> { visible = false; invalidate(); };
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!visible) return;
            float r = 36.0f * density;
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx - 8*density, cy, cx + 8*density, cy, paint);
            canvas.drawLine(cx, cy - 8*density, cx, cy + 8*density, paint);
        }
    }

    static class GyroSample {
        // Sensor timestamps use Android's monotonic clock, which lets us align samples
        // to the recording start rather than relying on callback arrival time.
        final long timestampNs;
        final double x, y, z;
        GyroSample(long timestampNs, double x, double y, double z) {
            this.timestampNs = timestampNs; this.x = x; this.y = y; this.z = z;
        }
        double secondsFrom(long startNs) { return (timestampNs - startNs) / 1_000_000_000.0; }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF171717);

        // Top quick-control row: continuous torch (not a one-shot photo flash).
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topRow.setPadding(8, 2, 8, 2);
        flashButton = new Button(this);
        flashButton.setText("⚡ FLASH OFF");
        flashButton.setOnClickListener(v -> togglePhotoFlash());
        topRow.addView(flashButton, new LinearLayout.LayoutParams(0, -2, 1));
        hdrButton = new Button(this);
        hdrButton.setText("HDR AUTO");
        hdrButton.setOnClickListener(v -> toggleHdr());
        topRow.addView(hdrButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        previewContainer = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        focusRing = new FocusRingView(this);
        focusRing.setClickable(false);
        focusRing.setFocusable(false);
        previewContainer.addView(focusRing, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        // Tap anywhere in preview: show a focus ring and request AF/AE metering.
        previewView.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                focusRing.showAt(event.getX(), event.getY());
                focusAt(event.getX(), event.getY());
            }
            return true;
        });

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.setPadding(8, 6, 8, 4);
        zoom1Button = new Button(this);
        zoom2Button = new Button(this);
        zoom4Button = new Button(this);
        zoom1Button.setText("1×");
        zoom2Button.setText("2×");
        zoom4Button.setText("4×");
        zoom1Button.setOnClickListener(v -> setZoom(1.0f));
        zoom2Button.setOnClickListener(v -> setZoom(2.0f));
        zoom4Button.setOnClickListener(v -> setZoom(4.0f));
        zoomRow.addView(zoom1Button, new LinearLayout.LayoutParams(0, -2, 1));
        zoomRow.addView(zoom2Button, new LinearLayout.LayoutParams(0, -2, 1));
        zoomRow.addView(zoom4Button, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(zoomRow, new LinearLayout.LayoutParams(-1, -2));

        // Motion Photo video ratio is fixed at 3:4 (720x960).

        // Exposure compensation controls. The AE-lock button freezes the camera's
        // auto-exposure state after the user has chosen the desired brightness.
        LinearLayout exposureRow = new LinearLayout(this);
        exposureRow.setOrientation(LinearLayout.HORIZONTAL);
        exposureRow.setGravity(Gravity.CENTER_VERTICAL);
        exposureRow.setPadding(12, 0, 12, 0);
        exposureLabel = new TextView(this);
        exposureLabel.setTextColor(Color.WHITE);
        exposureLabel.setTextSize(12);
        exposureLabel.setText("EV 0.0");
        exposureRow.addView(exposureLabel, new LinearLayout.LayoutParams(-2, -2));
        exposureSeekBar = new SeekBar(this);
        exposureSeekBar.setMax(100);
        exposureSeekBar.setProgress(50);
        exposureRow.addView(exposureSeekBar, new LinearLayout.LayoutParams(0, -2, 1));
        exposureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingExposureUi || camera == null || exposureLocked) return;
                int range = maxExposureIndex - minExposureIndex;
                int index = minExposureIndex + Math.round(range * (progress / 100.0f));
                applyExposureIndex(index);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        exposureLockButton = new Button(this);
        exposureLockButton.setText("AE LOCK");
        exposureLockButton.setOnClickListener(v -> toggleExposureLock());
        exposureRow.addView(exposureLockButton, new LinearLayout.LayoutParams(-2, -2));
        root.addView(exposureRow, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(16, 14, 16, 14);
        status.setText("Kamera • Siap merekam");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        recordButton = new Button(this);
        recordButton.setText("AMBIL MOTION PHOTO");
        recordButton.setTextSize(16);
        recordButton.setOnClickListener(v -> takeMotionPhoto());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(24, 8, 24, 20);
        root.addView(recordButton, bp);
        setContentView(root);

        if (hasPermissions()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                // Prefer SD for the motion buffer to reduce file size; fall back to higher quality if needed.
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.fromOrderedList(
                                Arrays.asList(Quality.HD, Quality.FHD, Quality.SD),
                                FallbackStrategy.lowerQualityThan(Quality.FHD)))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                // GCam-style capture experiment: prioritize the highest 4:3 JPEG size exposed by
                // this camera, rather than only requesting 4000x3000. The actual size
                // still depends on the sensor and the concurrent Preview + VideoCapture
                // + ImageCapture stream combination supported by the device.
                ResolutionSelector stillResolution = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build();
                ImageCapture.Builder stillBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(stillResolution);
                // Detect HDR scene support on the rear camera. Apply it dynamically through
                // Camera2CameraControl so it can be disabled whenever flash is enabled.
                hdrSceneSupported = false;
                try {
                    for (androidx.camera.core.CameraInfo info : provider.getAvailableCameraInfos()) {
                        Integer facing = Camera2CameraInfo.from(info).getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                        int[] scenes = Camera2CameraInfo.from(info).getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
                        if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK && scenes != null) {
                            for (int scene : scenes) if (scene == CaptureRequest.CONTROL_SCENE_MODE_HDR) hdrSceneSupported = true;
                        }
                    }
                } catch (Exception ignored) { hdrSceneSupported = false; }
                hdrEnabled = true;
                imageCapture = stillBuilder.build();
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture, imageCapture);
                try {
                    camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                } catch (Exception ignored) { camera2Control = null; }
                setupExposureControls();
                applyCameraCaptureOptions();
                boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
                flashButton.setEnabled(hasFlash);
                flashButton.setText(hasFlash ? "⚡ FLASH OFF" : "⚡ FLASH TIDAK ADA");
                photoFlashEnabled = false;
                hdrButton.setEnabled(true);
                hdrButton.setText(hdrEnabled ? "HDR ON" : "HDR OFF");
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
                if (gyro != null) {
                    sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
                    previewView.setScaleX(1.12f);
                    previewView.setScaleY(1.12f);
                }
                status.setText(gyro == null
                        ? "Motion Photo siap • HDR lembut software tersedia • gyro fisik tidak tersedia"
                        : "Motion Photo siap • HDR lembut software tersedia • gyro aktif");
                // Start a rolling low-resolution video buffer immediately so motion before
                // the shutter is available. The finalized clip is trimmed to a short window.
                mainHandler.postDelayed(() -> { if (recording == null && imageCapture != null) startRollingVideo(); }, 400);
            } catch (Exception e) {
                status.setText("Kamera gagal dibuka: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takeMotionPhoto() {
        if (imageCapture == null || recording == null) {
            Toast.makeText(this, "Kamera sedang disiapkan, coba lagi sebentar", Toast.LENGTH_SHORT).show();
            return;
        }
        long bufferedMs = recordingStartedNs == 0L ? 0L : (SystemClock.elapsedRealtimeNanos() - recordingStartedNs) / 1_000_000L;
        if (bufferedMs < 1600L) {
            Toast.makeText(this, "Tunggu sebentar agar video sebelum shutter tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        if (motionPhotoPending) return;
        sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists()) sessionDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        motionPhotoStamp = stamp;

        recordingStopRequestedNs = 0L;
        motionStillFile = new File(sessionDir, "motion_still_" + stamp + ".jpg");
        motionPhotoPending = true;
        focusCaptureFinished = false;
        recordButton.setEnabled(false);
        recordButton.setText("MENYIAPKAN FOTO...");
        status.setText("Menangkap momen saat shutter • autofocus menyusul pada klip gerak...");
        // Prioritize the exact shutter moment for the still. The subtle AF nudge is
        // requested after the still has been saved, while the rolling clip continues.
        captureMotionStill();
    }

    private void prepareFocusThenCapture() {
        if (!motionPhotoPending) return;
        Runnable beginFocus = () -> {
            if (!motionPhotoPending || focusCaptureFinished) return;
            if (camera == null || previewView == null || previewView.getWidth() <= 0 || previewView.getHeight() <= 0) {
                finishFocusBeforeCapture();
                return;
            }
            try {
                // Reuse the last user-selected focus point; use center if none was tapped.
                // A bounded wait prevents the shutter from hanging if the camera AF is slow.
                float x = lastFocusX >= 0f ? lastFocusX : previewView.getWidth() * 0.5f;
                float y = lastFocusY >= 0f ? lastFocusY : previewView.getHeight() * 0.5f;
                MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
                FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build();
                com.google.common.util.concurrent.ListenableFuture<androidx.camera.core.FocusMeteringResult> future =
                        camera.getCameraControl().startFocusAndMetering(action);
                // Capture as soon as AF completes, or at the timeout if it is slow.
                mainHandler.postDelayed(this::finishFocusBeforeCapture, photoFlashEnabled ? 650L : 400L);
                future.addListener(() -> mainHandler.post(this::finishFocusBeforeCapture),
                        ContextCompat.getMainExecutor(this));
            } catch (Exception ignored) {
                mainHandler.postDelayed(this::finishFocusBeforeCapture, 120L);
            }
        };
        if (photoFlashEnabled && camera != null && camera.getCameraInfo().hasFlashUnit()) {
            // Torch is temporary AF assist; ImageCapture handles the actual flash pulse.
            camera.getCameraControl().enableTorch(true).addListener(
                    () -> mainHandler.postDelayed(beginFocus, 250L), ContextCompat.getMainExecutor(this));
        } else {
            // Flash off: request a fast refinement at the user's selected focus point.
            beginFocus.run();
        }
    }

    private void finishFocusBeforeCapture() {
        if (!motionPhotoPending || focusCaptureFinished) return;
        focusCaptureFinished = true;
        if (photoFlashEnabled && camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(false).addListener(
                    () -> mainHandler.postDelayed(this::captureMotionStill, 80L), ContextCompat.getMainExecutor(this));
        } else {
            captureMotionStill();
        }
    }

    private void triggerShortFocusPulse() {
        if (camera == null || camera2Control == null) return;
        try {
            Float minFocus = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            // Fixed-focus cameras report null/zero; leave their AF pipeline untouched.
            if (minFocus == null || minFocus <= 0.05f) {
                if (previewView != null) {
                    float fx = lastFocusX >= 0f ? lastFocusX : previewView.getWidth() * 0.5f;
                    float fy = lastFocusY >= 0f ? lastFocusY : previewView.getHeight() * 0.5f;
                    MeteringPoint point = previewView.getMeteringPointFactory().createPoint(fx, fy);
                    camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point,
                            FocusMeteringAction.FLAG_AF).setAutoCancelDuration(1, TimeUnit.SECONDS).build());
                }
                return;
            }
            float nearDiopters = Math.min(3.0f, minFocus);
            CaptureRequestOptions near = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, nearDiopters)
                    .build();
            camera2Control.setCaptureRequestOptions(near);
            mainHandler.postDelayed(() -> {
                try {
                    CaptureRequestOptions restore = new CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                            .build();
                    camera2Control.setCaptureRequestOptions(restore);
                    if (camera != null && previewView != null) {
                        float fx = lastFocusX >= 0f ? lastFocusX : previewView.getWidth() * 0.5f;
                        float fy = lastFocusY >= 0f ? lastFocusY : previewView.getHeight() * 0.5f;
                        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(fx, fy);
                        camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point,
                                FocusMeteringAction.FLAG_AF).setAutoCancelDuration(1, TimeUnit.SECONDS).build());
                    }
                } catch (Exception ignored) { }
            }, 180L);
        } catch (Exception ignored) {
            // Keep capture safe on devices whose camera HAL rejects manual focus.
        }
    }

    private void captureMotionStill() {
        if (imageCapture == null || !motionPhotoPending || motionStillFile == null) return;
        // This timestamp is the capture request moment after focus-assist, not the initial tap.
        shutterTimestampNs = SystemClock.elapsedRealtimeNanos();
        recordButton.setText("MENANGKAP FOTO...");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(motionStillFile).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                // Take the still at the exact shutter moment first. Then make a very
                // short lens-focus pull in the embedded video: approximately 0.3 m
                // (about 3 diopters), then restore continuous-video autofocus. The
                // entire pulse is kept under 0.50 s so it does not consume the clip.
                triggerShortFocusPulse();
                if (status != null) status.setText(hdrEnabled && !photoFlashEnabled
                        ? "Menerapkan HDR lembut • menyeimbangkan gelap-terang..."
                        : "Foto tersimpan sementara • menyelesaikan Motion Photo...");
                cameraExecutor.execute(() -> {
                    boolean processed = false;
                    if (hdrEnabled && !photoFlashEnabled) processed = SoftHdrProcessor.apply(motionStillFile);
                    final boolean hdrProcessed = processed;
                    mainHandler.post(() -> {
                        if (hdrEnabled && !photoFlashEnabled) {
                            status.setText(hdrProcessed ? "HDR lembut diterapkan • warna sedikit diperkaya"
                                    : "HDR lembut dilewati untuk menjaga foto asli tetap aman");
                        }
                        long postShutterTargetMs = photoFlashEnabled ? 500L : 1500L;
                        long elapsedSinceShutterMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - shutterTimestampNs) / 1_000_000L);
                        long waitMs = Math.max(0L, postShutterTargetMs - elapsedSinceShutterMs);
                        mainHandler.postDelayed(() -> {
                            if (recording != null) {
                                recordingStopRequestedNs = SystemClock.elapsedRealtimeNanos();
                                recordButton.setText("MEMPROSES MOTION PHOTO...");
                                try { recording.stop(); }
                                catch (Exception e) { failMotionPhoto("Gagal menghentikan buffer video: " + e.getMessage()); }
                            } else failMotionPhoto("Buffer video tidak aktif.");
                        }, waitMs);
                    });
                });
            }
            @Override public void onError(@NonNull ImageCaptureException exception) {
                if (camera != null) camera.getCameraControl().enableTorch(false);
                failMotionPhoto("Pengambilan foto gagal: " + exception.getMessage());
            }
        });
    }

    private void startRollingVideo() {
        if (videoCapture == null || recording != null || motionPhotoPending) return;
        if (sessionDir == null) sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists()) sessionDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File rawFile = new File(sessionDir, "raw_" + stamp + ".mp4");
        gyroSamples.clear();
        gyroStartNs = SystemClock.elapsedRealtimeNanos();
        lastSavedNs = 0L;
        collectingGyro = true;
        FileOutputOptions output = new FileOutputOptions.Builder(rawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    gyroStartNs = SystemClock.elapsedRealtimeNanos();
                    recordingStartedNs = gyroStartNs;
                    recordButton.setEnabled(false);
                    recordButton.setText("MENYIAPKAN BUFFER...");
                    mainHandler.postDelayed(() -> {
                        if (!motionPhotoPending && recording != null) {
                            recordButton.setEnabled(true);
                            recordButton.setText("AMBIL MOTION PHOTO");
                            status.setText("Buffer video aktif • sudah tersedia 1,5 detik sebelum shutter");
                        }
                    }, 1650L);
                    status.setText("Buffer Motion Photo aktif • tekan shutter kapan saja");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    recording = null;
                    collectingGyro = false;
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    if (fin.hasError() || !rawFile.exists() || rawFile.length() == 0) {
                        if (motionPhotoPending) failMotionPhoto("Buffer video gagal (" + fin.getError() + ")");
                        else status.setText("Buffer video gagal; mencoba mulai ulang...");
                        if (!motionPhotoPending) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
                        return;
                    }
                    try {
                        // The gyro clock is re-anchored to the last ~3 seconds, matching the
                        // desired 1.5 s before/after shutter window as closely as possible.
                        gyroStartNs = Math.max(0L, SystemClock.elapsedRealtimeNanos() - 3_000_000_000L);
                        File trimmed = new File(sessionDir, rawFile.getName().replace("raw_", "motion_raw_"));
                        trimToLastThreeSeconds(rawFile, trimmed);
                        File csv = new File(sessionDir, trimmed.getName().replace(".mp4", "_gyro.csv"));
                        writeGyroCsv(csv);
                        pendingRawFile = trimmed;
                        pendingCsvFile = csv;
                        if (motionPhotoPending) {
                            status.setText("Video pendek siap • menstabilkan gyro otomatis...");
                            stabilize(trimmed, csv);
                        } else {
                            status.setText("Buffer selesai; mulai ulang...");
                            mainHandler.postDelayed(MainActivity.this::startRollingVideo, 500);
                        }
                    } catch (Exception e) {
                        if (motionPhotoPending) failMotionPhoto("Persiapan video gagal: " + e.getMessage());
                        else { status.setText("Buffer dipulihkan; mencoba ulang..."); mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700); }
                    }
                }
            });
        } catch (Exception e) {
            recording = null; collectingGyro = false;
            if (motionPhotoPending) failMotionPhoto("Tidak dapat memulai buffer: " + e.getMessage());
            else status.setText("Buffer kamera gagal dimulai: " + e.getMessage());
        }
    }

    private void failMotionPhoto(String message) {
        // Never discard the high-resolution still if the video pipeline fails.
        if (motionStillFile != null && motionStillFile.exists()) {
            try { publishPhoto(motionStillFile); message += " • foto utama tetap disimpan ke galeri"; }
            catch (Exception ignored) { }
        }
        motionPhotoPending = false;
        motionStillFile = null;
        recordButton.setEnabled(true);
        recordButton.setText("AMBIL MOTION PHOTO");
        status.setText(message + " • video dapat diuji ulang");
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
    }

    private void trimToLastThreeSeconds(File input, File output) throws IOException {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            long durationUs = 0L;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                if (f.containsKey(android.media.MediaFormat.KEY_DURATION)) durationUs = Math.max(durationUs, f.getLong(android.media.MediaFormat.KEY_DURATION));
            }
            long startUs = Math.max(0L, durationUs - 3_000_000L);
            muxer = new android.media.MediaMuxer(output.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // MediaMuxer does not copy source orientation metadata automatically. Without
            // this hint a portrait clip may be displayed sideways after trimming.
            int sourceRotation = 0;
            android.media.MediaMetadataRetriever rotationRetriever = new android.media.MediaMetadataRetriever();
            try {
                rotationRetriever.setDataSource(input.getAbsolutePath());
                String rotationValue = rotationRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotationValue != null) sourceRotation = Integer.parseInt(rotationValue);
            } catch (Exception ignored) { }
            finally { try { rotationRetriever.release(); } catch (Exception ignored) { } }
            sourceRotation = ((sourceRotation % 360) + 360) % 360;
            if (sourceRotation == 90 || sourceRotation == 180 || sourceRotation == 270) muxer.setOrientationHint(sourceRotation);
            int[] map = new int[extractor.getTrackCount()];
            java.util.Arrays.fill(map, -1);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) map[i] = muxer.addTrack(f);
            }
            muxer.start();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            for (int i = 0; i < map.length; i++) {
                if (map[i] < 0) continue;
                extractor.selectTrack(i);
                extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                while (true) {
                    info.offset = 0;
                    info.size = extractor.readSampleData(buffer, 0);
                    if (info.size < 0) break;
                    long pts = extractor.getSampleTime();
                    if (pts < 0) break;
                    if (pts >= startUs) {
                        info.presentationTimeUs = Math.max(0L, pts - startUs);
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(map[i], buffer, info);
                    }
                    if (!extractor.advance()) break;
                }
                extractor.unselectTrack(i);
            }
            muxer.stop();
        } catch (Exception e) {
            throw new IOException("Gagal memotong buffer video", e);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) {}
        }
        if (!output.exists() || output.length() < 1024) throw new IOException("Klip Motion Photo terlalu kecil");
    }

    private void toggleRecording() {
        // A second tap after recording has finished starts processing the saved video.
        if (recording == null && pendingRawFile != null && pendingCsvFile != null) {
            File raw = pendingRawFile;
            File csv = pendingCsvFile;
            recordButton.setEnabled(false);
            recordButton.setText("MEMPROSES...");
            stabilize(raw, csv);
            return;
        }

        if (recording != null) {
            recordButton.setEnabled(false);
            recordButton.setText("MENYIMPAN...");
            status.setText("Menghentikan rekaman dan menyimpan video asli...");
            try {
                recording.stop();
            } catch (Exception e) {
                recordButton.setEnabled(true);
                recordButton.setText("BERHENTI & STABILKAN");
                status.setText("Gagal menghentikan rekaman: " + e.getMessage());
            }
            return;
        }

        if (videoCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }

        sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            status.setText("Tidak bisa membuat folder penyimpanan.");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File rawFile = new File(sessionDir, "raw_" + stamp + ".mp4");
        gyroSamples.clear();
        // Set a provisional start immediately. The absolute sensor timestamps are kept,
        // and VideoRecordEvent.Start refines this to the actual CameraX recording start.
        gyroStartNs = SystemClock.elapsedRealtimeNanos();
        lastSavedNs = 0L;
        collectingGyro = true;

        FileOutputOptions output = new FileOutputOptions.Builder(rawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }

        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    gyroStartNs = SystemClock.elapsedRealtimeNanos();
                    recordButton.setEnabled(true);
                    recordButton.setText("BERHENTI & STABILKAN");
                    status.setText("Merekam • pratinjau gyro aktif; hasil final diproses setelah rekam.");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    recording = null;
                    collectingGyro = false;
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;

                    if (fin.hasError() || !rawFile.exists() || rawFile.length() == 0) {
                        status.setText("Rekaman gagal disimpan (kode " + fin.getError() + ").");
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                        gyroStartNs = 0L;
                        return;
                    }

                    try {
                        File csv = new File(sessionDir, rawFile.getName().replace(".mp4", "_gyro.csv"));
                        writeGyroCsv(csv);
                        pendingRawFile = rawFile;
                        pendingCsvFile = csv;
                        status.setText("Video asli tersimpan. Tekan STABILKAN VIDEO untuk memprosesnya.");
                        recordButton.setEnabled(true);
                        recordButton.setText("STABILKAN VIDEO");
                        // Keep gyroStartNs until the stabilization button is pressed; it is
                        // needed to align absolute sensor timestamps to this video.
                    } catch (Exception e) {
                        status.setText("Video asli tersimpan, tetapi data gyro gagal: " + e.getMessage());
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                    }
                }
            });
        } catch (Exception e) {
            recording = null;
            collectingGyro = false;
            recordButton.setEnabled(true);
            recordButton.setText("MULAI REKAM");
            status.setText("Gagal memulai rekaman: " + e.getMessage());
        }
    }

    private void togglePhotoFlash() {
        if (camera == null || imageCapture == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, "Flash foto tidak tersedia pada kamera ini", Toast.LENGTH_SHORT).show();
            return;
        }
        photoFlashEnabled = !photoFlashEnabled;
        imageCapture.setFlashMode(photoFlashEnabled ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        if (hdrButton != null) hdrButton.setEnabled(!photoFlashEnabled);
        applyCameraCaptureOptions();
        if (flashButton != null) flashButton.setText(photoFlashEnabled ? "⚡ FLASH ON" : "⚡ FLASH OFF");
        if (hdrButton != null) hdrButton.setText(hdrEnabled ? "HDR ON" : "HDR OFF");
        if (status != null) status.setText(photoFlashEnabled
                ? "Flash assist menunggu fokus • kilatan foto saat exposure • HDR nonaktif sementara"
                : (hdrEnabled ? "Flash mati • HDR lembut software aktif untuk foto berikutnya" : "Flash mati • HDR software nonaktif"));
    }

    private void toggleHdr() {
        if (photoFlashEnabled) {
            Toast.makeText(this, "Matikan flash sebelum mengubah HDR", Toast.LENGTH_SHORT).show();
            return;
        }
        hdrEnabled = !hdrEnabled;
        // Hardware HDR is optional; the software tone mapper works even when the camera HAL ignores HDR.
        applyCameraCaptureOptions();
        if (hdrButton != null) hdrButton.setText(hdrEnabled ? "HDR ON" : "HDR OFF");
        if (status != null) status.setText(hdrEnabled
                ? "HDR lembut ON • gelap-terang diseimbangkan, warna sedikit diperkaya"
                : "HDR OFF • foto asli tanpa tone mapping");
    }

    /** Requests the camera HAL's HDR scene mode when supported. This is not
     * computational multi-frame HDR and the device may ignore the request. */
    private void applyCameraCaptureOptions() {
        if (camera2Control == null) return;
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
            boolean enableHdr = hdrSceneSupported && hdrEnabled && !photoFlashEnabled;
            if (enableHdr) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR);
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED);
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, exposureLocked);
            camera2Control.setCaptureRequestOptions(builder.build());
        } catch (Exception ignored) { }
    }

    private void setupExposureControls() {
        if (camera == null || exposureSeekBar == null) return;
        try {
            android.util.Range<Integer> range = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
            minExposureIndex = range.getLower();
            maxExposureIndex = range.getUpper();
            currentExposureIndex = camera.getCameraInfo().getExposureState().getExposureCompensationIndex();
            boolean supported = maxExposureIndex > minExposureIndex;
            exposureSeekBar.setEnabled(supported);
            exposureLockButton.setEnabled(supported);
            if (!supported) {
                exposureLabel.setText("EV tidak didukung");
                return;
            }
            updateExposureUi(currentExposureIndex);
        } catch (Exception e) {
            exposureSeekBar.setEnabled(false);
            exposureLockButton.setEnabled(false);
            exposureLabel.setText("EV tidak tersedia");
        }
    }

    private void applyExposureIndex(int index) {
        if (camera == null) return;
        int safe = Math.max(minExposureIndex, Math.min(maxExposureIndex, index));
        currentExposureIndex = safe;
        try {
            camera.getCameraControl().setExposureCompensationIndex(safe).addListener(() -> runOnUiThread(() -> {
                if (exposureLabel != null) updateExposureUi(safe);
            }), ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            if (status != null) status.setText("Pengaturan exposure tidak didukung kamera ini");
        }
    }

    private void updateExposureUi(int index) {
        if (camera == null || exposureSeekBar == null || exposureLabel == null) return;
        double step = camera.getCameraInfo().getExposureState().getExposureCompensationStep().doubleValue();
        double ev = index * step;
        exposureLabel.setText(String.format(Locale.US, "EV %+.1f", ev));
        int range = maxExposureIndex - minExposureIndex;
        int progress = range == 0 ? 50 : Math.round((index - minExposureIndex) * 100.0f / range);
        updatingExposureUi = true;
        exposureSeekBar.setProgress(Math.max(0, Math.min(100, progress)));
        updatingExposureUi = false;
    }

    private void toggleExposureLock() {
        if (camera == null || camera2Control == null) {
            Toast.makeText(this, "AE Lock tidak didukung kamera ini", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean requested = !exposureLocked;
        try {
            exposureLocked = requested;
            applyCameraCaptureOptions();
            runOnUiThread(() -> {
                if (exposureLockButton != null) {
                    exposureLockButton.setText(exposureLocked ? "AE LOCK ON" : "AE LOCK");
                    exposureLockButton.setSelected(exposureLocked);
                }
                if (exposureSeekBar != null) exposureSeekBar.setEnabled(!exposureLocked && maxExposureIndex > minExposureIndex);
                if (status != null) status.setText(exposureLocked
                        ? "Exposure terkunci • ketuk AE LOCK ON untuk membuka"
                        : "Exposure otomatis aktif kembali");
            });
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mengunci exposure pada kamera ini", Toast.LENGTH_SHORT).show();
        }
    }

    private void focusAt(float x, float y) {
        if (camera == null || previewView == null) return;
        lastFocusX = x;
        lastFocusY = y;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action).addListener(() -> {
                runOnUiThread(() -> {
                    if (status != null && recording == null)
                        status.setText("Fokus disentuh • FHD jika didukung • zoom " + selectedZoom + "×");
                });
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            Toast.makeText(this, "Fokus sentuh tidak didukung di titik ini", Toast.LENGTH_SHORT).show();
        }
    }

    private void setZoom(float requested) {
        if (camera == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            androidx.camera.core.ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            float max = state == null ? requested : state.getMaxZoomRatio();
            float target = Math.max(1.0f, Math.min(requested, max));
            selectedZoom = target;
            camera.getCameraControl().setZoomRatio(target);
            if (status != null && recording == null) {
                String suffix = target < requested ? " (batas perangkat)" : "";
                status.setText("Zoom " + target + "×" + suffix + " • ketuk layar untuk fokus");
            }
            if (target < requested) Toast.makeText(this,
                    "Perangkat membatasi zoom sampai " + target + "×", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Zoom gagal: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        long now = event.timestamp;

        // Live preview stabilization: counter the integrated phone rotation instead
        // of following it. The correction is held near center until it reaches its
        // crop-safe limit; beyond that, only a slow recentering anchor is allowed.
        if (previewView != null && previewView.getWidth() > 0) {
            if (liveLastTimestampNs != 0L) {
                double dt = Math.max(0.0, Math.min(0.05, (now - liveLastTimestampNs) / 1_000_000_000.0));
                double rx = deadBand(event.values[0], 0.008);
                double ry = deadBand(event.values[1], 0.008);
                double rz = deadBand(event.values[2], 0.008);
                double rateAlpha = dt / (0.045 + dt);
                liveRateX += rateAlpha * (rx - liveRateX);
                liveRateY += rateAlpha * (ry - liveRateY);
                liveRateZ += rateAlpha * (rz - liveRateZ);
                liveAngleX += liveRateX * dt;
                liveAngleY += liveRateY * dt;
                liveAngleZ += liveRateZ * dt;
                if (!liveAnglesInitialized) {
                    liveSmoothX = liveAngleX; liveSmoothY = liveAngleY; liveSmoothZ = liveAngleZ;
                    liveAnchorX = liveAngleX; liveAnchorY = liveAngleY; liveAnchorZ = liveAngleZ;
                    liveAnglesInitialized = true;
                }

                // Very slow drift follow only after the correction has reached its limit.
                final double maxTilt = 0.22; // radians, about 12.6 degrees
                final double maxRoll = 0.12;
                double dx = liveAngleX - liveAnchorX;
                double dy = liveAngleY - liveAnchorY;
                double dz = liveAngleZ - liveAnchorZ;
                double followAlpha = Math.min(1.0, dt * 0.32);
                if (Math.abs(dx) > maxTilt) liveAnchorX += (dx - Math.copySign(maxTilt, dx)) * followAlpha;
                if (Math.abs(dy) > maxTilt) liveAnchorY += (dy - Math.copySign(maxTilt, dy)) * followAlpha;
                if (Math.abs(dz) > maxRoll) liveAnchorZ += (dz - Math.copySign(maxRoll, dz)) * followAlpha;

                dx = clamp(liveAngleX - liveAnchorX, -maxTilt, maxTilt);
                dy = clamp(liveAngleY - liveAnchorY, -maxTilt, maxTilt);
                dz = clamp(liveAngleZ - liveAnchorZ, -maxRoll, maxRoll);
                previewView.setPivotX(previewView.getWidth() * 0.5f);
                previewView.setPivotY(previewView.getHeight() * 0.5f);
                // Invert the measured motion so the displayed frame resists phone movement.
                previewView.setRotation((float) (-Math.toDegrees(dz)));
                previewView.setTranslationX((float) (dy * previewView.getWidth() * 0.48));
                previewView.setTranslationY((float) (-dx * previewView.getHeight() * 0.48));
            }
            liveLastTimestampNs = now;
        }

        // Store gyro samples only while CameraX is recording. Limit to ~100 Hz to
        // keep files and memory modest without losing meaningful hand motion.
        if (!collectingGyro || gyroStartNs == 0L) return;
        if (lastSavedNs != 0L && now - lastSavedNs < 10_000_000L) return;
        lastSavedNs = now;
        synchronized (gyroSamples) {
            gyroSamples.add(new GyroSample(now, event.values[0], event.values[1], event.values[2]));
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void writeGyroCsv(File csv) throws IOException {
        try (FileWriter w = new FileWriter(csv)) {
            w.write("t,gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s\n");
            synchronized (gyroSamples) {
                for (GyroSample s : gyroSamples) {
                    double t = s.secondsFrom(gyroStartNs);
                    if (t >= 0) w.write(String.format(Locale.US, "%.6f,%.7f,%.7f,%.7f\n", t, s.x, s.y, s.z));
                }
            }
        }
    }

    // Gyro-based stabilization: integrate gyro XYZ, smooth the camera path, and
    // sample a 240-point correction curve. Native Vulkan applies the curve per decoded
    // frame and MediaCodec encodes the stabilized result; no FFmpeg path is used.
    private void stabilize(File input, File csv) {
        status.setText("Menyiapkan stabilisasi Vulkan 240 titik... video asli tetap aman.");
        recordButton.setEnabled(false);
        new Thread(() -> {
            File output = null;
            File silent = null;
            try {
                final long startNs = gyroStartNs;
                List<GyroSample> all;
                synchronized (gyroSamples) { all = new ArrayList<>(gyroSamples); }
                List<GyroSample> samples = new ArrayList<>();
                for (GyroSample sample : all) {
                    double tSec = sample.secondsFrom(startNs);
                    if (tSec >= 0) samples.add(new GyroSample((long)(tSec * 1_000_000_000L), sample.x, sample.y, sample.z));
                }
                if (samples.size() < 8) throw new IOException("Data gyro terlalu sedikit; video asli tetap aman.");

                int n = samples.size();
                double[] t = new double[n], ax = new double[n], ay = new double[n], az = new double[n];
                for (int i = 0; i < n; i++) t[i] = samples.get(i).timestampNs / 1_000_000_000.0;
                double[] bias = estimateInitialGyroBias(samples, t, 0.25);
                double fx = 0, fy = 0, fz = 0;
                double prevFx = 0, prevFy = 0, prevFz = 0;
                final double deadBandThreshold = 0.008;
                for (int i = 1; i < n; i++) {
                    double dt = Math.max(0, Math.min(0.05, t[i] - t[i - 1]));
                    GyroSample cur = samples.get(i);
                    double rx = deadBand(cur.x - bias[0], deadBandThreshold);
                    double ry = deadBand(cur.y - bias[1], deadBandThreshold);
                    double rz = deadBand(cur.z - bias[2], deadBandThreshold);
                    double alpha = dt <= 0 ? 0 : dt / (0.050 + dt);
                    prevFx = fx; prevFy = fy; prevFz = fz;
                    fx += alpha * (rx - fx);
                    fy += alpha * (ry - fy);
                    fz += alpha * (rz - fz);
                    ax[i] = ax[i - 1] + 0.5 * (fx + prevFx) * dt;
                    ay[i] = ay[i - 1] + 0.5 * (fy + prevFy) * dt;
                    az[i] = az[i - 1] + 0.5 * (fz + prevFz) * dt;
                }

                // FRAME-LOCK stabilization: use a wider centered reference path so the
                // correction also counters slower hand tilt, not just tiny high-frequency
                // shakes. This changes only the gyro stabilization path; capture, HDR,
                // Motion Photo muxing, output aspect ratios, and UI remain untouched.
                // Centered offline smoothing avoids adding preview-style lag to the clip.
                double[] sx = smoothTrajectory(ax, t, 2.40), sy = smoothTrajectory(ay, t, 2.40), sz = smoothTrajectory(az, t, 2.40);
                double[] cx = new double[n], cy = new double[n], cz = new double[n];
                final double correctionGain = 0.96;
                for (int i = 0; i < n; i++) {
                    // A little extra headroom lets the stabilizer counter a more obvious
                    // tilt while keeping correction bounded to avoid extreme perspective.
                    cx[i] = clamp((ax[i] - sx[i]) * correctionGain, -0.34, 0.34);
                    cy[i] = clamp((ay[i] - sy[i]) * correctionGain, -0.34, 0.34);
                    cz[i] = clamp((az[i] - sz[i]) * correctionGain, -0.18, 0.18);
                }

                // Low-pass the correction curve itself to reduce residual frame trembling
                // and abrupt reversals when the phone changes direction. This is offline
                // centered smoothing and does not change the recorded clip's timing.
                cx = smoothTrajectory(cx, t, 0.38);
                cy = smoothTrajectory(cy, t, 0.38);
                cz = smoothTrajectory(cz, t, 0.38);

                final int points = 240;
                float[] pointTimes = new float[points];
                float[] pointX = new float[points], pointY = new float[points], pointZ = new float[points];
                double duration = Math.max(0.001, t[n - 1]);
                for (int k = 0; k < points; k++) {
                    double target = duration * k / (points - 1.0);
                    pointTimes[k] = (float) target;
                    pointX[k] = (float) interpolateSeries(t, cx, target);
                    pointY[k] = (float) interpolateSeries(t, cy, target);
                    pointZ[k] = (float) interpolateSeries(t, cz, target);
                }

                output = new File(sessionDir, input.getName().replace("raw_", "stabilized_").replace(".mp4", "_GPU.mp4"));
                silent = new File(sessionDir, input.getName().replace("raw_", "gpu_silent_").replace(".mp4", ".mp4"));
                if (output.exists()) output.delete();
                if (silent.exists()) silent.delete();
                final File outputFinal = output;
                final File silentFinal = silent;
                runOnUiThread(() -> status.setText("Stabilisasi Vulkan + MediaCodec 240 titik berjalan..."));
                GyroVulkanStabilizer.stabilize(this, input, silentFinal, outputFinal,
                        pointTimes, pointX, pointY, pointZ, false,
                        message -> runOnUiThread(() -> status.setText(message)));

                runOnUiThread(() -> {
                    try {
                        if (motionPhotoPending && motionStillFile != null && motionStillFile.exists()) {
                            String motionStamp = motionPhotoStamp != null ? motionPhotoStamp : new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                            File motionJpeg = new File(sessionDir, "GyroLock_" + motionStamp + "MP.jpg");
                            long markerUs = calculateMotionMarkerUs(outputFinal);
                            MotionPhotoWriter.write(motionStillFile, outputFinal, motionJpeg, markerUs);
                            publishPhoto(motionJpeg);
                            // Single-file Motion Photo: the MP4 is embedded in the JPEG by MotionPhotoWriter.
                            // Do not publish a separate MP4 companion.
                            motionPhotoPending = false;
                            motionStillFile = null;
                            motionPhotoStamp = null;
                            status.setText("Motion Photo tersimpan di DCIM/Camera");
                            Toast.makeText(this, "Motion Photo tersimpan di Kamera", Toast.LENGTH_LONG).show();
                        } else {
                            publishVideo(outputFinal);
                            status.setText("Stabilisasi selesai! Video tersimpan di Movies/Camera.");
                        }
                        pendingRawFile = null;
                        pendingCsvFile = null;
                        recordButton.setText("AMBIL MOTION PHOTO");
                    } catch (Exception publishError) {
                        status.setText("Stabilisasi selesai, tetapi gagal menyalin hasil ke Galeri: " + publishError.getMessage());
                        recordButton.setText("COBA SIMPAN HASIL LAGI");
                    }
                    recordButton.setEnabled(true);
                    if (!motionPhotoPending && recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 600);
                });
            } catch (Throwable e) {
                final String message = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                runOnUiThread(() -> {
                    if (motionPhotoPending && motionStillFile != null && motionStillFile.exists() && input.exists()) {
                        try {
                            String motionStamp = motionPhotoStamp != null ? motionPhotoStamp : new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                            File fallbackMotion = new File(sessionDir, "GyroLock_" + motionStamp + "MP.jpg");
                            long markerUs = calculateMotionMarkerUs(input);
                            MotionPhotoWriter.write(motionStillFile, input, fallbackMotion, markerUs);
                            publishPhoto(fallbackMotion);
                            // Fallback also saves one combined Motion Photo only; no separate MP4 is published.
                            motionPhotoPending = false;
                            motionStillFile = null;
                            motionPhotoStamp = null;
                            status.setText("Motion Photo tersimpan; stabilisasi gyro tidak tersedia, video asli dipakai.");
                            Toast.makeText(this, "Motion Photo tersimpan tanpa stabilisasi", Toast.LENGTH_LONG).show();
                            recordButton.setEnabled(true);
                            recordButton.setText("AMBIL MOTION PHOTO");
                            if (recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
                            return;
                        } catch (Exception saveError) {
                            failMotionPhoto("Stabilisasi gagal dan video gerak tidak dapat digabung: " + message);
                            return;
                        }
                    }
                    status.setText("Stabilisasi GPU gagal; video asli tetap aman. " + message);
                    recordButton.setEnabled(true);
                    recordButton.setText("AMBIL MOTION PHOTO");
                    if (recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
                });
            }
        }, "GyroLock-GPU-Stabilizer").start();
    }

    /** Align the XMP still-frame marker to the shutter request inside the trimmed clip. */
    private long calculateMotionMarkerUs(File clip) {
        long clipDurationUs = 3_000_000L;
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(clip.getAbsolutePath());
            String durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs != null) clipDurationUs = Math.max(0L, Long.parseLong(durationMs) * 1000L);
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        long afterShutterUs = recordingStopRequestedNs > shutterTimestampNs && shutterTimestampNs > 0L
                ? (recordingStopRequestedNs - shutterTimestampNs) / 1000L
                : (photoFlashEnabled ? 500_000L : 1_500_000L);
        return Math.max(0L, Math.min(clipDurationUs, clipDurationUs - afterShutterUs));
    }

    private double interpolateSeries(double[] times, double[] values, double target) {
        if (target <= times[0]) return values[0];
        int last = times.length - 1;
        if (target >= times[last]) return values[last];
        int lo = 0, hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= target) lo = mid; else hi = mid;
        }
        double span = times[hi] - times[lo];
        if (span <= 0) return values[lo];
        double f = (target - times[lo]) / span;
        return values[lo] + (values[hi] - values[lo]) * f;
    }

    private double deadBand(double value, double threshold) {
        // Soft dead-band avoids turning sensor noise around zero into visible jitter.
        double magnitude = Math.abs(value) - threshold;
        return magnitude <= 0.0 ? 0.0 : Math.copySign(magnitude, value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double[] estimateInitialGyroBias(List<GyroSample> samples, double[] times, double windowSeconds) {
        double[] sum = new double[3];
        int count = 0;
        for (int i = 0; i < samples.size() && times[i] <= windowSeconds; i++) {
            sum[0] += samples.get(i).x;
            sum[1] += samples.get(i).y;
            sum[2] += samples.get(i).z;
            count++;
        }
        if (count == 0) return new double[]{0, 0, 0};
        return new double[]{sum[0] / count, sum[1] / count, sum[2] / count};
    }

    private double[] smoothTrajectory(double[] values, double[] times, double windowSeconds) {
        // Sliding-window mean using a prefix sum: O(n), not O(n * windowSamples).
        // The wider camera-path window suppresses walking-footstep oscillation;
        // the short correction window removes abrupt reversal without over-blurring motion.
        int n = values.length;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + values[i];
        double[] result = new double[n];
        int left = 0, right = 0;
        double half = Math.max(0.0, windowSeconds * 0.5);
        for (int i = 0; i < n; i++) {
            while (left < i && times[i] - times[left] > half) left++;
            if (right < i) right = i;
            while (right + 1 < n && times[right + 1] - times[i] <= half) right++;
            int count = right - left + 1;
            result[i] = count > 0 ? (prefix[right + 1] - prefix[left]) / count : values[i];
        }
        return result;
    }

    private List<Integer> pickIndices(int n, int maxPoints) {
        List<Integer> picked = new ArrayList<>();
        int count = Math.min(maxPoints, n);
        for (int k = 0; k < count; k++) {
            int index = count == 1 ? 0 : (int)Math.round(k * (n - 1.0) / (count - 1.0));
            if (picked.isEmpty() || picked.get(picked.size() - 1) != index) picked.add(index);
        }
        return picked;
    }

    private String buildExpression(double[] times, double[] values, List<Integer> idx) {
        if (idx == null || idx.isEmpty()) return "0";
        int last = idx.get(idx.size() - 1);
        String expression = String.format(Locale.US, "%.7f", values[last]);
        for (int k = idx.size() - 2; k >= 0; k--) {
            int ia = idx.get(k), ib = idx.get(k + 1);
            double ta = times[ia], tb = times[ib], va = values[ia], vb = values[ib];
            double dt = tb - ta;
            if (dt <= 0.0001) continue;
            String interpolation = String.format(Locale.US, "(%.7f+(%.7f)*(t-%.6f)/%.7f)", va, vb - va, ta, dt);
            expression = String.format(Locale.US, "if(lt(t\\,%.6f)\\,%s\\,%s)", tb, interpolation, expression);
        }
        return expression;
    }

    /**
     * Publishes the processed MP4 into the public Movies/GyroLock folder so it
     * appears in common file managers and gallery apps. The working/original
     * files stay in the app's private external-files directory.
     */
    private Uri publishPhoto(File source) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, source.lastModified());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("Galeri menolak file Motion Photo");
        boolean success = false;
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Tidak dapat membuka output galeri");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
            success = true;
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (success) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, ready, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            } else if (!success) {
                resolver.delete(uri, null, null);
            }
        }
        return uri;
    }

    private Uri publishVideo(File source) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Camera");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("Gagal membuat file video di Movies/Camera");
            boolean success = false;
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Tidak bisa menulis file video");
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
                success = true;
            } finally {
                if (success) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
            return uri;
        } else {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File folder = new File(movies, "Camera");
            if (!folder.exists() && !folder.mkdirs()) throw new IOException("Gagal membuat folder Movies/GyroLock");
            File dest = new File(folder, source.getName());
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            }
            return Uri.fromFile(dest);
        }
    }

    private String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ && hasPermissions()) startCamera();
        else if (requestCode == REQ) status.setText("Izin kamera dan mikrofon diperlukan.");
    }

    @Override protected void onDestroy() {
        try { if (recording != null) recording.stop(); } catch (Exception ignored) { }
        super.onDestroy();
        collectingGyro = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
