package jspectrumanalyzer.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.function.Consumer;

/**
 * Reader for .hsr files created by SpectrumRecorder. Delivers FFTBins to provided consumer
 * trying to honor original timestamps (playbackSpeed=1.0 means real-time).
 */
public class SpectrumPlayer {
    private Thread playThread;
    private volatile boolean running = false;

    public void start(File file, Consumer<FFTBins> consumer, double playbackSpeed) throws Exception {
        stop();
        running = true;
        playThread = new Thread(() -> {
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                byte[] magic = new byte[4];
                dis.readFully(magic);
                String mag = new String(magic, "UTF-8");
                if (!"HSR1".equals(mag)) throw new IllegalArgumentException("Not an HSR file");
                byte version = dis.readByte();
                long recordingStart = dis.readLong();
                long prevTs = -1;
                while (running) {
                    long sweepTs;
                    try {
                        sweepTs = dis.readLong();
                    } catch (Exception e) {
                        break; // EOF
                    }
                    int freqCount = dis.readInt();
                    double[] freqStart = null;
                    if (freqCount > 0) {
                        freqStart = new double[freqCount];
                        for (int i = 0; i < freqCount; i++) freqStart[i] = dis.readDouble();
                    }
                    float fftBinWidthHz = dis.readFloat();
                    int binCount = dis.readInt();
                    float[] sig = new float[binCount];
                    for (int i = 0; i < binCount; i++) {
                        short q = dis.readShort();
                        sig[i] = q / 100f;
                    }
                    FFTBins bins = new FFTBins(true, freqStart, fftBinWidthHz, sig);
                    // sleep to honor timestamp difference
                    if (prevTs >= 0) {
                        long diff = sweepTs - prevTs;
                        if (diff > 0) {
                            long toSleep = (long) (diff / playbackSpeed);
                            try { Thread.sleep(Math.max(0, toSleep)); } catch (InterruptedException ie) { break; }
                        }
                    }
                    prevTs = sweepTs;
                    consumer.accept(bins);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                running = false;
            }
        });
        playThread.setName("SpectrumPlayer");
        playThread.start();
    }

    public void stop() {
        running = false;
        if (playThread != null) {
            playThread.interrupt();
            try { playThread.join(500); } catch (InterruptedException e) {}
            playThread = null;
        }
    }

    public boolean isPlaying() { return running; }
}
