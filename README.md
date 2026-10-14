# GyroLock Motion Photo — Frame Lock, shutter focus kick, soft HDR, 3:4 / 9:16 (3.0.9)


## Perubahan 3.0.9 — gyro frame-lock
- Hanya jalur pemrosesan stabilisasi gyro yang diubah: referensi lintasan diperhalus dengan jendela lebih lebar agar kompensasi juga meredam kemiringan tangan yang lebih lambat, bukan hanya getaran cepat.
- Gain koreksi dan batas sudut dinaikkan sedikit, lalu kurva koreksi diperhalus untuk mengurangi gemetar/gerakan balik yang terlihat.
- Tidak mengubah jalur capture, HDR, autofocus, ukuran foto, rasio video 3:4 / 9:16, metadata XMP, atau penulisan Motion Photo.
- Frame-lock tetap memiliki batas fisik: gyro dapat drift, sudut besar memerlukan crop, dan hasil harus diuji pada perangkat target. Ini bukan gimbal mekanis dan tidak menjamin frame benar-benar diam untuk semua gerakan.

## Perubahan 3.0.7
- Foto diam diambil segera setelah tombol shutter ditekan, tanpa menunggu autofocus awal. Setelah foto tersimpan, aplikasi meminta autofocus singkat pada klip gerak agar muncul perubahan fokus halus seperti “kedut” alami. Efeknya bergantung pada respons autofocus perangkat.
- HDR software lembut tetap dipakai pada JPEG: mengangkat bayangan secara ringan, meredam highlight, dan menambah saturasi tipis. Ini tone mapping satu foto, bukan HDR multi-exposure sungguhan; hasil bergantung pada kemampuan kamera dan memori perangkat.
- Menambahkan pilihan rasio video Motion Photo: 3:4 (720×960, default dan zoom stabilisasi lama dipertahankan) atau 9:16 (720×1280, crop/zoom stabilisasi dibuat sangat ringan). Rasio foto diam tetap mengikuti resolusi maksimum 4:3 yang disediakan kamera.
- Metadata XMP dan penggabungan JPEG + MP4 tetap menggunakan MotionPhotoWriter yang sudah ada; jalur stabilisasi gyro/Vulkan dipertahankan selain penyesuaian zoom khusus 9:16.

## Catatan pengujian
Perubahan ini dibuat pada source lengkap, tetapi perlu dibangun dan diuji pada perangkat Android target untuk memastikan dukungan codec, hasil HDR, autofocus, dan kompatibilitas Motion Photo di Galeri/WhatsApp/TikTok.

\n\n---\n\n# GyroLock Motion Photo — consistent 3:4 motion frame 3.0.5

This build targets Android 11 (API 30) and asks CameraX for the highest available 4:3 still-image resolution. The actual output size depends on camera hardware and the concurrently bound Preview + VideoCapture + ImageCapture stream combination; verify the saved JPEG dimensions on-device. This experiment aims for 12 MP where the camera exposes it.

## Motion Photo changes
- Keeps the result as one `.jpg` file with the JPEG still, embedded MP4 motion segment, and XMP metadata. It does not publish a separate MP4 companion.
- Fixes invalid XMP caused by writing duplicate `MotionPhoto` and `MotionPhotoVersion` attributes through two prefixes bound to the same namespace URI.
- Retains the modern `Camera:MotionPhoto` and `GContainer:Directory` fields plus legacy `GCamera:MicroVideo*` fields for reader compatibility.
- Increments versionCode to 36 and versionName to 3.0.0.

## Important compatibility note
This is a standards-oriented compatibility fix based on the available Infinix/Transsion APK and the GyroLock source. It is not a complete reproduction of proprietary Infinix camera processing. TikTok and WhatsApp may still treat a Motion Photo JPEG as a still image because app import support varies by version and media picker. Build and test the generated JPEG on the target device; inspect its actual dimensions and whether its embedded motion is recognized.

## Build
Open GitHub Actions, run the included workflow, and download the debug APK artifact. The workflow compiles the Vulkan shaders and builds the Android project.


## 3.0.1 — GCam-style metadata experiment

This test build keeps the photo and video in one `*MP.jpg` file. It writes both modern Android Motion Photo Container metadata and legacy `GCamera:MicroVideo*` fields, while omitting a synthetic `Item:Length=0` on the primary JPEG item. The filename ends in `MP.jpg` as required by the Android Motion Photo 1.0 filename pattern. This is an interoperability experiment, not a guarantee that TikTok will accept the file; test both TikTok Live Photo Comments and the post composer. No standalone MP4 is published.


