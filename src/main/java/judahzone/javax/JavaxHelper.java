// file: judahzone/javax/JavaxHelper.java
package judahzone.javax;

/** Simple synchronous provider that enumerates Javax audio mixers and MIDI
 *  devices and hands lists of user-friendly names to a Ports.Consumer. */
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import judahzone.api.Ports;
import judahzone.api.Ports.Connect;
import judahzone.api.Ports.IO;
import judahzone.api.Ports.PortData;
import judahzone.api.Ports.Request;
import judahzone.api.Ports.Type;
import judahzone.api.Ports.Wrapper;

public class JavaxHelper implements Ports.Provider {

    @Override
    public void query(PortData consumer) {
        try {
            List<String> audio = listAudioPorts();
            List<String> midi = listMidiPorts();
            consumer.queried(List.copyOf(audio), List.copyOf(midi));
        } catch (Throwable t) {
            System.err.println("JavaxHelper: error enumerating ports: " + t.getMessage());
            consumer.queried(List.of(), List.of());
        }
    }

    private List<String> listAudioPorts() {
        List<String> out = new ArrayList<>();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        DataLine.Info want = new DataLine.Info(TargetDataLine.class, null);
        for (Mixer.Info inf : infos) {
            try {
                Mixer mixer = AudioSystem.getMixer(inf);
                // Prefer mixers that explicitly support capture TargetDataLine
                boolean supportsCapture = mixer.isLineSupported(want);
                if (!supportsCapture) {
                    // Fallback: any declared target lines likely means capture capability
                    supportsCapture = mixer.getTargetLineInfo() != null && mixer.getTargetLineInfo().length > 0;
                }
                if (supportsCapture) {
                    out.add(formatMixerInfo(inf));
                }
            } catch (Throwable ignored) {
                // conservative: ignore failing mixers
            }
        }
        if (out.isEmpty()) {
            // As a final fallback expose all mixers so UI is not empty
            for (Mixer.Info inf : infos) out.add(formatMixerInfo(inf));
        }
        return out;
    }

    private List<String> listMidiPorts() {
        List<String> out = new ArrayList<>();
        javax.sound.midi.MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        for (javax.sound.midi.MidiDevice.Info inf : infos) {
            try {
                MidiDevice dev = MidiSystem.getMidiDevice(inf);
                int maxReceivers = dev.getMaxReceivers();
                // Devices that can *receive* MIDI are suitable targets for sending MIDI out
                if (maxReceivers != 0) {
                    out.add(formatMidiInfo(inf));
                }
            } catch (Throwable ignored) {
                // ignore devices that fail to open/probe
            }
        }
        if (out.isEmpty()) {
            for (javax.sound.midi.MidiDevice.Info inf : infos) out.add(formatMidiInfo(inf));
        }
        return out;
    }

    private static String formatMixerInfo(Mixer.Info inf) {
        String vendor = inf.getVendor();
        return inf.getName() + (vendor == null || vendor.isBlank() ? "" : " (" + vendor + ")");
    }

    private static String formatMidiInfo(javax.sound.midi.MidiDevice.Info inf) {
        String desc = inf.getDescription();
        return inf.getName() + (desc == null || desc.isBlank() ? "" : " - " + desc);
    }

	@Override
	public void register(Request req) {
		// No-op javax doesn't need to register
	}

	@Override
	public void unregister(Request reg, Wrapper wrap) {
		// no-op javax doesn't need to unregister
	}

	@Override
	public void connect(Connect con) {
		// TODO see JavaxIn, connect port...
	}

	@Override
	public Wrapper registerNow(Type type, IO io, String portName) throws Exception {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void connectNow(Object ours, Type type, String portName) throws Exception {
		// TODO Auto-generated method stub
	}

}
