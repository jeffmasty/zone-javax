package judahzone.javax;

import java.awt.event.ItemEvent;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import judahzone.api.FX.Calc;
import judahzone.api.Transform;
import judahzone.util.Services;
import judahzone.util.WavConstants;

/**
 * JavaxIn — capture audio from javax.sound (TargetDataLine) and feed JudahScope.analyze()
 *
 * Behavior:
 * - Presents a small controls panel with a mixer/device selector and a Start/Stop button.
 * - Captures audio in the project canonical format (WavConstants.S_RATE, 16-bit, stereo).
 * - Accumulates JACK_BUFFER-sized stereo blocks into a deque. When enough blocks have been
 *   collected to form an FFT_SIZE window (CHUNKS = FFT_SIZE / JACK_BUFFER), it assembles a
 *   Recording containing those consecutive blocks and calls JudahScope.analyze(recordingWindow).
 *
 * - availableMixersSupportingCapture() now filters out obvious non-capture devices (MIDI controllers etc.)
 *   by checking whether the Mixer advertises a TargetDataLine for the canonical AudioFormat,
 *   and falls back to checking for any TargetDataLine class in the mixer's target lines.
 * - captureLoop() now treats read==0 (no bytes) as a valid zero-filled block and still appends it to
 *   the deque so analyze() is invoked periodically even if the device is silent. A short sleep is kept
 *   to avoid a tight busy loop when no data is available.
 *
 * Notes:
 * - Filtering is conservative: it prefers mixers that explicitly support the canonical format. If none
 *   match, the default system mixer is included so the UI is never empty.
 */
public class JavaxIn implements Closeable {

    private final Calc<Transform> analyzer;
    private final JComboBox<Mixer.Info> devices;
    private final JButton startStop = new JButton("Start");
    private final JLabel statusLabel = new JLabel("idle");

    private volatile boolean running = false;
    private Thread captureThread;
    private TargetDataLine line;

    // ring of most recent JACK_BUFFER blocks while building up FFT windows
    private final Deque<float[][]> blockDeque = new ArrayDeque<>();

    public JavaxIn(Calc<Transform> receiver) {
    	analyzer = receiver;
        devices = new JComboBox<>(availableMixersSupportingCapture());

        // restart capture on change
        devices.addItemListener(e -> {
		    if (e.getStateChange() == ItemEvent.SELECTED) {
		    	stop();
		    	start();
		    }
		});

        if (devices.getItemCount() > 1)
        	devices.setSelectedIndex(1); // author's PCH mic
    }

    public JComboBox<Mixer.Info> getDevices() {
    	return devices;
    }

