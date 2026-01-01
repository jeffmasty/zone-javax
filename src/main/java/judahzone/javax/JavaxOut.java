package judahzone.javax;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.swing.SwingUtilities;

import judahzone.api.PlayAudio;
import judahzone.api.Played;
import judahzone.util.RTLogger;
import judahzone.util.Recording;
import judahzone.util.Services;
import judahzone.util.Threads;
import judahzone.util.WavConstants;

/**
 * JavaxOut — underlying playback implementation using Java Sound SourceDataLine.
 *
 * timeDomain is mutable so a single JavaxOut instance can be reused across TimeDomains.
 */
public class JavaxOut implements PlayAudio, Closeable {

    private Recording tape;
    private volatile Played timeDomain; // mutable attachment
    private AudioFormat format;

    private volatile boolean playing = false;
    private Thread playThread;
    private volatile long playFrame = 0L; // absolute sample-frames (interleaved frames)
    private volatile Type type = Type.ONE_SHOT;
    private volatile float mastering = 1.0f;

    private SourceDataLine line;

    private volatile boolean flushRequested = false;
    private final AtomicInteger underrunCount = new AtomicInteger(0);

    public JavaxOut(Played timeDomain) {
        this.timeDomain = timeDomain;
    }

    /** Allow attaching a TimeDomain (Played) after creation. */
    public void setTimeDomain(Played td) {
        this.timeDomain = td;
    }

    @Override
    public synchronized void setRecording(Recording r) {
        this.tape = r;
        this.format = (r != null) ? r.getFormat() : null;
        rewind();
    }

    @Override
    public synchronized void play(boolean onOrOff) {
        if (onOrOff == playing) return;
        if (onOrOff) {
            if (tape == null || tape.size() == 0) {
                // nothing to play
                return;
            }
            Services.add(this);
            playing = true;
            playThread = new Thread(this::playLoop, "JavaxOut-playback");
            playThread.setDaemon(true);
            playThread.start();
        } else {
            playing = false;
            if (playThread != null) {
                playThread.interrupt();
                try { playThread.join(500); } catch (InterruptedException ignored) {}
                playThread = null;
            }
            SourceDataLine l = line;
            if (l != null) {
                Threads.execute(() -> {
                    try { l.flush(); } catch (Throwable ignored) {}
                });
            }
            Services.remove(this);
        }
    }

    @Override public synchronized boolean isPlaying() { return playing; }

    @Override public synchronized int getLength() {
        if (tape == null) return 0;
        return tape.size() * WavConstants.JACK_BUFFER;
    }

    @Override public synchronized float seconds() {
        int frames = getLength();
        if (frames <= 0) return 0f;
        return frames / (float) WavConstants.S_RATE;
    }

    @Override public synchronized void rewind() {
        playFrame = 0L;
        if (timeDomain != null) {
            final int idx = (int) (playFrame / WavConstants.FFT_SIZE);
            SwingUtilities.invokeLater(() -> timeDomain.setHeadIndex(idx));
        }
    }

    public synchronized void setType(Type t) {
        if (t == null) t = Type.ONE_SHOT;
        this.type = t;
    }

    public synchronized void setMastering(float m) { this.mastering = m; }

    public synchronized void setPlaySampleFrame(long sampleFrame) {
        if (sampleFrame < 0) sampleFrame = 0;
        long total = totalFrames();
        if (total > 0 && sampleFrame >= total) sampleFrame = Math.max(0, total - 1);
        this.playFrame = sampleFrame;
        flushRequested = true;
        if (line != null) {
            Threads.execute(() -> {
                try { line.flush(); } catch (Throwable ignored) {}
            });
        }
        if (timeDomain != null) {
            final int idx = (int) (playFrame / WavConstants.FFT_SIZE);
            SwingUtilities.invokeLater(() -> timeDomain.setHeadIndex(idx));
        }
    }

    private long totalFrames() {
        if (tape == null) return 0L;
        return (long) tape.size() * (long) WavConstants.JACK_BUFFER;
    }

