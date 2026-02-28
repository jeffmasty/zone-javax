package judahzone.javax;


import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import judahzone.util.Constants;

public class FloatToLine {
    // convert mono float[] (-1..1) to 16-bit signed little-endian bytes (reuses outBytes)
    public static void floatsTo16LE(float[] in, byte[] outBytes) {
        int len = Math.min(in.length, outBytes.length / 2);
        int o = 0;
        for (int i = 0; i < len; i++) {
            float f = in[i];
            // clamp
            if (f > 1f) f = 1f;
            else if (f < -1f) f = -1f;
            short s = (short) (f * 32767f);
            outBytes[o++] = (byte) (s & 0xFF);
            outBytes[o++] = (byte) ((s >>> 8) & 0xFF);
        }
    }

    // example streaming writer (mono, 48000 Hz, 16-bit LE)
    public static void stream(float[] floatBuffer) throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(Constants.bufSize(), 16, 1, true, false); // signed, little-endian
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            line.open(fmt, 4096); // choose appropriate buffer size
            line.start();

            // reuse byte buffer sized for frames (2 bytes per sample for 16-bit mono)
            byte[] out = new byte[floatBuffer.length * 2];
            floatsTo16LE(floatBuffer, out);
            // blocking write - in a loop you would refill floatBuffer with DSP outputs and keep writing
            line.write(out, 0, out.length);

            line.drain();
            line.stop();
        }
    }
}
