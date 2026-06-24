package jspectrumanalyzer.iq;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

public class IQAudioOutput {
	public enum Mode {
		OFF("Off"),
		AM("AM/OOK"),
		FM("FM/NFM");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public static final int TARGET_AUDIO_RATE_HZ = 48_000;
	private static final int MAX_DEMOD_INPUT_RATE_HZ = 192_000;
	private static final int MAX_QUEUE_BLOCKS = 48;
	private static final double START_FADE_SECONDS = 0.35d;
	private static final double AM_DC_CUTOFF_HZ = 3d;
	private static final double AUDIO_DC_CUTOFF_HZ = 12d;
	public static final int MIN_TONE_CUTOFF_HZ = 3_000;
	public static final int MAX_TONE_CUTOFF_HZ = 22_000;
	public static final int DEFAULT_TONE_CUTOFF_HZ = 16_000;

	private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE_BLOCKS);
	private SourceDataLine line;
	private Thread audioThread;
	private volatile boolean running = false;
	private volatile Mode mode = Mode.OFF;
	private volatile Pcm16AudioSink recorder;
	private int inputRateHz = 1;
	private int demodInputRateHz = 1;
	private int demodInputStride = 1;
	private int audioRateHz = TARGET_AUDIO_RATE_HZ;
	private double amDc = 0;
	private double audioLowPass = 0;
	private double audioDc = 0;
	private double amDcAlpha = 0.0001d;
	private double audioDcAlpha = 0.0001d;
	private volatile double audioLowPassAlpha = 0.2d;
	private double resampleAccumulator = 0;
	private double audioSamplesPerInputSample = 1;
	private volatile double volume = 0.8d;
	private volatile int toneCutoffHz = DEFAULT_TONE_CUTOFF_HZ;
	private int fadeSamplesRemaining = 0;
	private int fadeTotalSamples = 1;
	private boolean amDcInitialized = false;
	private boolean hasPreviousFmSample = false;
	private int previousFmI = 0;
	private int previousFmQ = 0;

	public synchronized void start(Mode mode, int inputRateHz) {
		stop();
		this.mode = mode == null ? Mode.OFF : mode;
		this.inputRateHz = Math.max(1, inputRateHz);
		this.demodInputStride = Math.max(1, (int) Math.ceil(this.inputRateHz / (double) MAX_DEMOD_INPUT_RATE_HZ));
		this.demodInputRateHz = Math.max(1, this.inputRateHz / this.demodInputStride);
		this.audioRateHz = TARGET_AUDIO_RATE_HZ;
		this.audioSamplesPerInputSample = TARGET_AUDIO_RATE_HZ / (double) this.demodInputRateHz;
		this.amDcAlpha = calculateOnePoleAlpha(AM_DC_CUTOFF_HZ, this.demodInputRateHz);
		this.audioDcAlpha = calculateOnePoleAlpha(AUDIO_DC_CUTOFF_HZ, this.demodInputRateHz);
		this.audioLowPassAlpha = calculateAudioLowPassAlpha(this.demodInputRateHz);
		this.resampleAccumulator = 0;
		this.fadeTotalSamples = Math.max(1, (int) Math.round(audioRateHz * START_FADE_SECONDS));
		this.fadeSamplesRemaining = this.mode == Mode.OFF ? 0 : this.fadeTotalSamples;
		this.amDc = 0;
		this.amDcInitialized = false;
		this.audioLowPass = 0;
		this.audioDc = 0;
		this.hasPreviousFmSample = false;
		queue.clear();
		if (this.mode == Mode.OFF) {
			return;
		}

		try {
			AudioFormat format = new AudioFormat(Encoding.PCM_SIGNED, audioRateHz, 16, 2, 4, audioRateHz, false);
			line = AudioSystem.getSourceDataLine(format);
			line.open(format, audioRateHz * 4);
			line.start();
			running = true;
			audioThread = new Thread(this::audioLoop, "iq-audio-output");
			audioThread.setDaemon(true);
			audioThread.start();
		} catch (Exception e) {
			System.err.println("Audio output failed: " + e.getMessage());
			mode = Mode.OFF;
			running = false;
			line = null;
		}
	}

