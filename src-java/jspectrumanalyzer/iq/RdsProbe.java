package jspectrumanalyzer.iq;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

final class RdsProbe {
	private static final int DEFAULT_SAMPLE_RATE = 250_000;

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: RdsProbe <file.pcm|file.wav> [sample-rate] [max-seconds]");
			System.exit(2);
		}
		int sampleRate = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_SAMPLE_RATE;
		double maxSeconds = args.length >= 3 ? Double.parseDouble(args[2]) : 0d;
		for (String path : args[0].split(";")) run(new File(path), sampleRate, maxSeconds);
	}

	private static void run(File file, int sampleRate, double maxSeconds) throws Exception {
		RdsSignalAnalyzer analyzer = new RdsSignalAnalyzer();
		String lastDecoded = "";
		RdsSignalAnalyzer.Result lastResult = RdsSignalAnalyzer.Result.empty("not run");
		try (IqInput input = openInput(file, sampleRate)) {
			int chunkBytes = (input.sampleRate / 5) * input.frameBytes;
			byte[] history = new byte[input.sampleRate * input.frameBytes * 2];
			int historyLength = 0;
			byte[] buffer = new byte[chunkBytes];
			for (;;) {
				int got = input.read(buffer);
				if (got < 0) break;
				got &= ~1;
				int keep = Math.min(got, history.length);
				if (historyLength + keep > history.length) {
					int drop = historyLength + keep - history.length;
					System.arraycopy(history, drop, history, 0, historyLength - drop);
					historyLength -= drop;
				}
				System.arraycopy(buffer, got - keep, history, historyLength, keep);
				historyLength += keep;
				RdsSignalAnalyzer.Result r = analyzer.analyze(history, historyLength, input.sampleRate);
				lastResult = r;
				String decoded = decodedSummary(r);
				if (!decoded.equals(lastDecoded) && (r.windowGroups > 0 || r.pi >= 0
						|| r.programService.length() > 0 || r.radioText.length() > 0)) {
					String line = String.format(Locale.US, "%6.2fs %-30s %s win=%d/%d corr=%d/%d Q=%.0f",
							input.secondsRead(), r.state, decoded,
							r.windowBlocks, r.windowGroups, r.windowCorrectedBits, r.possibleBlocks, r.quality);
					System.out.println(file.getName() + "  " + line);
					lastDecoded = decoded;
				}
				if (maxSeconds > 0d && input.secondsRead() >= maxSeconds) break;
			}
		}
		System.out.println(file.getName() + "  FINAL " + decodedSummary(lastResult)
				+ String.format(Locale.US, " total=%d/%d corr=%d/%d Q=%.0f", lastResult.validBlocks,
						lastResult.totalGroups, lastResult.windowCorrectedBits,
						lastResult.possibleBlocks, lastResult.quality));
	}

	private static String decodedSummary(RdsSignalAnalyzer.Result r) {
		return String.format(Locale.US,
				"PS='%-8s' Country='%s' Coverage='%s' Audio='%s' Mode='%s' PTY='%s' AF='%s' PIN='%s' RT+='%s' TMC='%s' EON='%s' Clock='%s' RT='%s'",
				r.programService, r.countryName, r.coverageAreaName,
				r.audioMode, r.programmeMode, r.pty >= 0 ? r.ptyName : "--",
				r.alternativeFrequencies, r.programmeItem.length() == 0 ? "--" : r.programmeItem,
				r.rtPlusStatus, r.tmcStatus, r.eonStatus,
				r.clockText.length() == 0 ? "--" : r.clockText, r.radioText);
	}

	private static IqInput openInput(File file, int fallbackSampleRate) throws IOException {
		String name = file.getName().toLowerCase(Locale.US);
		if (name.endsWith(".wav")) return openWavInput(file, fallbackSampleRate);
		return new IqInput(new FileInputStream(file), fallbackSampleRate, 2, false, Long.MAX_VALUE);
	}

	private static IqInput openWavInput(File file, int fallbackSampleRate) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(file, "r");
		try {
			if (raf.length() < 44 || readFourCc(raf) != 0x46464952 || readLe32(raf) < 0
					|| readFourCc(raf) != 0x45564157) {
				throw new IOException("Unsupported WAV header: " + file);
			}
			int channels = 0, bits = 0, rate = fallbackSampleRate, blockAlign = 0;
			long dataOffset = -1L, dataLength = -1L;
			while (raf.getFilePointer() + 8 <= raf.length()) {
				int chunkId = readFourCc(raf);
				long chunkSize = readLe32(raf) & 0xffffffffL;
				long chunkData = raf.getFilePointer();
				if (chunkId == 0x20746d66) {
					int format = readLe16(raf);
					channels = readLe16(raf);
					rate = readLe32(raf);
					readLe32(raf);
					blockAlign = readLe16(raf);
					bits = readLe16(raf);
					if (format != 1 || channels != 2 || bits != 8 || blockAlign != 2)
						throw new IOException("Only 8-bit stereo PCM WAV IQ is supported: " + file);
				} else if (chunkId == 0x61746164) {
					dataOffset = chunkData;
					dataLength = chunkSize;
					break;
				}
				raf.seek(chunkData + chunkSize + (chunkSize & 1L));
			}
			if (dataOffset < 0) throw new IOException("Missing WAV data chunk: " + file);
			FileInputStream in = new FileInputStream(file);
			skipFully(in, dataOffset);
			return new IqInput(in, rate, blockAlign == 0 ? 2 : blockAlign, true, dataLength);
		} finally {
			raf.close();
		}
	}

	private static int readFourCc(RandomAccessFile raf) throws IOException {
		int b0 = raf.read();
		int b1 = raf.read();
		int b2 = raf.read();
		int b3 = raf.read();
		if ((b0 | b1 | b2 | b3) < 0) throw new IOException("Unexpected EOF");
		return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
	}

	private static int readLe16(RandomAccessFile raf) throws IOException {
		int b0 = raf.read();
		int b1 = raf.read();
		if ((b0 | b1) < 0) throw new IOException("Unexpected EOF");
		return b0 | (b1 << 8);
	}

	private static int readLe32(RandomAccessFile raf) throws IOException {
		int b0 = raf.read();
		int b1 = raf.read();
		int b2 = raf.read();
		int b3 = raf.read();
		if ((b0 | b1 | b2 | b3) < 0) throw new IOException("Unexpected EOF");
		return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
	}

	private static void skipFully(FileInputStream in, long bytes) throws IOException {
		long remaining = bytes;
		while (remaining > 0) {
			long skipped = in.skip(remaining);
			if (skipped <= 0) {
				if (in.read() < 0) throw new IOException("Unexpected EOF while skipping WAV header");
				skipped = 1;
			}
			remaining -= skipped;
		}
	}

	private static final class IqInput implements Closeable {
		private final FileInputStream in;
		private final boolean unsigned8;
		private long remainingData;
		private long readBytes;
		final int sampleRate;
		final int frameBytes;

		IqInput(FileInputStream in, int sampleRate, int frameBytes, boolean unsigned8, long remainingData) {
			this.in = in;
			this.sampleRate = sampleRate;
			this.frameBytes = frameBytes;
			this.unsigned8 = unsigned8;
			this.remainingData = remainingData;
		}

		int read(byte[] buffer) throws IOException {
			if (remainingData <= 0) return -1;
			int limit = (int) Math.min(buffer.length, remainingData);
			int got = in.read(buffer, 0, limit);
			if (got <= 0) return got;
			remainingData -= got;
			readBytes += got;
			if (unsigned8) {
				for (int i = 0; i < got; i++) buffer[i] = (byte) ((buffer[i] & 0xff) - 128);
			}
			return got;
		}

		double secondsRead() {
			return readBytes / (double) (sampleRate * frameBytes);
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
