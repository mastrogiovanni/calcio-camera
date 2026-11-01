import it.calciocamera.NdiSender;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/** Optional desktop integration check against a separately installed official NDI runtime. */
public final class NdiSmoke {
    public interface Receiver extends Library {
        Pointer NDIlib_recv_create_v3(Pointer settings);
        void NDIlib_recv_connect(Pointer receiver, NdiSender.Source source);
        int NDIlib_recv_capture_v3(Pointer receiver, NdiSender.Video video, Pointer audio, Pointer metadata, int timeout);
        void NDIlib_recv_free_video_v2(Pointer receiver, NdiSender.Video video);
        void NDIlib_recv_destroy(Pointer receiver);
        Pointer NDIlib_send_get_source_name(Pointer sender);
    }

    public static void main(String[] args) throws Exception {
        if (new NdiSender.Video().size() != 72 || new NdiSender.Create().size() != 24) {
            throw new AssertionError("Unexpected 64-bit NDI structure layout");
        }
        Receiver api = Native.load("ndi", Receiver.class);
        Pointer recv = null;
        try (NdiSender sender = new NdiSender("Calcio Camera integration test")) {
            // Use the source struct from the same sender without requiring multicast discovery.
            java.lang.reflect.Field instance = NdiSender.class.getDeclaredField("instance");
            instance.setAccessible(true);
            NdiSender.Source source = new NdiSender.Source(api.NDIlib_send_get_source_name((Pointer) instance.get(sender)));
            recv = api.NDIlib_recv_create_v3(null);
            if (recv == null) throw new AssertionError("Receiver creation failed");
            api.NDIlib_recv_connect(recv, source);
            byte[] pixels = new byte[640 * 360 * 3 / 2];
            java.util.Arrays.fill(pixels, 0, 640 * 360, (byte) 126);
            java.util.Arrays.fill(pixels, 640 * 360, pixels.length, (byte) 128);
            NdiSender.Video video = new NdiSender.Video();
            boolean captured = false;
            for (int i = 0; i < 100; i++) {
                sender.send(pixels, 640, 360, 30);
                if (api.NDIlib_recv_capture_v3(recv, video, null, null, 50) == 1) {
                    try {
                        if (video.width != 640 || video.height != 360 || video.data == null)
                            throw new AssertionError("Invalid received frame: " + video.width + "x" + video.height);
                        System.out.println("PASS: " + sender.sourceName() + ", 640x360 I420 submitted and video received, " + sender.connections() + " receiver(s)");
                        captured = true;
                        break;
                    } finally { api.NDIlib_recv_free_video_v2(recv, video); }
                }
                Thread.sleep(10);
            }
            if (!captured) throw new AssertionError("No frame received within timeout");
            api.NDIlib_recv_destroy(recv);
            recv = null;
        } finally {
            if (recv != null) api.NDIlib_recv_destroy(recv);
        }
    }
}
