package jspectrumanalyzer.iq;

import java.util.Arrays;

/** Estimates downlink OVSF code occupancy after primary-cell descrambling. */
final class UmtsLoadEstimator {
	private static final int FRAME_CHIPS = 38400;
	private static final int SLOT_CHIPS = 2560;
	private static final int BLOCK_CHIPS = 256;

	private UmtsLoadEstimator() {}

	static UmtsSignalAnalyzer.LoadData measure(double[] inI, double[] inQ,
			UmtsSignalAnalyzer.Candidate cell) {
		if (inI == null || inQ == null || cell == null) return UmtsSignalAnalyzer.LoadData.EMPTY;
		long active = 0, total = 0;
		int blocks = 0;
		double[] re = new double[BLOCK_CHIPS], im = new double[BLOCK_CHIPS];
		double[] energies = new double[BLOCK_CHIPS];
		double omega = cell.cfoHz * 2d * Math.PI / UmtsSignalAnalyzer.CHIP_RATE;
		int frames = Math.min(4, Math.max(0, (inI.length - cell.frameStartSample) / FRAME_CHIPS));
		for (int frame = 0; frame < frames; frame++) {
			int frameStart = cell.frameStartSample + frame * FRAME_CHIPS;
			for (int slot = 0; slot < 15; slot++) {
				// The first 256 chips contain the unscripted SCH overlay and bias a code projection.
				for (int block = 1; block < 10; block++) {
					int start = frameStart + slot * SLOT_CHIPS + block * BLOCK_CHIPS;
					if (start < 0 || start + BLOCK_CHIPS > inI.length) continue;
					for (int chip = 0; chip < BLOCK_CHIPS; chip++) {
						int at = start + chip;
						double angle = -omega * (at - frameStart);
						double ca = Math.cos(angle), sa = Math.sin(angle);
						double ar = inI[at] * ca - inQ[at] * sa;
						double ai = inI[at] * sa + inQ[at] * ca;
						if (cell.iqOrientation < 0) ai = -ai;
						int relative = Math.floorMod(at - frameStart, FRAME_CHIPS);
						int[] sc = UmtsSignalAnalyzer.scramblingChip(cell.psc * 16, relative);
						re[chip] = ar * sc[0] + ai * sc[1];
						im[chip] = ai * sc[0] - ar * sc[1];
					}
					walshHadamard(re);
					walshHadamard(im);
					for (int code = 0; code < BLOCK_CHIPS; code++)
						energies[code] = re[code] * re[code] + im[code] * im[code];
					double[] sorted = energies.clone();
					Arrays.sort(sorted);
					double floor = (sorted[95] + sorted[127]) * .5d;
					double threshold = Math.max(1e-12, floor * 7d);
					for (int code = 0; code < BLOCK_CHIPS; code++) {
						// Walsh index 0 is P-CPICH; index 128 is P-CCPCH code 1.
						if (code == 0 || code == 128) continue;
						if (energies[code] > threshold) active++;
						total++;
					}
					blocks++;
				}
			}
		}
		return total == 0 ? UmtsSignalAnalyzer.LoadData.EMPTY
				: new UmtsSignalAnalyzer.LoadData(true, active * 100d / total, active, total, blocks);
	}

	private static void walshHadamard(double[] values) {
		for (int width = 1; width < values.length; width <<= 1) {
			for (int start = 0; start < values.length; start += width << 1) {
				for (int offset = 0; offset < width; offset++) {
					double left = values[start + offset], right = values[start + offset + width];
					values[start + offset] = left + right;
					values[start + offset + width] = left - right;
				}
			}
		}
	}
}
