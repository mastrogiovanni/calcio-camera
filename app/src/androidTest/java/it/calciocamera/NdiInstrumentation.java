package it.calciocamera;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Real Android SDK smoke test; requires libndi.so, not an SDK mock. */
public final class NdiInstrumentation extends Instrumentation {
    public interface Receiver extends Library {
        Pointer NDIlib_recv_create_v3(Pointer settings);
        void NDIlib_recv_connect(Pointer receiver, NdiSender.Source source);
        int NDIlib_recv_capture_v3(Pointer receiver, NdiSender.Video video, Pointer audio, Pointer metadata, int timeout);
        void NDIlib_recv_free_video_v2(Pointer receiver, NdiSender.Video video);
        void NDIlib_recv_destroy(Pointer receiver);
        Pointer NDIlib_send_get_source_name(Pointer sender);
        Pointer NDIlib_version();
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (new NdiSender.Video().size() != 72 || new NdiSender.Create().size() != 24)
                throw new AssertionError("Unexpected ARM64 ABI layout");
            Receiver api = Native.load("ndi", Receiver.class);
            result.putString("runtime", api.NDIlib_version().getString(0));
            for (int cycle = 0; cycle < 2; cycle++) verifyRoundTrip(api, cycle);
            result.putString("stream", "PASS: Android ARM64 I420 send, video receive, connection count, stop/restart");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verifyRoundTrip(Receiver api, int cycle) throws Exception {
        try (NdiSender sender = new NdiSender("Calcio Camera Android test " + cycle)) {
            Field handle = NdiSender.class.getDeclaredField("instance");
            handle.setAccessible(true);
            Pointer nativeSender = (Pointer) handle.get(sender);
            NdiSender.Source source = new NdiSender.Source(api.NDIlib_send_get_source_name(nativeSender));
            Pointer receiver = api.NDIlib_recv_create_v3(null);
            if (receiver == null) throw new AssertionError("Receiver creation failed");
            try {
                api.NDIlib_recv_connect(receiver, source);
                byte[] pixels = new byte[640 * 360 * 3 / 2];
                Arrays.fill(pixels, 0, 640 * 360, (byte) 126);
                Arrays.fill(pixels, 640 * 360, pixels.length, (byte) 128);
                NdiSender.Video video = new NdiSender.Video();
                long deadline = SystemClock.elapsedRealtime() + 10000;
                int received = 0;
                while (SystemClock.elapsedRealtime() < deadline) {
                    sender.send(pixels, 640, 360, 30);
                    if (api.NDIlib_recv_capture_v3(receiver, video, null, null, 100) == 1) {
                        try {
                            if (video.width != 640 || video.height != 360 || video.data == null)
                                throw new AssertionError("Invalid received video frame");
                            received++;
                        } finally { api.NDIlib_recv_free_video_v2(receiver, video); }
                        if (received >= 3 && sender.connections() >= 1) return;
                    }
                    SystemClock.sleep(15);
                }
                throw new AssertionError("NDI round-trip timed out (received " + received + " frames)");
            } finally { api.NDIlib_recv_destroy(receiver); }
        }
    }
}
