package jspectrumanalyzer.iq;

import java.io.FileInputStream;

/** Manual raw-IQ probe used while validating the lightweight FIC decoder. */
public final class DabFicFileProbe {
	public static void main(String[] args) throws Exception {
		if (args.length == 0) throw new IllegalArgumentException("raw IQ file path required");
		DabFicDecoder decoder = new DabFicDecoder();
		DabFicDecoder.Result lastResult = null;
		byte[] block = new byte[500_000];
		int index = 0;
		try (FileInputStream input = new FileInputStream(args[0])) {
			if (args.length > 1 && "scan".equals(args[1])) {
				int read = readFully(input, block);
				long power = 0; int peak = 0;
				for (int i = 0; i < read; i++) { int v = block[i]; power += v * v; peak = Math.max(peak, Math.abs(v)); }
				System.out.printf("signed RMS %.2f, peak %d%n", Math.sqrt(power / (double)read), peak);
				for (int rate = 1_900_000; rate <= 2_100_000; rate += 2_000) {
					DabFicDecoder.Result result = new DabFicDecoder().analyze(block, read, rate);
					if (result.validFibs > 0) System.out.printf("rate %d: FIB %d/12, EId %04X, '%s'%n",
							rate, result.validFibs, result.ensembleId, result.ensembleLabel);
				}
				return;
			}
			for (int read; (read = readFully(input, block)) >= 100_000 && index < 40; index++) {
				DabFicDecoder.Result result = decoder.analyze(block, read, 2_000_000);
				lastResult = result;
				System.out.printf("%03d: %s, FIB %d/%d, rolling %.1f%%, EId %04X, country %s, UTC '%s', '%s', services %d%n",
						index, result.state, result.validFibs, result.attemptedFibs, result.rollingQuality,
						result.ensembleId, result.countryCode, result.utcTime, result.ensembleLabel,
						result.services.size());
			}
		}
		if (lastResult != null) for (DabFicDecoder.Service service : lastResult.services)
			System.out.printf("  %04X '%s' %s subch=%d bitrate=%d %s%n", service.id, service.label,
					service.componentType, service.subchannelId, service.bitrateKbps, service.protection);
	}

	private static int readFully(FileInputStream input, byte[] block) throws Exception {
		int total = 0;
		while (total < block.length) {
			int read = input.read(block, total, block.length - total);
			if (read < 0) break;
			total += read;
		}
		return total;
	}
}