## 3.0.2 — GCam-style capture experiment

The still-image selector now prioritizes the highest available resolution for a 4:3 aspect ratio instead of requesting only 4000x3000. Gyro stabilization classes and the post-recording stabilization path are retained. Motion Photo remains a single JPEG with embedded MP4 bytes and XMP metadata; no standalone MP4 is published by the Motion Photo writer. This is an experiment: higher still resolution may not be available while the rolling video use case is active, and TikTok acceptance is not guaranteed without device testing.


## 3.0.3 — shutter, focus, flash, gallery and HDR test

- Adds `TCamera:LivePhoto=1` to the Motion Photo XMP and writes modern `Container:Item` directory entries as attributes. Legacy `MicroVideo` fields remain for older gallery readers.
- Publishes the completed JPEG to `DCIM/Camera` through MediaStore with pending-state handling so gallery indexing starts after the copy completes.
- Requests center AF/AE metering before taking the still. When flash is enabled, the LED is used briefly as focus assist, then CameraX is asked to fire its still-photo flash at exposure time. Actual flash behavior depends on the OPPO camera HAL and must be tested on-device.
- Adds an HDR toggle only when Camera2 reports the legacy HDR scene mode. This is a request to the camera HAL, not computational multi-frame HDR, and may not activate the proprietary HDR processing in the stock OPPO camera.
- Uses the recorded shutter request and clip stop time to calculate the XMP still-frame presentation marker rather than always writing a fixed 1.5/2.5 second value.
- Prefers an HD rolling-video quality and transcodes the stabilized embedded motion segment to portrait 720x1000 (or landscape 1000x720) with center crop to avoid stretching. The source still image resolution remains device-dependent.

This remains an experimental build. Gallery motion-photo badges, flash burst timing, HDR behavior, focus, and TikTok Live Photo recognition must be verified on the target phone.


## 3.0.4 — fast shutter-time focus refinement

- Remembers the last point tapped by the user for touch-to-focus and AE metering.
- With flash off, shutter press requests a quick AF/AE refinement at that point (or center if none was tapped); capture proceeds when AF finishes or after a short 400 ms timeout. This is not true manual lens focus: the phone camera driver controls AF behavior.
- With flash on, the torch is used briefly as a focus-assist lamp, the same focus point is metered with a bounded wait, then the torch is turned off and CameraX is asked to fire its still-photo flash for exposure.
- Guards against duplicate capture callbacks when AF completion and timeout happen close together.

The camera HAL may ignore or delay AF/flash requests. This source ZIP was updated, but the resulting APK still needs to be built by GitHub Actions and tested on the OPPO A54; TikTok/OPPO Gallery recognition is not guaranteed.


## 3.0.5 — consistent Motion Photo frame ratio

- Fixes the embedded motion video output from 720x1000 (18:25) to 720x960 (3:4) for portrait clips, matching the intended 3:4 still-photo frame.
- Landscape clips use 960x720 (4:3), the reciprocal ratio.
- This changes only the encoded motion-video frame dimensions; it does not change the camera preview, capture zoom, still-photo dimensions, or shutter/focus flow.
- Expected result: the image frame should no longer appear to shrink during the motion segment and then grow again at the still-photo moment. Verify on OPPO Gallery and TikTok on-device.


## GyroLock 3.0.6 — Soft HDR
- Adds a gentle software tone-mapping pass after still capture: subtle shadow lift, highlight roll-off, and a small saturation increase without aggressive contrast.
- HDR processing is independent of Camera2 HDR scene-mode support. It is disabled while flash is enabled and can be toggled with the HDR button.
- Original JPEG is retained unchanged if processing fails or the source image is unusually large, to protect image quality and memory stability.


## GyroLock 3.0.9 changes
- Removed the 9:16 video ratio option; embedded Motion Photo video is fixed at 3:4 (720x960 portrait).
- Removed the opening 0.7-second zoom-out ramp; stabilization crop is constant from frame one.
- Added a brief post-shutter manual lens-focus pulse (target about 0.3 m where supported), then restores continuous-video autofocus. Some fixed-focus or restricted camera HALs may ignore manual focus.
