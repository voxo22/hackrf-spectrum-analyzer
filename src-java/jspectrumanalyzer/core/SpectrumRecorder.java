package jspectrumanalyzer.core;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple compact binary recorder for full-spectrum sweeps.
 * Format (all little-endian via DataOutputStream default big-endian but consistent across JVMs):
 * - 4 bytes ASCII magic: 'HSR1'
 * - byte version (1)
 * - long recordingStartEpochMs
 * Repeated records:
 * - long sweepTimestampEpochMs
 * - int freqStartCount
 * - freqStartCount * double (each start freq in MHz)
 * - float fftBinWidthHz
 * - int binCount
 * - binCount * short (quantized power in centi-dB: value = round(dBm * 100))
 */
public class SpectrumRecorder {
    private DataOutputStream dos;
    private File file;

    public void start(File outFile) throws IOException {
        if (dos != null) stop();
        this.file = outFile;
        FileOutputStream fos = new FileOutputStream(outFile);
        dos = new DataOutputStream(new BufferedOutputStream(fos));
        // header
        dos.writeBytes("HSR1");
        dos.writeByte(1); // version
        dos.writeLong(System.currentTimeMillis());
        dos.flush();
    }

    public synchronized void writeSweep(FFTBins bins) throws IOException {
        if (dos == null) return;
        long ts = System.currentTimeMillis();
        dos.writeLong(ts);
        if (bins.freqStart == null) {
            dos.writeInt(0);
        } else {
            dos.writeInt(bins.freqStart.length);
            for (double f : bins.freqStart) {
                dos.writeDouble(f);
            }
        }
        dos.writeFloat(bins.fftBinWidthHz);
        if (bins.sigPowdBm == null) {
            dos.writeInt(0);
        } else {
            dos.writeInt(bins.sigPowdBm.length);
            for (float v : bins.sigPowdBm) {
                // quantize to centi-dB and store as signed 16-bit
                int q = Math.round(v * 100f);
                if (q > Short.MAX_VALUE) q = Short.MAX_VALUE;
                if (q < Short.MIN_VALUE) q = Short.MIN_VALUE;
                dos.writeShort((short) q);
            }
        }
        // flush occasionally
        dos.flush();
    }

    public synchronized void stop() throws IOException {
        if (dos != null) {
            try {
                dos.flush();
            } finally {
                try { dos.close(); } catch (Exception e) {}
                dos = null;
            }
        }
    }

    public boolean isRecording() {
        return dos != null;
    }

    public File getFile() { return file; }
}
