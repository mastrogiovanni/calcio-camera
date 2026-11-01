package it.calciocamera;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class Yuv420Test {
    @Test public void removesRowPaddingAndDeinterleavesChromaWithoutChangingBufferPositions() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 4, 0, 0, 5, 6, 7, 8});
        y.position(1);
        ByteBuffer uv = ByteBuffer.wrap(new byte[]{10, 20, 11, 21});
        ByteBuffer v = uv.duplicate(); v.position(1);
        byte[] output = new byte[12];
        Yuv420.pack(y, 6, 1, uv, 4, 2, v, 4, 2, 4, 2, 0, output);
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8,10,11,20,21}, output);
        assertEquals(1, y.position());
        assertEquals(0, uv.position());
        assertEquals(1, v.position());
    }

    @Test public void rotatesLumaAndChromaForAllSensorOrientations() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{1,2,3,4,5,6,7,8});
        ByteBuffer u = ByteBuffer.wrap(new byte[]{10,11});
        ByteBuffer v = ByteBuffer.wrap(new byte[]{20,21});
        byte[] out = new byte[12];
        Yuv420.pack(y, 4, 1, u, 2, 1, v, 2, 1, 4, 2, 90, out);
        assertArrayEquals(new byte[]{5,1,6,2,7,3,8,4,10,11,20,21}, out);
        Yuv420.pack(y, 4, 1, u, 2, 1, v, 2, 1, 4, 2, 180, out);
        assertArrayEquals(new byte[]{8,7,6,5,4,3,2,1,11,10,21,20}, out);
        Yuv420.pack(y, 4, 1, u, 2, 1, v, 2, 1, 4, 2, 270, out);
        assertArrayEquals(new byte[]{4,8,3,7,2,6,1,5,11,10,21,20}, out);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsOddWidth() {
        ByteBuffer p = ByteBuffer.allocate(16);
        Yuv420.pack(p, 4, 1, p, 2, 1, p, 2, 1, 3, 2, 0, new byte[16]);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsShortDestination() {
        ByteBuffer p = ByteBuffer.allocate(16);
        Yuv420.pack(p, 4, 1, p, 2, 1, p, 2, 1, 4, 2, 0, new byte[11]);
    }
}
