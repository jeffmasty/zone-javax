package judahzone.javax;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import judahzone.api.MidiClock;
import judahzone.util.WavConstants;

/**
 * JavaxGraph — a substitute JACK-like scheduler for JavaSound.
 *
 * Purpose
 * - Provide a lightweight scheduler that calls registered audio producers at a steady rate
 *   corresponding to WavConstants.JACK_BUFFER frames at WavConstants.S_RATE.
 * - Emulate JACK's behavior where synths/sample-players "produce" whenever the process
 *   callback tells them to. Producers mix their output into the provided left/right buffers.
 * - Optionally notify a MidiClock once per audio cycle by calling MidiClock.pulse().
 *
 * Design / API
 * - Producers produce/mix into per-cycle float[] buffers:
 *     interface AudioProducer { void process(float[] left, float[] right, int frames); }
 *   Register producers with registerProducer(...). Producers should add/mix their output
 *   into the buffers (i.e. accumulate samples).
 *
 * - Consumers (optional) receive the already-mixed buffers after all producers have run:
 *     interface AudioConsumer { void consume(float[] left, float[] right, int frames); }
 *   Register consumers with registerConsumer(...). Typical consumer: an output bridge that
 *   converts floats -> PCM and writes to SourceDataLine (e.g. JavaxOut as consumer).
 *
 * - Timing uses a dedicated high-priority daemon thread. Scheduling uses sleep + spin to
 *   approximate sub-millisecond timing; this is best-effort and must be tested/tuned on
 *   target platforms.
 *
 * Notes & recommendations
 * - Producers must be realtime-friendly: avoid blocking, allocation, or heavy locks inside process().
 *   Pre-allocate any temporary buffers outside the process() call.
 * - The left/right buffers are reused each cycle; a producer that needs to retain data must copy it.
 * - The scheduler calls MidiClock.pulse() exactly once per audio cycle if a MidiClock was supplied.
 */
public class JavaxGraph {

    /**
     * Called by producers to fill/mix into the per-cycle buffers.
     */
    public interface AudioProducer {
        /**
         * Called once per cycle. Implementations should mix their output into the provided
         * left/right arrays for the first 'frames' elements.
         *
         * @param left   float array length >= frames
         * @param right  float array length >= frames
         * @param frames number of frames to produce (== WavConstants.JACK_BUFFER)
         */
        void process(float[] left, float[] right, int frames);
    }

    /**
     * Called after producers have run; consumers typically convert and forward to audio output.
     */
    public interface AudioConsumer {
        void consume(float[] left, float[] right, int frames);
    }

    private final List<AudioProducer> producers = new CopyOnWriteArrayList<>();
    private final List<AudioConsumer> consumers = new CopyOnWriteArrayList<>();

    private final int framesPerCycle = WavConstants.JACK_BUFFER;
    private final double sampleRate = WavConstants.S_RATE;
    private final long nanosPerCycle;

    private volatile boolean running = false;
    private Thread thread;

    // Reused buffers for each cycle to avoid allocations
    private final float[] leftBuf;
    private final float[] rightBuf;

    // Optional external clock that expects a pulse() called each audio process
    private final MidiClock midiClock;

    // Simple stats (can be expanded)
    private long cycles = 0;
    @SuppressWarnings("unused")
	private long startsAt = 0;

    /**
     * Construct a JavaxGraph scheduler.
     *
     * @param midiClock optional MidiClock; if non-null, midiClock.pulse() is invoked once per cycle.
     */
    public JavaxGraph(MidiClock midiClock) {
        this.midiClock = midiClock;
        this.leftBuf = new float[framesPerCycle];
        this.rightBuf = new float[framesPerCycle];
        // nanos per cycle = JACK_BUFFER / sampleRate seconds
        this.nanosPerCycle = (long) (1_000_000_000.0 * (framesPerCycle / sampleRate));
    }

    public JavaxGraph() {
        this(null);
    }

    /** Register a producer. Producers are invoked in registration order. */
    public void registerProducer(AudioProducer p) {
        if (p != null) producers.add(p);
    }

    public void unregisterProducer(AudioProducer p) {
        producers.remove(p);
    }

    /** Register a consumer that receives the mixed buffers after production. */
    public void registerConsumer(AudioConsumer c) {
        if (c != null) consumers.add(c);
    }

    public void unregisterConsumer(AudioConsumer c) {
        consumers.remove(c);
    }

    /** Start the scheduler thread. Safe to call multiple times (no-op if already running). */
    public synchronized void start() {
        if (running) return;
        running = true;
        cycles = 0;
        startsAt = System.currentTimeMillis();
        thread = new Thread(this::runLoop, "JavaxGraph-scheduler");
        thread.setDaemon(true);
        try { thread.setPriority(Thread.MAX_PRIORITY); } catch (Throwable ignored) {}
        thread.start();
    }

