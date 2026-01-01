package judahzone.javax;

import java.io.Closeable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.swing.SwingUtilities;

import judahzone.api.PlayAudio;
import judahzone.api.Played;
import judahzone.util.Recording;
import judahzone.util.Services;
import judahzone.util.Threads;
import judahzone.util.WavConstants;

/**
 * JavaxOut — underlying playback implementation using Java Sound SourceDataLine.
 *
 * Responsibilities:
 * - Implement the PlayAudio contract.
 * - Manage audio line, conversion of float samples to PCM16LE, playback thread, seeking.
 * - On natural EOF (playback runs out of frames) always call rewind(). If Type==LOOP continue
 *   playback from the beginning automatically; otherwise stop playback.
 *
 * Notes:
 * - This class is independent of the GUI. Playa will call its methods to control playback.
 * - TimeDomain is optional; if provided JavaxOut will update the head index on the EDT.
 */
public class JavaxOutSimpler implements PlayAudio, Closeable {

    private Recording tape;
    private final Played timeDomain; // optional visual feedback target
    private AudioFormat format;

    private volatile boolean playing = false;
    private Thread playThread;
    private volatile long playFrame = 0L; // absolute sample-frames (interleaved frames)
    private volatile Type type = Type.ONE_SHOT;
    private volatile float mastering = 1.0f;

    private SourceDataLine line;

    public JavaxOutSimpler(Played timeDomain) {
        this.timeDomain = timeDomain;
    }

    @Override
    public synchronized void setRecording(Recording r) {
        this.tape = r;
        this.format = (r != null) ? r.getFormat() : null;
        // reset position when a new tape is set
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
            // stop playback, but per requirement do not rewind on explicit stop
            playing = false;
            if (playThread != null) {
                playThread.interrupt();
                try { playThread.join(300); } catch (InterruptedException ignored) {}
                playThread = null;
            }
            // async flush/stop the line to avoid blocking caller/EDT
            SourceDataLine l = line;
            if (l != null) {
                Threads.execute(() -> {
                    try { l.flush(); } catch (Throwable ignored) {}
                });
            }
            Services.remove(this);
        }
    }

    @Override
    public synchronized boolean isPlaying() {
        return playing;
    }

    @Override
    public synchronized int getLength() {
        if (tape == null) return 0;
        return tape.size() * WavConstants.JACK_BUFFER;
    }

    @Override
    public synchronized float seconds() {
        int frames = getLength();
        if (frames <= 0) return 0f;
        return frames / (float) WavConstants.S_RATE;
    }

    @Override
    public synchronized void rewind() {
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

    public synchronized void setMastering(float m) {
        this.mastering = m;
    }

    public synchronized void setPlaySampleFrame(long sampleFrame) {
        if (sampleFrame < 0) sampleFrame = 0;
        long total = totalFrames();
        if (total > 0 && sampleFrame >= total) sampleFrame = Math.max(0, total - 1);
        this.playFrame = sampleFrame;
        // flush line asynchronously
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
        int bufferBytes = WavConstants.FFT_SIZE * frameSize;
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format, bufferBytes * 4);
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, Math.max(bufferBytes, line.getBufferSize()));
        line.start();
    }

    private static AudioFormat defaultFormat() {
        float sampleRate = WavConstants.S_RATE;
        int sampleSizeInBits = WavConstants.VALID_BITS;
        int channels = WavConstants.STEREO;
        boolean signed = true;
        boolean bigEndian = false;
        return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
    }

    /**
     * Playback thread body.
     *
     * EOF handling:
     * - When natural EOF reached, always call rewind().
     * - If type==LOOP, continue playback from start; otherwise stop playback.
     */
    private void playLoop() {
        final int framesPerChunk = WavConstants.FFT_SIZE;
        final int channels = WavConstants.STEREO;
        final int bytesPerSample = WavConstants.SAMPLE_BYTES;
        final int jack = WavConstants.JACK_BUFFER;

        float[] leftPad = new float[jack];
        float[] rightPad = new float[jack];

        int frameSize;
        byte[] pcmChunk;

        try {
            if (line == null) openLineForFft();
            frameSize = format.getFrameSize();
            if (frameSize <= 0) frameSize = bytesPerSample * channels;
            pcmChunk = new byte[framesPerChunk * frameSize];

            while (playing && !Thread.currentThread().isInterrupted()) {
                long total = totalFrames();
                long remaining = total - playFrame;
                if (remaining <= 0) {
                    // reached EOF
                    rewind(); // always rewind on EOF per requirement

                    if (type == Type.LOOP && playing) {
                        // continue from beginning
                        continue;
                    } else {
                        // one-shot, stop playing but remain rewound
                        break;
                    }
                }

                int framesToRead = (int) Math.min(framesPerChunk, remaining);

                if (pcmChunk.length < framesToRead * frameSize) pcmChunk = new byte[framesToRead * frameSize];

                int segments = (framesToRead + jack - 1) / jack;

                int pcmOffset = 0;

                for (int seg = 0; seg < segments; seg++) {
                    int segStartFrame = seg * jack;
                    int segLen = Math.min(jack, framesToRead - segStartFrame);

                    // zero tails
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
                    if (pcmChunk.length < pcmOffset + bytesThisSegment) {
                        byte[] tmp = new byte[pcmOffset + bytesThisSegment];
                        System.arraycopy(pcmChunk, 0, tmp, 0, pcmOffset);
                        pcmChunk = tmp;
                    }

                    int j = pcmOffset;
                    for (int i = 0; i < segLen; i++) {
                        short ls = floatToPcm16(leftPad[i]);
                        short rs = floatToPcm16(rightPad[i]);
                        pcmChunk[j++] = (byte) (ls & 0xFF);
                        pcmChunk[j++] = (byte) ((ls >> 8) & 0xFF);
                        pcmChunk[j++] = (byte) (rs & 0xFF);
                        pcmChunk[j++] = (byte) ((rs >> 8) & 0xFF);
                    }

                    pcmOffset += bytesThisSegment;
                }

                int written = 0;
                while (written < pcmOffset && playing) {
                    int w = line.write(pcmChunk, written, pcmOffset - written);
                    if (w <= 0) break;
                    written += w;
                }

                playFrame += framesToRead;

                // Update TimeDomain visual head
                if (timeDomain != null) {
                    final int idx = (int) (playFrame / WavConstants.FFT_SIZE);
                    SwingUtilities.invokeLater(() -> timeDomain.setHeadIndex(idx));
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            // stop playing and clean up
            playing = false;
            Services.remove(this);
            // leave playFrame rewound if we hit EOF (rewind was already called)
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
            try { playThread.join(200); } catch (InterruptedException ignored) {}
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
}