	public synchronized void stop() {
		running = false;
		queue.clear();
		Thread thread = audioThread;
		audioThread = null;
		if (thread != null && thread != Thread.currentThread()) {
			thread.interrupt();
			try {
				thread.join(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (line != null) {
			line.stop();
			line.flush();
			line.close();
			line = null;
		}
		mode = Mode.OFF;
	}

	public void setVolumePercent(int volumePercent) {
		int clamped = Math.max(0, Math.min(100, volumePercent));
		if (clamped == 0) {
			volume = 0;
			return;
		}
		double normalized = clamped / 100d;
		volume = Math.pow(10d, (normalized - 1d) * 2d);
	}

	public void setToneCutoffHz(int cutoffHz) {
		toneCutoffHz = Math.max(MIN_TONE_CUTOFF_HZ, Math.min(MAX_TONE_CUTOFF_HZ, cutoffHz));
		audioLowPassAlpha = calculateAudioLowPassAlpha(demodInputRateHz);
	}

	public int getToneCutoffHz() {
		return toneCutoffHz;
	}

	public void setRecorder(Pcm16AudioSink recorder) {
		this.recorder = recorder;
	}

	public void acceptIQ(byte[] iqData, int length) {
		Mode activeMode = mode;
		if (!running || activeMode == Mode.OFF || iqData == null || length < 4) {
			return;
		}
		int samples = length / 2;
		int demodSamples = (samples + demodInputStride - 1) / demodInputStride;
		int outputSamples = Math.max(1, (int) Math.ceil(demodSamples * audioSamplesPerInputSample) + 4);
		byte[] pcm = new byte[outputSamples * 4];
		int out = 0;

		if (activeMode == Mode.AM) {
			out = demodAm(iqData, samples, pcm);
		} else if (activeMode == Mode.FM) {
			out = demodFm(iqData, samples, pcm);
		}

		if (out > 0) {
			if (out < pcm.length) {
				byte[] trimmed = new byte[out];
				System.arraycopy(pcm, 0, trimmed, 0, out);
				pcm = trimmed;
			}
			applyPlaybackVolumeAndFade(pcm, 0, pcm.length);
			Pcm16AudioSink activeRecorder = recorder;
			if (activeRecorder != null) {
				try {
					activeRecorder.writePcm16(pcm, 0, pcm.length);
				} catch (IOException e) {
					System.err.println("Audio recording failed: " + e.getMessage());
					recorder = null;
				}
			}
			if (!queue.offer(pcm)) {
				queue.poll();
				queue.offer(pcm);
			}
		}
	}

	private int demodAm(byte[] iqData, int samples, byte[] pcm) {
		int out = 0;
		if (!amDcInitialized) {
			amDc = estimateAmDc(iqData, samples);
			audioDc = 0;
			audioLowPass = 0;
			amDcInitialized = true;
		}
		for (int sample = 0; sample < samples; sample += demodInputStride) {
			int i = iqData[sample * 2];
			int q = iqData[sample * 2 + 1];
			double magnitude = Math.sqrt(i * i + q * q) / 181d;
			amDc += (magnitude - amDc) * amDcAlpha;
			double audio = (magnitude - amDc) * 6d;
			out = resampleAndWrite(pcm, out, audio);
		}
		return out;
	}

	private double estimateAmDc(byte[] iqData, int samples) {
		if (iqData == null || samples <= 0) {
			return 0;
		}
		double sum = 0;
		int count = 0;
		for (int sample = 0; sample < samples; sample += demodInputStride) {
			int i = iqData[sample * 2];
			int q = iqData[sample * 2 + 1];
			sum += Math.sqrt(i * i + q * q) / 181d;
			count++;
		}
		return count == 0 ? 0 : sum / count;
	}

	private int demodFm(byte[] iqData, int samples, byte[] pcm) {
		int out = 0;
		for (int sample = 0; sample < samples; sample += demodInputStride) {
			int i = iqData[sample * 2];
			int q = iqData[sample * 2 + 1];
			if (hasPreviousFmSample) {
				double cross = previousFmI * q - previousFmQ * i;
				double dot = previousFmI * i + previousFmQ * q;
				double audio = Math.atan2(cross, dot) / Math.PI * 1.8d;
				out = resampleAndWrite(pcm, out, audio);
			}
			previousFmI = i;
			previousFmQ = q;
			hasPreviousFmSample = true;
		}
		return out;
	}

	private int resampleAndWrite(byte[] pcm, int out, double audio) {
		audioDc += (audio - audioDc) * audioDcAlpha;
		audio -= audioDc;
		audioLowPass += (audio - audioLowPass) * audioLowPassAlpha;
		resampleAccumulator += audioSamplesPerInputSample;
		while (resampleAccumulator >= 1d) {
			out = writePcm(pcm, out, audioLowPass);
			resampleAccumulator -= 1d;
			if (out + 1 >= pcm.length) {
				break;
			}
		}
		return out;
	}

	private double calculateAudioLowPassAlpha(int inputRateHz) {
		double cutoffHz = toneCutoffHz;
		double maxUsefulCutoff = inputRateHz * 0.42d;
		if (cutoffHz > maxUsefulCutoff) {
			cutoffHz = maxUsefulCutoff;
		}
		if (cutoffHz < 100d) {
			cutoffHz = 100d;
		}
		return 1d - Math.exp(-2d * Math.PI * cutoffHz / Math.max(1d, inputRateHz));
	}

	private double calculateOnePoleAlpha(double cutoffHz, int inputRateHz) {
		return 1d - Math.exp(-2d * Math.PI * cutoffHz / Math.max(1d, inputRateHz));
	}

	private int writePcm(byte[] pcm, int out, double audio) {
		if (out + 3 >= pcm.length) {
			return out;
		}
		if (audio > 1) {
			audio = 1;
		} else if (audio < -1) {
			audio = -1;
		}
		short value = (short) Math.round(audio * 28000d);
		pcm[out++] = (byte) (value & 0xff);
		pcm[out++] = (byte) ((value >> 8) & 0xff);
		pcm[out++] = (byte) (value & 0xff);
		pcm[out++] = (byte) ((value >> 8) & 0xff);
		return out;
	}

	private void applyPlaybackVolumeAndFade(byte[] pcm, int offset, int length) {
		double activeVolume = volume;
		int end = Math.min(pcm.length, offset + length);
		for (int pos = offset; pos + 3 < end; pos += 4) {
			short value = (short) ((pcm[pos] & 0xff) | (pcm[pos + 1] << 8));
			double gain = activeVolume;
			if (fadeSamplesRemaining > 0) {
				double fade = (fadeTotalSamples - fadeSamplesRemaining) / (double) fadeTotalSamples;
				gain *= fade;
				fadeSamplesRemaining--;
			}
			int scaled = (int) Math.round(value * gain);
			if (scaled > Short.MAX_VALUE) {
				scaled = Short.MAX_VALUE;
			} else if (scaled < Short.MIN_VALUE) {
				scaled = Short.MIN_VALUE;
			}
			pcm[pos] = (byte) (scaled & 0xff);
			pcm[pos + 1] = (byte) ((scaled >> 8) & 0xff);
			pcm[pos + 2] = pcm[pos];
			pcm[pos + 3] = pcm[pos + 1];
		}
	}

	private void audioLoop() {
		while (running) {
			try {
				byte[] block = queue.take();
				SourceDataLine activeLine = line;
				if (activeLine != null) {
					activeLine.write(block, 0, block.length);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