    private void openLineForFft() throws LineUnavailableException {
        if (format == null) format = defaultFormat();
        int frameSize = format.getFrameSize();
        if (frameSize <= 0) frameSize = (WavConstants.SAMPLE_BYTES) * WavConstants.STEREO;
        final int jack = WavConstants.JACK_BUFFER;
        final int LINE_BUFFER_MULTIPLIER = 4;
        int bufferBytes = jack * frameSize;
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format, bufferBytes * LINE_BUFFER_MULTIPLIER);
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, Math.max(bufferBytes, line.getBufferSize()));
    }

    private static AudioFormat defaultFormat() {
        float sampleRate = WavConstants.S_RATE;
        int sampleSizeInBits = WavConstants.VALID_BITS;
        int channels = WavConstants.STEREO;
        boolean signed = true;
        boolean bigEndian = false;
        return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
    }

    private void playLoop() {
        final int framesPerChunk = WavConstants.FFT_SIZE;
        final int channels = WavConstants.STEREO;
        final int bytesPerSample = WavConstants.SAMPLE_BYTES;
        final int jack = WavConstants.JACK_BUFFER;

        final float[] leftPad = new float[jack];
        final float[] rightPad = new float[jack];

        int frameSize;

        try {
            if (line == null) openLineForFft();
            frameSize = format.getFrameSize();
            if (frameSize <= 0) frameSize = bytesPerSample * channels;

            final byte[] segBytes = new byte[jack * frameSize];

            try { line.start(); } catch (Throwable t) { RTLogger.warn(this, t); }

            while (playing && !Thread.currentThread().isInterrupted()) {
                if (flushRequested) {
                    try { line.flush(); } catch (Throwable ignored) {}
                    flushRequested = false;
                }

                long total = totalFrames();
                long remaining = total - playFrame;
                if (remaining <= 0) {
                    rewind();
                    if (type == Type.LOOP && playing) {
                        continue;
                    } else {
                        break;
                    }
                }

                int framesToRead = (int) Math.min(framesPerChunk, remaining);
                int segments = (framesToRead + jack - 1) / jack;

                for (int seg = 0; seg < segments && playing; seg++) {
                    int segStartFrame = seg * jack;
                    int segLen = Math.min(jack, framesToRead - segStartFrame);

                    if (segLen < jack) {
                        for (int i = segLen; i < jack; i++) leftPad[i] = rightPad[i] = 0f;
                    }

                    if (segLen > 0) {
                        float[] tmpL = new float[segLen];
                        float[] tmpR = new float[segLen];
                        tape.getSamples(playFrame + segStartFrame, tmpL, WavConstants.LEFT);
                        tape.getSamples(playFrame + segStartFrame, tmpR, WavConstants.RIGHT);

                        System.arraycopy(tmpL, 0, leftPad, 0, segLen);
                        System.arraycopy(tmpR, 0, rightPad, 0, segLen);
                    } else {
                        for (int i = 0; i < jack; i++) leftPad[i] = rightPad[i] = 0f;
                    }

                    float m = mastering;
                    if (m != 1.0f) {
                        for (int i = 0; i < segLen; i++) {
                            leftPad[i] *= m;
                            rightPad[i] *= m;
                        }
                    }

                    int bytesThisSegment = segLen * frameSize;

                    int j = 0;
                    for (int i = 0; i < segLen; i++) {
                        short ls = floatToPcm16(leftPad[i]);
                        short rs = floatToPcm16(rightPad[i]);
                        segBytes[j++] = (byte) (ls & 0xFF);
                        segBytes[j++] = (byte) ((ls >> 8) & 0xFF);
                        segBytes[j++] = (byte) (rs & 0xFF);
                        segBytes[j++] = (byte) ((rs >> 8) & 0xFF);
                    }

                    int canWrite = 1;
                    try { canWrite = line.available(); } catch (Throwable ignored) { canWrite = 1; }

                    if (canWrite <= 0) {
                        try { Thread.sleep(1); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        underrunCount.incrementAndGet();
                    }

                    int written = 0;
                    while (written < bytesThisSegment && playing) {
                        int w = 0;
                        try {
                            w = line.write(segBytes, written, bytesThisSegment - written);
                        } catch (Throwable t) {
                            RTLogger.warn(this, t);
                            break;
                        }
                        if (w <= 0) break;
                        written += w;
                    }
                }

                playFrame += framesToRead;

                if (timeDomain != null) {
                    final int idx = (int) (playFrame / WavConstants.FFT_SIZE);
                    SwingUtilities.invokeLater(() -> timeDomain.setHeadIndex(idx));
                }
            }
        } catch (Throwable t) {
            RTLogger.warn(this, t);
        } finally {
            playing = false;
            Services.remove(this);
            try {
                if (line != null) {
                    try { line.stop(); } catch (Throwable ignored) {}
                    try { line.flush(); } catch (Throwable ignored) {}
                    try { line.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            line = null;
        }
    }

    private static short floatToPcm16(float f) {
        if (f > 1f) f = 1f;
        if (f < -1f) f = -1f;
        return (short) Math.round(f * Short.MAX_VALUE);
    }

    @Override
    public void close() {
        playing = false;
        if (playThread != null) {
            playThread.interrupt();
            try { playThread.join(300); } catch (InterruptedException ignored) {}
            playThread = null;
        }
        final SourceDataLine l = this.line;
        if (l != null) {
            Threads.execute(() -> {
                try { l.stop(); } catch (Throwable ignored) {}
                try { l.flush(); } catch (Throwable ignored) {}
                try { l.close(); } catch (Throwable ignored) {}
            });
            this.line = null;
        }
    }

    public int getUnderrunCount() { return underrunCount.get(); }
}