    /**Query available mixers that appear to support TargetDataLine (capture).
     *
     * Strategy:
     * - Prefer mixers that explicitly advertise support for the canonical AudioFormat (DataLine.Info).
     * - As a fallback accept mixers that advertise any TargetDataLine in their target lines.
     * - Ensure the system default mixer is present in the returned list.
     */
    private Mixer.Info[] availableMixersSupportingCapture() {
        AudioFormat fmt = canonicalFormat();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        List<Mixer.Info> good = new ArrayList<>();

        DataLine.Info wantInfo = new DataLine.Info(TargetDataLine.class, fmt);

        Mixer.Info defaultInfo = null;
        try {
            Mixer defaultMixer = AudioSystem.getMixer(null);
            if (defaultMixer != null) defaultInfo = defaultMixer.getMixerInfo();
        } catch (Throwable ignored) {}

        for (Mixer.Info info : infos) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                // First, check if mixer explicitly supports the exact canonical format
                if (mixer.isLineSupported(wantInfo)) {
                    good.add(info);
                    continue;
                }
                // Next, check if the mixer advertises any TargetDataLine (capture) at all
                Line.Info[] targetLineInfos = mixer.getTargetLineInfo();
                boolean hasTarget = false;
                if (targetLineInfos != null) {
                    for (Line.Info li : targetLineInfos) {
                        Class<?> lineClass = li.getLineClass();
                        if (TargetDataLine.class.isAssignableFrom(lineClass)) {
                            hasTarget = true;
                            break;
                        }
                    }
                }
                if (hasTarget) {
                    good.add(info);
                } else {
                    // skip: likely a MIDI-only device (thin controllers) or output-only device
                }
            } catch (Throwable t) {
                // be conservative: don't crash UI enumeration if a mixer throws
                System.err.println("Mixer probe failed for " + info + ": " + t);
            }
        }

        // Ensure default mixer shows up first
        List<Mixer.Info> ordered = new ArrayList<>();
        if (defaultInfo != null && good.contains(defaultInfo)) {
            ordered.add(defaultInfo);
        } else if (defaultInfo != null && !good.contains(defaultInfo)) {
            // if default wasn't included by capability checks, include it as a fallback
            ordered.add(defaultInfo);
        }
        // add remaining good mixers, but avoid duplicating the default
        for (Mixer.Info mi : good) {
            if (!ordered.contains(mi)) ordered.add(mi);
        }

        // If nothing matched, fall back to returning all mixers to avoid empty device list
        if (ordered.isEmpty()) {
            return infos;
        }
        return ordered.toArray(new Mixer.Info[0]);
    }

    /**
     * Start capture from the selected device. Runs capture loop on a background thread.
     */
    public synchronized void start() {
        if (running) return;
        Services.add(this);

        Mixer.Info sel = (Mixer.Info) devices.getSelectedItem();
        AudioFormat format = canonicalFormat();
        try {
            Mixer mixer = (sel == null) ? AudioSystem.getMixer(null) : AudioSystem.getMixer(sel);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!mixer.isLineSupported(info)) {
                // Try system default if selected mixer doesn't support format
                updateStatus("format unsupported on selected device");
                return;
            }

            line = (TargetDataLine) mixer.getLine(info);
            line.open(format, WavConstants.JACK_BUFFER * format.getFrameSize());
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            updateStatus("open failed: " + e.getMessage());
            return;
        }

        running = true;
        startStop.setText("Stop");
        updateStatus("running");

        captureThread = new Thread(this::captureLoop, "JavaxIn-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /**
     * Stop capture and release resources.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        startStop.setText("Start");
        updateStatus("stopping");
        try {
            if (captureThread != null) {
                captureThread.interrupt();
                captureThread.join(300);
            }
        } catch (InterruptedException ignored) {
        }
        captureThread = null;

        if (line != null) {
            try {
                line.stop();
            } catch (Throwable ignored) {}
            try {
                line.close();
            } catch (Throwable ignored) {}
            line = null;
        }
        updateStatus("idle");
        synchronized (blockDeque) { blockDeque.clear(); }
        Services.remove(this);
    }

    private void updateStatus(String s) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(s));
    }

    private AudioFormat canonicalFormat() {
        float sampleRate = WavConstants.S_RATE;
        int sampleSizeInBits = WavConstants.VALID_BITS; // 16
        int channels = WavConstants.STEREO; // 2
        boolean signed = true;
        boolean bigEndian = false; // WAV-style little-endian
        return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
    }



    /**
     * Capture loop: read JACK_BUFFER frames worth of bytes repeatedly, convert to float blocks,
     * push into blockDeque and when CHUNKS blocks collected assemble a Recording and call view.analyze().
     *
     * Change: when read returns zero bytes we now still create a zero-filled block and process it,
     * so analyze() is called periodically even when the device is silent. A short sleep prevents
     * a tight busy loop.
     */
    private void captureLoop() {
        final AudioFormat fmt = canonicalFormat();
        final int frameSize = fmt.getFrameSize(); // bytes per frame (e.g. 4 for stereo 16-bit)
        final int bytesPerBlock = WavConstants.JACK_BUFFER * frameSize;
        final byte[] readBuf = new byte[bytesPerBlock];
        final int chunksPerWindow = WavConstants.CHUNKS; // FFT_SIZE / JACK_BUFFER

        while (running && !Thread.currentThread().isInterrupted()) {
            int bytesRead = 0;
            try {
                while (bytesRead < readBuf.length) {
                    int r = line.read(readBuf, bytesRead, readBuf.length - bytesRead);
                    if (r <= 0) break;
                    bytesRead += r;
                }
            } catch (Exception e) {
                updateStatus("read error");
                break;
            }

            // Prepare output arrays (zero-filled by default)
            float[] left = new float[WavConstants.JACK_BUFFER];
            float[] right = new float[WavConstants.JACK_BUFFER];
            Arrays.fill(left, 0f);
            Arrays.fill(right, 0f);

            if (bytesRead > 0) {
                // Convert readBuf -> float[2][JACK_BUFFER]
                ByteBuffer bb = ByteBuffer.wrap(readBuf, 0, bytesRead);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                int samples = bytesRead / (sampleSizeInBytes(fmt));
                int framesRead = Math.min(WavConstants.JACK_BUFFER, samples / fmt.getChannels());

                for (int f = 0; f < framesRead; f++) {
                    int l = (fmt.getChannels() >= 1) ? bb.getShort() : 0;
                    int r = (fmt.getChannels() >= 2) ? bb.getShort() : l;
                    left[f] = shortToFloat((short) l);
                    right[f] = shortToFloat((short) r);
                }
                // If fewer frames than JACK_BUFFER, the tail is already zeroed
            } else {
                // No bytes read: produce one zero block but sleep a bit to avoid busy-looping
                try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
            }

            // append to deque to preserve a short history for other uses, but feed the fresh block
            synchronized (blockDeque) {
                blockDeque.addLast(new float[][] { left, right });
                // keep deque reasonable long (cap to chunksPerWindow * 4)
                while (blockDeque.size() > chunksPerWindow * 4) blockDeque.removeFirst();
            }

            // Feed this JACK-sized block into the provided analyzer (real-time entry point).
            // Analysis implementations (e.g., judahzone.fx.analysis.Analysis/Transformer) will
            // accumulate these blocks and perform FFT/RMS off the capture thread.
            if (analyzer != null) {
                try {
                    analyzer.process(left, right);
                } catch (Throwable t) {
                    // prevent analyzer exceptions from killing capture thread
                    t.printStackTrace();
                }
            }
        } // end while
        updateStatus("stopped");

    }
    private static int sampleSizeInBytes(AudioFormat fmt) {
        return (fmt.getSampleSizeInBits() + 7) / 8;
    }

    private static float shortToFloat(short s) {
        return s / (float) Short.MAX_VALUE;
    }

    @Override
    public void close() {
        stop();
    }

}