package it.calciocamera;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** All methods are called on the camera thread. Uses the official NDI C ABI. */
public final class NdiSender implements AutoCloseable {
    public interface Api extends Library {
        // NDI uses C++ bool (one byte), not JNA's four-byte Boolean mapping.
        byte NDIlib_initialize();
        void NDIlib_destroy();
        Pointer NDIlib_send_create(Create settings);
        void NDIlib_send_destroy(Pointer sender);
        void NDIlib_send_send_video_v2(Pointer sender, Video frame);
        int NDIlib_send_get_no_connections(Pointer sender, int timeoutMs);
        Pointer NDIlib_send_get_source_name(Pointer sender);
    }

    @Structure.FieldOrder({"name", "groups", "clockVideo", "clockAudio"})
    public static class Create extends Structure {
        public String name;
        public String groups;
        public byte clockVideo;
        public byte clockAudio;
    }

    @Structure.FieldOrder({"width", "height", "fourCC", "fpsN", "fpsD", "aspect",
            "format", "timecode", "data", "stride", "metadata", "timestamp"})
    public static class Video extends Structure {
        public int width, height, fourCC, fpsN, fpsD;
        public float aspect;
        public int format;
        public long timecode;
        public Pointer data;
        public int stride;
        public Pointer metadata;
        public long timestamp;
    }

    @Structure.FieldOrder({"name", "address"})
    public static class Source extends Structure {
        public Pointer name, address;
        public Source(Pointer p) { super(p); read(); }
    }

    private final Api api;
    private Pointer instance;
    private Memory memory;
    private final Video frame = new Video();
    private boolean initialized;
    private String sourceName;

    public NdiSender(String name) {
        api = Native.load("ndi", Api.class);
        initialized = api.NDIlib_initialize() != 0;
        if (!initialized) throw new IllegalStateException("Il runtime NDI non si inizializza su questo dispositivo.");
        try {
            Create settings = new Create();
            settings.name = name;
            // The camera controls pacing. Never clock or sleep inside a camera callback.
            instance = api.NDIlib_send_create(settings);
            if (instance == null) throw new IllegalStateException("Impossibile creare la sorgente NDI.");
            Pointer source = api.NDIlib_send_get_source_name(instance);
            sourceName = source == null ? name : new Source(source).name.getString(0, "UTF-8");
        } catch (RuntimeException | LinkageError error) {
            close();
            throw error;
        }
    }

    public String sourceName() { return sourceName; }

    public void send(byte[] i420, int width, int height, int fps) {
        if (instance == null) return;
        int size = width * height * 3 / 2;
        if (memory == null || memory.size() != size) {
            if (memory != null) memory.close();
            memory = new Memory(size);
        }
        memory.write(0, i420, 0, size);
        frame.width = width;
        frame.height = height;
        frame.fourCC = 0x30323449; // I420: Y, U, V (not YV12).
        frame.fpsN = fps;
        frame.fpsD = 1;
        frame.aspect = (float) width / height;
        frame.format = 1; // NDIlib_frame_format_type_progressive
        frame.timecode = Long.MAX_VALUE; // NDIlib_send_timecode_synthesize
        frame.data = memory;
        frame.stride = width;
        // Synchronous submission: the buffer is safe to reuse when this returns.
        api.NDIlib_send_send_video_v2(instance, frame);
    }

    public int connections() { return instance == null ? 0 : api.NDIlib_send_get_no_connections(instance, 0); }

    @Override public void close() {
        try {
            if (instance != null) { api.NDIlib_send_destroy(instance); instance = null; }
        } finally {
            if (memory != null) { memory.close(); memory = null; }
            if (initialized) { api.NDIlib_destroy(); initialized = false; }
        }
    }
}