    /** Stop the scheduler and join the thread. */
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(500); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }

    /** True if scheduler is running. */
    public boolean isRunning() {
        return running;
    }

    /** Return number of cycles (process calls) since start. */
    public long getCycles() {
        return cycles;
    }

    /** Simple run-loop implementing sleep + spin scheduler. */
    private void runLoop() {
        final long spinThresholdNanos = 200_000; // 0.2 ms busy-spin threshold
        long next = System.nanoTime();

        while (running && !Thread.currentThread().isInterrupted()) {
            // compute next wakeup time
            next += nanosPerCycle;

            // zero buffers
            clearBuffers();

            // Call producers to mix into left/right
            for (AudioProducer p : producers) {
                try {
                    p.process(leftBuf, rightBuf, framesPerCycle);
                } catch (Throwable t) {
                    // swallow exceptions to avoid killing scheduler; log or handle as appropriate
                    t.printStackTrace();
                }
            }

            // Notify MidiClock (emulate jack frame/pulse)
            if (midiClock != null) {
                try {
                    midiClock.pulse();
                } catch (Throwable t) { t.printStackTrace(); }
            }

            // Call consumers with the mixed output
            for (AudioConsumer c : consumers) {
                try {
                    c.consume(leftBuf, rightBuf, framesPerCycle);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            cycles++;

            // Sleep until next cycle: sleep for most of the remaining time, then spin/yield
            long now = System.nanoTime();
            long sleepNanos = next - now - spinThresholdNanos;
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // busy-wait until the exact next time
            while (System.nanoTime() < next) {
                Thread.yield();
            }
        }
    }

    private void clearBuffers() {
        // fast clear
        for (int i = 0; i < framesPerCycle; i++) {
            leftBuf[i] = 0f;
            rightBuf[i] = 0f;
        }
    }

}


/*
outline of what a “heavyweight” JavaSound scheduler would look like if you decide you need it.

High-level contrast

Goals

Lightweight: keep it simple, portable, easy to reason about. Best‑effort timing using Java timers + sleep/spin. Low implementation complexity. Good for prototyping, moderate-latency apps, or where external real‑time guarantees (JACK/ASIO) are not required.
Heavyweight: aim for deterministic, sample‑accurate scheduling, minimal jitter, low latency, and robust handling of complex graphs (many nodes, routing, parameter automation). Usually involves lock‑free structures, tight real‑time loops, careful memory management, possibly native hooks.
Timing model

Lightweight: periodic thread using System.nanoTime() + Thread.sleep + yield/spin. Schedules whole JACK_BUFFER blocks at roughly the right interval.
Heavyweight: driven by an OS/audio callback (preferred) or a carefully tuned high-priority realtime thread with busy-spin and high-resolution timers, often combined with sample-accurate event scheduling within each block.
Threading and blocking

Lightweight: producers/consumers run on scheduler thread; small GC-safe arrays reused. Accepts occasional blocking if code is well behaved.
Heavyweight: strict separation — realtime thread must never block, allocate, or lock contended resources. Use SPSC ring buffers, preallocated memory, atomic flags. Background threads handle non-realtime tasks (loading disk, UI).
Memory and allocation

Lightweight: acceptable short-lived allocations if rare; uses reused buffers where practical.
Heavyweight: no allocations on realtime path; use pooled, preallocated buffers (off-heap or preallocated on heap and reused) to avoid GC pauses.
APIs and graph features

Lightweight: simple producer/consumer callbacks that mix into shared buffers.
Heavyweight: full audio processing graph with nodes, per-node buffers, sample-accurate parameter automation/event queues, dynamic connect/disconnect with lock-free synchronization or staged graph rebuild on guardened thread.
Accuracy

Lightweight: block-accurate (per JACK_BUFFER) and best-effort timing. Good enough for many apps.
Heavyweight: sample-accurate scheduling within a block (you can schedule a note to start at sample N inside block M).
Complexity & maintenance

Lightweight: small codebase, easy to debug/test.
Heavyweight: much more complex, needs disciplined coding and testing; likely needs platform-specific tuning.
When to prefer heavyweight

You must guarantee low jitter or sample-accurate timing (e.g., synths with tight transient timing, step-based sequencing with sub-block timing).
You have many producers/consumers and need scalability without contention/GC interference.
You’re integrating with hardware or external clocks and require sample-accurate sync/transport.
You’re targeting pro audio use where underruns/latency must be minimized and predictable.
When lightweight is fine

Moderate-latency playback/production where block accuracy (JACK_BUFFER) is acceptable.
Prototyping, simple synths and sample players, UI-driven apps where occasional jitter is tolerable.
When you want cross-platform portability without platform specific tuning.
Concrete differences (quick checklist)

Scheduler

Lightweight: single scheduler thread using sleep + spin.
Heavyweight: run in the audio callback (if available), or use dedicated realtime thread with strict rules.
Data movement

Lightweight: producers mix directly into shared float[] per cycle.
Heavyweight: producers render into private preallocated slices (or circular buffers). Use ring buffers to transfer between threads. Avoid shared mutable arrays unless ownership rules are strict.
Event scheduling

Lightweight: schedule at block boundaries.
Heavyweight: maintain an event queue with sample-offset timestamps; process events at the appropriate sample index inside the block.
Graph changes

Lightweight: CopyOnWrite lists or simple add/remove; safe but can be costly.
Heavyweight: staged graph reconfiguration: modify a pending graph structure in background, then atomically swap into realtime thread without locks.
Safety

Lightweight: allow try/catch and tolerate occasional exceptions.
Heavyweight: realtime thread must not throw or block; exceptions are handled in background threads and reported to UI.
Design outline for a heavyweight JavaSound scheduler

Core ideas
Realtime thread (consumer) does only:
Acquire next output buffer (a preallocated float[] or direct ByteBuffer)
Pull from per-producer ring buffers or call a sample-accurate render function that reads from prepopulated buffers
Convert to PCM and write to SourceDataLine (or write via native callback)
Non-realtime threads do:
Graph changes
Disk I/O, sample loading
MIDI handling and event queuing (they push timestamped events into a lock-free event queue)
Components
Node: DSP unit with methods:
void render(float[] outL, float[] outR, int frames, long startSample) // sample-accurate param
Event queue: timestamped events (absolute sample frame). Implement as a lock-free priority ring or bucketed staging buffer for current window.
Scheduler:
Driven by audio callback or high-priority thread.
For each block:
Process events whose timestamp ∈ [blockStart, blockEnd] and apply them at sample-accurate offsets
Call each node.render(...) with startSample parameter
Buffer ownership:
Each node writes into its own preallocated output buffer slice; a central mixer sums slices using vectorized loops (or the nodes mix directly into a dedicated mix buffer, if ownership is clear).
Graph reconfiguration:
Build new graph snapshot off-thread, atomically swap pointer to snapshot in realtime thread.
I/O & loading:
Preload samples to memory/streamed buffers accessible to realtime thread (no blocking).
Real-time safety rules
No synchronized blocks or blocking I/O on realtime thread.
No allocations on realtime path.
Use volatile/atomic swaps for small control ops.
Handle underruns gracefully, and expose metrics.
Example APIs (sketch)
interface RealtimeNode { void render(int frames, float[] outL, float[] outR, long startSample); }
class Event { long sampleFrame; EventType type; Object payload; }
class RealtimeGraph { RealtimeNode[] nodes; void renderBlock(int frames, long blockStart); }
Migration steps from JavaxGraph to heavyweight

Identify hard realtime requirements: sample-accurate events? large graph? many nodes?
Convert existing producers to RealtimeNode API (render with startSample).
Introduce a lock-free event queue (bounded) and background MIDI/Event feeder that posts timestamped events.
Implement staged graph snapshots and swap in the realtime thread.
Replace scheduler with audio-callback-driven loop (if possible) or high-priority thread closely coupled to SourceDataLine available/driver behavior.
Test extensively for underruns, GC pauses, and event timing.
Provide diagnostics: underruns, blocking durations, queue fill levels.
Practical notes for Java/JVM

Pure Java cannot achieve audio-driver-callback semantics as cleanly as C/C++ with ASIO/JACK, but you can closely approach it:
Use SourceDataLine with small buffer and the hybrid scheduling we added, but ensure realtime rules on the consumer.
Consider using JNI/native code or libraries (JACK via JNA/JNAJack) if you need driver-level callbacks or absolute low latency.
On JVM, reduce GC interference by preallocating and avoiding allocation in realtime path. Consider using off-heap buffers (ByteBuffer.allocateDirect) for PCM conversion if necessary.
When heavy is overkill

If you only need moderately lower latency and a reliable blocking-write loop suffices after a few optimizations (reuse buffers, reduce SourceDataLine buffer), there’s no need for full heavyweight complexity.
Recommendation

Start lightweight and instrument:
Add counters for underruns, available() behavior, write latencies.
Replace per-segment allocations with preallocated buffers (we already did in JavaxOut).
Try JACK_BUFFER=256 and tune LINE_BUFFER_MULTIPLIER.
*/