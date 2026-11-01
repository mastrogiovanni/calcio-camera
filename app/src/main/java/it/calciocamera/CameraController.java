package it.calciocamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.Arrays;
import java.util.Comparator;

/** Camera, image buffers and NDI sender share a serial worker: stop cannot free an in-flight frame. */
public final class CameraController {
    public interface Listener {
        void onReady(int width, int height, int rotation, int fps, boolean torch);
        void onRotationChanged(int rotation);
        void onStarted(String sourceName);
        void onStats(float fps, int receivers, long sent, boolean transmitting);
        void onStopped();
        void onCameraError(String message);
        void onError(String message);
    }
    private final Context context;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("Camera-NDI");
    private final Handler worker;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface preview;
    private CaptureRequest.Builder request;
    private NdiSender sender;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private boolean front, torch, ready, firstFrame;
    private int rotation, sensorAngle, fps = 30, generation;
    private byte[] pixels;
    private long sent, statTime, statFrames, lastSentTime;

    public CameraController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public void open(SurfaceTexture texture, boolean useFront, int targetHeight, int targetFps, int displayDegrees) {
        worker.post(() -> {
            closeInternal();
            final int token = generation;
            front = useFront;
            fps = targetFps;
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                String selected = null;
                CameraCharacteristics info = null;
                int facing = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics candidate = manager.getCameraCharacteristics(id);
                    Integer lens = candidate.get(CameraCharacteristics.LENS_FACING);
                    if (lens != null && lens == facing) { selected = id; info = candidate; break; }
                }
                if (selected == null || info == null) throw new IllegalStateException("Fotocamera richiesta non disponibile.");
                StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) throw new IllegalStateException("Nessun formato video disponibile.");
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                Size[] previews = map.getOutputSizes(SurfaceTexture.class);
                if (sizes == null || previews == null) throw new IllegalStateException("Formato YUV non supportato.");
                // Both outputs must support the exact same size. Avoid unexpectedly selecting 4K.
                Size size = Arrays.stream(sizes)
                    .filter(s -> s.getWidth() % 2 == 0 && s.getHeight() % 2 == 0)
                    .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                    .filter(s -> Arrays.asList(previews).contains(s))
                    .min(Comparator.comparingDouble(s -> Math.abs((double) s.getWidth() / s.getHeight() - 16.0 / 9) * 10000
                        + Math.abs(s.getHeight() - targetHeight)))
                    .orElseThrow(() -> new IllegalStateException("Nessun formato video compatibile fino a 1080p."));
                Integer sensor = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorAngle = sensor == null ? 0 : sensor;
                rotation = (sensorAngle + (front ? displayDegrees : -displayDegrees) + 360) % 360;
                boolean hasTorch = Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
                int[] afModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                int focusMode = afModes != null && Arrays.stream(afModes).anyMatch(m -> m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_OFF;
                Range<Integer>[] ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                Range<Integer> rate = ranges == null ? null : Arrays.stream(ranges)
                    .min(Comparator.comparingInt(r -> Math.abs(r.getUpper() - targetFps) * 10 + Math.abs(r.getLower() - targetFps)))
                    .orElse(null);
                if (rate != null) fps = Math.min(targetFps, rate.getUpper());
                texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
                preview = new Surface(texture);
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
                reader.setOnImageAvailableListener(this::imageAvailable, worker);
                if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Consenti l’accesso alla fotocamera nelle impostazioni Android.");
                }
                manager.openCamera(selected, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        if (token != generation) { device.close(); return; }
                        camera = device;
                        configure(token, size, hasTorch, rate, focusMode);
                    }
                    @Override public void onDisconnected(CameraDevice device) {
                        device.close();
                        if (token == generation) fail("Fotocamera disconnessa. Riapri l’anteprima.");
                    }
                    @Override public void onError(CameraDevice device, int error) {
                        device.close();
                        if (token == generation) fail("Fotocamera occupata o non disponibile (" + error + ").");
                    }
                }, worker);
            } catch (CameraAccessException | RuntimeException error) {
                fail(error.getMessage());
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void configure(int token, Size size, boolean hasTorch, Range<Integer> rate, int focusMode) {
        try {
            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(preview);
            request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
            if (rate != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, rate);
            camera.createCaptureSession(Arrays.asList(preview, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (token != generation || camera == null) { configured.close(); return; }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), null, worker);
                        ready = true;
                        statTime = SystemClock.elapsedRealtime();
                        statFrames = 0;
                        int outputRotation = rotation;
                        int outputFps = fps;
                        main.post(() -> listener.onReady(size.getWidth(), size.getHeight(), outputRotation, outputFps, hasTorch));
                    } catch (CameraAccessException | RuntimeException error) { fail(error.getMessage()); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    if (token == generation) fail("Configurazione video non supportata: prova 720p.");
                }
            }, worker);
        } catch (CameraAccessException | RuntimeException error) { fail(error.getMessage()); }
    }

    private void imageAvailable(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null || source != reader || !ready) return;
            statFrames++;
            if (sender != null) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (lastSentTime == 0 || now - lastSentTime >= 1_000_000_000L / fps - 1_000_000L) {
                    int w = image.getWidth(), h = image.getHeight();
                    if (pixels == null || pixels.length != w * h * 3 / 2) pixels = new byte[w * h * 3 / 2];
                    Image.Plane[] p = image.getPlanes();
                    Yuv420.pack(p[0].getBuffer(), p[0].getRowStride(), p[0].getPixelStride(),
                        p[1].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(),
                        p[2].getBuffer(), p[2].getRowStride(), p[2].getPixelStride(), w, h, rotation, pixels);
                    boolean swap = rotation == 90 || rotation == 270;
                    sender.send(pixels, swap ? h : w, swap ? w : h, fps);
                    sent++;
                    lastSentTime = now;
                    if (!firstFrame) {
                        firstFrame = true;
                        String name = sender.sourceName();
                        main.post(() -> listener.onStarted(name));
                    }
                }
            }
            long now = SystemClock.elapsedRealtime();
            if (now - statTime >= 1000) {
                float measured = (sender == null ? statFrames : sent - previousSent) * 1000f / (now - statTime);
                int receivers = sender == null ? 0 : sender.connections();
                long total = sent;
                boolean transmitting = sender != null && firstFrame;
                main.post(() -> listener.onStats(measured, receivers, total, transmitting));
                previousSent = sent;
                statTime = now;
                statFrames = 0;
            }
        } catch (RuntimeException | LinkageError error) {
            stopInternal();
            main.post(() -> listener.onError("Errore durante l’invio: " + error.getMessage()));
        }
    }
    private long previousSent;

    public void start(String sourceName) {
        worker.post(() -> {
            if (!ready) {
                main.post(() -> listener.onError("Attendi che la fotocamera sia pronta prima di avviare la trasmissione."));
                return;
            }
            if (sender != null) return;
            try {
                WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                multicastLock = wifi.createMulticastLock("CalcioCamera-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CalcioCamera-video");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                sender = new NdiSender(sourceName);
                sent = previousSent = 0;
                firstFrame = false;
                lastSentTime = 0;
                statTime = SystemClock.elapsedRealtime();
                statFrames = 0;
            } catch (RuntimeException | LinkageError error) {
                stopInternal();
                main.post(() -> listener.onError("NDI non disponibile. Importa libndi.so dall’SDK Android ufficiale e ricompila. Dettaglio: " + error.getMessage()));
            }
        });
    }

    public void stop() { worker.post(this::stopInternal); }
    public void toggleTorch() {
        worker.post(() -> {
            if (!ready || session == null || request == null) return;
            try {
                torch = !torch;
                request.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                session.setRepeatingRequest(request.build(), null, worker);
            } catch (CameraAccessException | RuntimeException error) {
                main.post(() -> listener.onError("Torcia non disponibile: " + error.getMessage()));
            }
        });
    }

    private void stopInternal() {
        try { if (sender != null) sender.close(); }
        finally {
            sender = null;
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            multicastLock = null;
            wifiLock = null;
            main.post(listener::onStopped);
        }
    }

    private void fail(String message) {
        closeInternal();
        main.post(() -> listener.onCameraError(message == null ? "Errore fotocamera." : message));
    }

    public void pause() { worker.post(this::closeInternal); }
    public void updateDisplayRotation(int degrees) {
        worker.post(() -> {
            rotation = (sensorAngle + (front ? degrees : -degrees) + 360) % 360;
            int current = rotation;
            main.post(() -> listener.onRotationChanged(current));
        });
    }
    public void releaseTexture(SurfaceTexture texture) {
        if (!worker.post(() -> { closeInternal(); texture.release(); })) texture.release();
    }
    private void closeInternal() {
        generation++;
        ready = false;
        stopInternal();
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (preview != null) { preview.release(); preview = null; }
        request = null;
        pixels = null;
        torch = false;
    }
    public void destroy() { worker.post(() -> { closeInternal(); thread.quitSafely(); }); }
}
