package jspectrumanalyzer.iq;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Offline carrier survey and per-channel SDS inventory for wideband IQ WAV files. */
public final class TetraWidebandProbe {
	private static final int FFT_SIZE = 8192;
	private static final int GRID_HZ = 25_000;
	private static final int CHANNEL_HALF_WIDTH_HZ = 10_500;

	private TetraWidebandProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: TetraWidebandProbe [--scan-only] [--all-grid] "
					+ "[--duration seconds] [--frequency Hz] <IQ.wav> [...]");
			System.exit(2);
		}
		boolean scanOnly = false, allGrid = false;
		long durationMillis = Long.MAX_VALUE;
		List<Long> requested = new ArrayList<Long>();
		List<File> files = new ArrayList<File>();
		for (int n = 0; n < args.length; n++) {
			String value = args[n];
			if ("--scan-only".equals(value)) scanOnly = true;
			else if ("--all-grid".equals(value)) allGrid = true;
			else if ("--duration".equals(value) && n + 1 < args.length)
				durationMillis = Math.max(1000L,
						Math.round(Double.parseDouble(args[++n]) * 1000d));
			else if ("--frequency".equals(value) && n + 1 < args.length)
				requested.add(Long.parseLong(args[++n]));
			else files.add(new File(value));
		}
		for (File file : files) analyze(file, scanOnly, allGrid, durationMillis, requested);
	}

	private static void analyze(File file, boolean scanOnly, boolean allGrid,
			long durationMillis, List<Long> requested) throws Exception {
		Survey survey;
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			survey = survey(replay);
			System.out.printf(Locale.US, "%n=== %s ===%ncenter %.6f MHz, %.3f MS/s, %.3f s%n",
					file.getName(), replay.getCenterFrequencyHz() / 1e6,
					replay.getSampleRateHz() / 1e6, replay.getDurationMillis() / 1000d);
			System.out.printf(Locale.US, "25 kHz grid median %.1f dB; detected carriers:%n",
					survey.medianDb);
			for (ChannelPower channel : survey.channels)
				System.out.printf(Locale.US, "  %.6f MHz  %+.1f dB over median%s%n",
						channel.frequencyHz / 1e6, channel.db - survey.medianDb,
						channel.active ? "  candidate" : "");
		}
		if (scanOnly) return;

		List<Long> frequencies = new ArrayList<Long>();
		if (!requested.isEmpty()) frequencies.addAll(requested);
		else for (ChannelPower channel : survey.channels)
			if (allGrid || channel.active) frequencies.add(channel.frequencyHz);
		for (long frequency : frequencies)
			decode(file, frequency, durationMillis);
	}

	private static Survey survey(IQReplayFile replay) throws Exception {
		int rate = replay.getSampleRateHz();
		long duration = replay.getDurationMillis();
		int snapshots = (int)Math.max(8L, Math.min(48L, duration / 1000L));
		double[] accumulated = new double[FFT_SIZE];
		byte[] iq = new byte[FFT_SIZE * 2];
		double[] real = new double[FFT_SIZE], imag = new double[FFT_SIZE];
		for (int shot = 0; shot < snapshots; shot++) {
			long at = Math.round((shot + .5d) * Math.max(0L, duration - 20L)
					/ snapshots);
			replay.seekMillis(at);
			replay.readLoopedSigned(iq);
			double meanI = 0d, meanQ = 0d;
			for (int n = 0; n < FFT_SIZE; n++) {
				meanI += iq[n * 2]; meanQ += iq[n * 2 + 1];
			}
			meanI /= FFT_SIZE; meanQ /= FFT_SIZE;
			for (int n = 0; n < FFT_SIZE; n++) {
				double window = .5d - .5d * Math.cos(2d * Math.PI * n / (FFT_SIZE - 1));
				real[n] = (iq[n * 2] - meanI) * window;
				imag[n] = (iq[n * 2 + 1] - meanQ) * window;
			}
			fft(real, imag);
			for (int bin = 0; bin < FFT_SIZE; bin++)
				accumulated[bin] += real[bin] * real[bin] + imag[bin] * imag[bin];
		}
		long inputCenter = replay.getCenterFrequencyHz();
		long low = inputCenter - rate / 2L + 16_000L;
		long high = inputCenter + rate / 2L - 16_000L;
		long first = Math.floorDiv(low + GRID_HZ - 1L, GRID_HZ) * GRID_HZ;
		List<ChannelPower> channels = new ArrayList<ChannelPower>();
		for (long frequency = first; frequency <= high; frequency += GRID_HZ) {
			double power = bandPower(accumulated, inputCenter, rate, frequency,
					CHANNEL_HALF_WIDTH_HZ) / snapshots;
			channels.add(new ChannelPower(frequency, 10d * Math.log10(Math.max(1e-30, power))));
		}
		double[] ordered = new double[channels.size()];
		for (int n = 0; n < ordered.length; n++) ordered[n] = channels.get(n).db;
		Arrays.sort(ordered);
		double median = ordered.length == 0 ? 0d : ordered[ordered.length / 2];
		for (ChannelPower channel : channels) channel.active = channel.db >= median + 4.5d;
		return new Survey(channels, median);
	}

	private static double bandPower(double[] spectrum, long inputCenter, int rate,
			long frequency, int halfWidth) {
		double sum = 0d; int count = 0;
		for (int bin = 0; bin < spectrum.length; bin++) {
			int signed = bin < spectrum.length / 2 ? bin : bin - spectrum.length;
			double at = inputCenter + signed * (double)rate / spectrum.length;
			if (Math.abs(at - frequency) <= halfWidth) { sum += spectrum[bin]; count++; }
		}
		return count == 0 ? 0d : sum / count;
	}

	private static void decode(File file, long frequency, long requestedDuration) throws Exception {
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			long duration = Math.min(requestedDuration, replay.getDurationMillis());
			long low = replay.getCenterFrequencyHz() - replay.getSampleRateHz() / 2L + 14_000L;
			long high = replay.getCenterFrequencyHz() + replay.getSampleRateHz() / 2L - 14_000L;
			if (frequency < low || frequency > high) return;
			TetraDecoderCore decoder = new TetraDecoderCore();
			Map<Integer, Integer> pidCounts = new HashMap<Integer, Integer>();
			Map<String, Payload> interesting = new LinkedHashMap<String, Payload>();
			int rate = replay.getSampleRateHz();
			int blockBytes = Math.max(65_536, Math.min(524_288, rate / 8 * 2)) & ~1;
			byte[] iq = new byte[blockBytes];
			long processed = 0L;
			long recordingStart = IQReplayFile.getRecordingStartEpochMillis(file);
			long started = System.nanoTime();
			while (processed < duration) {
				long remainingSamples = Math.max(1L, Math.round((duration - processed) * rate / 1000d));
				int wanted = (int)Math.min(iq.length, remainingSamples * 2L) & ~1;
				byte[] block = wanted == iq.length ? iq : new byte[wanted];
				int read = replay.readLoopedSigned(block);
				if (read <= 0) break;
				decoder.accept(replay.getCenterFrequencyHz(), frequency, rate, block, read,
						recordingStart + processed);
				for (TetraMacDecoder.SdsMessage message : decoder.takeSdsMessages()) {
					pidCounts.put(message.protocol, pidCounts.containsKey(message.protocol)
							? pidCounts.get(message.protocol) + 1 : 1);
					if (message.protocol == 192 || message.protocol == 209) {
						String key = message.protocol + ":" + message.dataHex();
						Payload payload = interesting.get(key);
						if (payload == null) interesting.put(key, new Payload(message));
						else payload.count++;
					}
				}
				processed += Math.round(read / 2d / rate * 1000d);
			}
			TetraDecoderCore.Result result = decoder.latest();
			List<Integer> pids = new ArrayList<Integer>(pidCounts.keySet());
			Collections.sort(pids);
			System.out.printf(Locale.US, "%n--- %.6f MHz --- %s%n",
					frequency / 1e6, result.systemInfo == null ? "no TETRA lock" : String.format(
							Locale.US, "MCC %d MNC %d CC %d", result.systemInfo.mcc,
							result.systemInfo.mnc, result.systemInfo.colourCode));
			System.out.printf(Locale.US, "%.3f s; BSCH %d, SCH/F %d, SDS %d, PIDs %s; CPU %.2f s%n",
					duration / 1000d, result.decodedBsch, result.decodedSchf,
					result.sdsMessages, formatCounts(pids, pidCounts),
					(System.nanoTime() - started) / 1e9);
			List<Payload> payloads = new ArrayList<Payload>(interesting.values());
			Collections.sort(payloads, new Comparator<Payload>() {
				@Override public int compare(Payload a, Payload b) {
					int pid = Integer.compare(a.message.protocol, b.message.protocol);
					return pid != 0 ? pid : Integer.compare(b.message.lengthBits,
							a.message.lengthBits);
				}
			});
			for (Payload payload : payloads) printPayload(payload);
		}
	}

	private static String formatCounts(List<Integer> pids, Map<Integer, Integer> counts) {
		if (pids.isEmpty()) return "-";
		StringBuilder text = new StringBuilder();
		for (int pid : pids) {
			if (text.length() != 0) text.append(", ");
			text.append(pid).append('x').append(counts.get(pid));
		}
		return text.toString();
	}

	private static void printPayload(Payload payload) {
		TetraMacDecoder.SdsMessage message = payload.message;
		System.out.printf(Locale.US, "  PID %d  bits %d  dst %d  src %d  repeated %d%n    %s%n",
				message.protocol, message.lengthBits, message.destination, message.source,
				payload.count, message.dataHex());
		List<String> text = textCandidates(message.userDataBits, 8);
		for (String candidate : text) System.out.println("    text? " + candidate);
	}

	private static List<String> textCandidates(int[] bits, int skipBits) {
		List<String> found = new ArrayList<String>();
		for (int shift = 0; shift < 8; shift++) {
			byte[] bytes = pack(bits, skipBits + shift, 8);
			StringBuilder run = new StringBuilder();
			for (byte raw : bytes) {
				int value = raw & 0xff;
				if (value >= 32 && value <= 126) run.append((char)value);
				else {
					if (run.length() >= 5) found.add("8-bit shift " + shift + ": " + run);
					run.setLength(0);
				}
			}
			if (run.length() >= 5) found.add("8-bit shift " + shift + ": " + run);
		}
		for (int shift = 0; shift < 7; shift++) {
			byte[] septets = pack(bits, skipBits + shift, 7);
			StringBuilder run = new StringBuilder();
			for (byte raw : septets) {
				int value = raw & 0x7f;
				if (value >= 32 && value <= 126) run.append((char)value);
				else {
					if (run.length() >= 7) found.add("7-bit shift " + shift + ": " + run);
					run.setLength(0);
				}
			}
			if (run.length() >= 7) found.add("7-bit shift " + shift + ": " + run);
		}
		return found;
	}

	private static byte[] pack(int[] bits, int start, int width) {
		int count = Math.max(0, (bits.length - start) / width);
		byte[] result = new byte[count];
		for (int n = 0; n < count; n++) {
			int value = 0;
			for (int bit = 0; bit < width; bit++)
				value = (value << 1) | (bits[start + n * width + bit] & 1);
			result[n] = (byte)value;
		}
		return result;
	}

	private static void fft(double[] real, double[] imag) {
		int size = real.length;
		for (int n = 1, j = 0; n < size; n++) {
			int bit = size >> 1;
			for (; (j & bit) != 0; bit >>= 1) j ^= bit;
			j ^= bit;
			if (n < j) {
				double value = real[n]; real[n] = real[j]; real[j] = value;
				value = imag[n]; imag[n] = imag[j]; imag[j] = value;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = -2d * Math.PI / length;
			double stepR = Math.cos(angle), stepI = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double wr = 1d, wi = 0d;
				for (int n = 0; n < length / 2; n++) {
					int left = start + n, right = left + length / 2;
					double rr = real[right] * wr - imag[right] * wi;
					double ri = real[right] * wi + imag[right] * wr;
					real[right] = real[left] - rr; imag[right] = imag[left] - ri;
					real[left] += rr; imag[left] += ri;
					double next = wr * stepR - wi * stepI;
					wi = wr * stepI + wi * stepR; wr = next;
				}
			}
		}
	}

	private static final class Survey {
		final List<ChannelPower> channels; final double medianDb;
		Survey(List<ChannelPower> channels, double medianDb) {
			this.channels = channels; this.medianDb = medianDb;
		}
	}
	private static final class ChannelPower {
		final long frequencyHz; final double db; boolean active;
		ChannelPower(long frequencyHz, double db) { this.frequencyHz = frequencyHz; this.db = db; }
	}
	private static final class Payload {
		final TetraMacDecoder.SdsMessage message; int count = 1;
		Payload(TetraMacDecoder.SdsMessage message) { this.message = message; }
	}
}
