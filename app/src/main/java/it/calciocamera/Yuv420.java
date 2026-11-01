package it.calciocamera;

import java.nio.ByteBuffer;

/** Packs Android's strided YUV_420_888 planes into NDI I420, with physical rotation. */
public final class Yuv420 {
    private Yuv420() {}

    public static void pack(ByteBuffer y, int yRow, int yPixel,
                            ByteBuffer u, int uRow, int uPixel,
                            ByteBuffer v, int vRow, int vPixel,
                            int width, int height, int rotation, byte[] out) {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0
                || out.length < width * height * 3 / 2
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
            throw new IllegalArgumentException("Dimensioni o rotazione YUV non valide");
        }
        plane(y, yRow, yPixel, width, height, rotation, out, 0);
        plane(u, uRow, uPixel, width / 2, height / 2, rotation, out, width * height);
        plane(v, vRow, vPixel, width / 2, height / 2, rotation, out, width * height * 5 / 4);
    }

    private static void plane(ByteBuffer src, int row, int pixel, int w, int h,
                              int rotation, byte[] dst, int offset) {
        int base = src.position();
        int outWidth = rotation == 90 || rotation == 270 ? h : w;
        for (int sy = 0; sy < h; sy++) {
            if (rotation == 0 && pixel == 1) {
                ByteBuffer line = src.duplicate();
                line.position(base + sy * row);
                line.get(dst, offset + sy * w, w);
                continue;
            }
            for (int sx = 0; sx < w; sx++) {
                int dx, dy;
                switch (rotation) {
                    case 90: dx = h - 1 - sy; dy = sx; break;
                    case 180: dx = w - 1 - sx; dy = h - 1 - sy; break;
                    case 270: dx = sy; dy = w - 1 - sx; break;
                    default: dx = sx; dy = sy;
                }
                dst[offset + dy * outWidth + dx] = src.get(base + sy * row + sx * pixel);
            }
        }
    }
}
