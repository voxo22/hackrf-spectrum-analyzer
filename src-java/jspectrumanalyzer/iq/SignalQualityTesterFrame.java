package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

final class SignalQualityTesterFrame extends JFrame {
	private static final int MAX_SAMPLES = 4096;
	private static final int SPECTRUM_SIZE = 1024;
	private static final int DAB_BANDWIDTH_HZ = 1_536_000;
	private static final int DAB_BASE_SAMPLE_RATE_HZ = 2_048_000;
	private static final int DAB_MODE_I_NULL_SAMPLES = 2656;
	private static final int DAB_MODE_I_USEFUL_SAMPLES = 2048;
	private static final int DAB_MODE_I_GUARD_SAMPLES = 504;
	private static final int DAB_MODE_I_ACTIVE_CARRIERS = 1536;
	private static final int DAB_CONSTELLATION_CARRIER_STEP = 8;
	private static final int DAB_HISTORY_BYTES = 700_000;
	private static final long MIN_ANALYSIS_INTERVAL_NANOS = 50_000_000L;
	private static final long DAB_MIN_ANALYSIS_INTERVAL_NANOS = 100_000_000L;
	private static final Color PANEL_BG = Color.BLACK;
	private static final Color TEXT_FG = Color.WHITE;
	private static final Color MUTED_FG = new Color(0xdddddd);
	private static final Color GRID = new Color(0x2d2d2d);
	private static final Color POINT = new Color(0x55ccff);
	private static final Color QUALITY_GOOD = new Color(0x44cc44);
	private static final Color QUALITY_WARN = new Color(0xffcc33);
	private static final Color QUALITY_BAD = new Color(0xff5555);

	private final ScatterPanel scatterPanel = new ScatterPanel();
	private final SpectrumPanel spectrumPanel = new SpectrumPanel();
	private final DabConstellationPanel dabConstellationPanel = new DabConstellationPanel();
	private final QualityPanel qualityPanel = new QualityPanel();
	private final DabLockPanel dabLockPanel = new DabLockPanel();
	private final JLabel titleLabel;
	private final JLabel statsLabel = new JLabel("Waiting for IQ samples...");
	private final JLabel detailLabel = new JLabel("RAW quality waits for signal level, clipping, DC offset and stability");
	private final Timer repaintTimer;
	private final boolean dabMode;

	private volatile Snapshot snapshot;
	private volatile long lastAnalysisNanos = 0;
	private double emaDbfs = Double.NaN;
	private double stabilityDb = 0;
	private double dabLockEma = Double.NaN;
	private final double[] dabCarrierLevelEma =
			new double[DAB_MODE_I_ACTIVE_CARRIERS / DAB_CONSTELLATION_CARRIER_STEP];
	private final byte[] dabHistory = new byte[DAB_HISTORY_BYTES];
	private int dabHistoryLength = 0;

	SignalQualityTesterFrame(String mode, Runnable closedCallback) {
		super("Signal Quality Tester");
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		dabMode = "DAB".equals(mode);

		titleLabel = new JLabel(mode + " raw IQ monitor");
		titleLabel.setForeground(TEXT_FG);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
		statsLabel.setForeground(MUTED_FG);
		detailLabel.setForeground(MUTED_FG);

		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBackground(PANEL_BG);
		header.setBorder(new EmptyBorder(8, 10, 6, 10));
		header.add(titleLabel, BorderLayout.WEST);
		header.add(statsLabel, BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout(8, 4));
		footer.setBackground(PANEL_BG);
		footer.setBorder(new EmptyBorder(6, 10, 8, 10));
		JPanel qualityRows = new JPanel(new GridLayout(dabMode ? 2 : 1, 1, 0, 2));
		qualityRows.setBackground(PANEL_BG);
		qualityRows.add(qualityPanel);
		if (dabMode) {
			qualityRows.add(dabLockPanel);
		}
		footer.add(qualityRows, BorderLayout.NORTH);
		footer.add(detailLabel, BorderLayout.CENTER);

		scatterPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		spectrumPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		dabConstellationPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel plots = new JPanel(new GridLayout(1, 2, 6, 0));
		plots.setBackground(PANEL_BG);
		plots.setBorder(new EmptyBorder(0, 8, 0, 8));
		plots.add(scatterPanel);
		plots.add(dabMode ? dabConstellationPanel : spectrumPanel);

		add(header, BorderLayout.NORTH);
		add(plots, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);
		setSize(760, 420);

		repaintTimer = new Timer(100, e -> updateView());
		repaintTimer.start();
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				repaintTimer.stop();
				if (closedCallback != null) {
					closedCallback.run();
				}
			}
		});
	}

	void offerIQBlock(long centerFreqHz, int sampleRateHz, byte[] iqData, int length) {
		if (iqData == null || length < 2) {
			return;
		}
		int evenLength = Math.min(length, iqData.length) & ~1;
		int totalSamples = evenLength / 2;
		if (totalSamples <= 0) {
			return;
		}
		byte[] dabAnalysisData = iqData;
		int dabAnalysisLength = evenLength;
		if (dabMode) {
			dabAnalysisLength = appendDabHistory(iqData, evenLength);
			dabAnalysisData = dabHistory;
		}
		long now = System.nanoTime();
		long interval = dabMode ? DAB_MIN_ANALYSIS_INTERVAL_NANOS : MIN_ANALYSIS_INTERVAL_NANOS;
		if (now - lastAnalysisNanos < interval) {
			return;
		}
		lastAnalysisNanos = now;
		int sampleCount = Math.min(MAX_SAMPLES, totalSamples);
		int stride = Math.max(1, totalSamples / sampleCount);
		byte[] samples = new byte[sampleCount * 2];
		long sumPower = 0;
		long sumI = 0;
		long sumQ = 0;
		int clipped = 0;
		int peak = 0;
		int out = 0;
		for (int sample = 0; sample < totalSamples && out + 1 < samples.length; sample += stride) {
			int offset = sample * 2;
			int i = iqData[offset];
			int q = iqData[offset + 1];
			samples[out++] = (byte) i;
			samples[out++] = (byte) q;
			sumPower += i * i + q * q;
			sumI += i;
			sumQ += q;
			if (Math.abs(i) >= 126 || Math.abs(q) >= 126) {
				clipped++;
			}
			peak = Math.max(peak, Math.max(Math.abs(i), Math.abs(q)));
		}
		if (out != samples.length) {
			byte[] trimmed = new byte[out];
			System.arraycopy(samples, 0, trimmed, 0, out);
			samples = trimmed;
			sampleCount = out / 2;
		}
		double rms = sampleCount <= 0 ? 0 : Math.sqrt(sumPower / (sampleCount * 2d));
		double dbfs = rms <= 0 ? -120d : 20d * Math.log10(rms / 128d);
		double meanI = sampleCount <= 0 ? 0 : sumI / (double) sampleCount;
		double meanQ = sampleCount <= 0 ? 0 : sumQ / (double) sampleCount;
		double dcPercent = Math.sqrt(meanI * meanI + meanQ * meanQ) / 128d * 100d;
		double clippingPercent = sampleCount <= 0 ? 0 : clipped * 100d / sampleCount;
		double quality = calculateRawQuality(dbfs, clippingPercent, dcPercent);
		float[] spectrum = computeSpectrum(iqData, totalSamples);
		DabMetrics dabMetrics = dabMode ? computeDabMetrics(spectrum, sampleRateHz) : null;
		if (dabMetrics != null) {
			dabMetrics.constellation = computeDabConstellation(dabAnalysisData, dabAnalysisLength, sampleRateHz,
					dabMetrics);
		}
		snapshot = new Snapshot(centerFreqHz, sampleRateHz, samples, sampleCount, dbfs, peak, dcPercent,
				clippingPercent, stabilityDb, quality, spectrum, dabMetrics, System.currentTimeMillis());
	}

	private int appendDabHistory(byte[] iqData, int length) {
		int copyLength = Math.min(length, dabHistory.length) & ~1;
		if (copyLength <= 0) {
			return dabHistoryLength;
		}
		if (copyLength >= dabHistory.length) {
			System.arraycopy(iqData, length - copyLength, dabHistory, 0, copyLength);
			dabHistoryLength = copyLength;
			return dabHistoryLength;
		}
		int overflow = Math.max(0, dabHistoryLength + copyLength - dabHistory.length);
		if (overflow > 0) {
			System.arraycopy(dabHistory, overflow, dabHistory, 0, dabHistoryLength - overflow);
			dabHistoryLength -= overflow;
		}
		System.arraycopy(iqData, length - copyLength, dabHistory, dabHistoryLength, copyLength);
		dabHistoryLength += copyLength;
		return dabHistoryLength & ~1;
	}

	private DabMetrics computeDabMetrics(float[] spectrum, int sampleRateHz) {
		if (spectrum == null || spectrum.length == 0 || sampleRateHz <= DAB_BANDWIDTH_HZ) {
			return new DabMetrics(0, 0, 0, 0, 0, "need >= 1.536 MS/s");
		}
		int size = spectrum.length;
		double binHz = sampleRateHz / (double) size;
		int center = size / 2;
		int halfDabBins = Math.max(2, (int) Math.round(DAB_BANDWIDTH_HZ * 0.5d / binHz));
		int left = Math.max(0, center - halfDabBins);
		int right = Math.min(size - 1, center + halfDabBins);
		int guardBins = Math.max(2, (int) Math.round(80_000d / binHz));

		Average inside = averageDb(spectrum, left + guardBins, right - guardBins);
		Average outsideLeft = averageDb(spectrum, 8, Math.max(8, left - guardBins));
		Average outsideRight = averageDb(spectrum, Math.min(size - 8, right + guardBins), size - 8);
		double outsideDb;
		if (outsideLeft.count > 0 && outsideRight.count > 0) {
			outsideDb = (outsideLeft.db + outsideRight.db) * 0.5d;
		} else if (outsideLeft.count > 0) {
			outsideDb = outsideLeft.db;
		} else if (outsideRight.count > 0) {
			outsideDb = outsideRight.db;
		} else {
			outsideDb = inside.db - 3d;
		}

		Average leftInside = averageDb(spectrum, left + guardBins, center - guardBins);
		Average rightInside = averageDb(spectrum, center + guardBins, right - guardBins);
		double contrastDb = inside.db - outsideDb;
		double balanceDb = Math.abs(leftInside.db - rightInside.db);
		double offsetHz = estimateSpectralOffsetHz(spectrum, left + guardBins, right - guardBins, binHz, center);

		double contrastEvidence = clamp01((contrastDb - 0.8d) / 8d);
		double centerScore = clamp01(1d - Math.abs(offsetHz) / 250_000d);
		double balanceScore = clamp01(1d - balanceDb / 8d);
		double score = contrastEvidence * (60d + centerScore * 25d + balanceScore * 15d);
		String state;
		if (score >= 70d) {
			state = "DAB-like";
		} else if (score >= 45d) {
			state = "possible DAB";
		} else {
			state = "weak/no DAB shape";
		}
		return new DabMetrics(score, contrastDb, offsetHz, balanceDb, DAB_BANDWIDTH_HZ, state);
	}

	private DabConstellation computeDabConstellation(byte[] iqData, int length, int sampleRateHz, DabMetrics metrics) {
		int totalSamples = length / 2;
		if (sampleRateHz < 1_800_000 || totalSamples < 10_000) {
			return new DabConstellation(new float[0], 0, 0, "need wider DAB IQ");
		}
		double scale = sampleRateHz / (double) DAB_BASE_SAMPLE_RATE_HZ;
		int nullSamples = Math.max(64, (int) Math.round(DAB_MODE_I_NULL_SAMPLES * scale));
		int usefulSamples = Math.max(256, (int) Math.round(DAB_MODE_I_USEFUL_SAMPLES * scale));
		int guardSamples = Math.max(16, (int) Math.round(DAB_MODE_I_GUARD_SAMPLES * scale));
		int symbolSamples = usefulSamples + guardSamples;
		double exactSymbolSamples = (DAB_MODE_I_USEFUL_SAMPLES + DAB_MODE_I_GUARD_SAMPLES) * scale;
		int required = nullSamples + guardSamples + usefulSamples + symbolSamples * 3;
		if (totalSamples < required) {
			return new DabConstellation(new float[0], 0, 0, "need longer block");
		}

		int latestUsableNull = totalSamples - required;
		NullSearch nullSearch = findDabNull(iqData, totalSamples, nullSamples, latestUsableNull);
		int expectedUsefulStart = nullSearch.startSample + nullSamples + guardSamples;
		SymbolTiming symbolTiming = refineDabSymbolTiming(iqData, totalSamples, expectedUsefulStart,
				usefulSamples, guardSamples);

		int carrierStep = DAB_CONSTELLATION_CARRIER_STEP;
		int displaySymbol = 2;
		int[] symbolStarts = new int[displaySymbol + 1];
		symbolStarts[0] = symbolTiming.usefulStart;
		double timingCorrelation = symbolTiming.correlation;
		double correctionBins = symbolTiming.correctionBins;
		for (int symbol = 1; symbol <= displaySymbol; symbol++) {
			int expectedStart = symbolTiming.usefulStart + (int) Math.round(symbol * exactSymbolSamples);
			SymbolTiming refined = refineDabSymbolTiming(iqData, totalSamples, expectedStart,
					usefulSamples, guardSamples);
			symbolStarts[symbol] = refined.usefulStart;
			timingCorrelation += refined.correlation;
			correctionBins += refined.correctionBins;
		}
		timingCorrelation /= symbolStarts.length;
		correctionBins /= symbolStarts.length;
		int fineTiming = Math.max(1, (int) Math.round(sampleRateHz / 256_000d));
		int[] timingOffsets = {-fineTiming, 0, fineTiming};
		ConstellationCandidate best = null;
		for (int timingOffset : timingOffsets) {
			ConstellationCandidate candidate = buildDabConstellationCandidate(iqData, symbolStarts,
					usefulSamples, timingOffset, carrierStep, displaySymbol, correctionBins);
			if (best == null || candidate.symmetryScore > best.symmetryScore) {
				best = candidate;
			}
		}
		float[] points = best == null ? new float[0] : best.points;
		double symmetryScore = best == null ? 0 : best.symmetryScore;
		double nullScore = clamp01((nullSearch.ratio - 1.15d) / 2d);
		double shapeScore = clamp01(metrics.score / 100d);
		double symmetryEvidence = clamp01((symmetryScore - 0.08d) / 0.72d);
		double timingEvidence = clamp01((timingCorrelation - 0.08d) / 0.5d);
		double rawLock = Math.max(0, Math.min(100,
				symmetryEvidence * 60d + timingEvidence * 20d + nullScore * 15d + shapeScore * 5d));
		if (metrics.contrastDb < 4d || nullSearch.ratio < 1.5d) {
			rawLock = 0d;
		}
		if (rawLock == 0d) {
			dabLockEma = 0d;
		} else if (Double.isNaN(dabLockEma)) {
			dabLockEma = rawLock;
		} else {
			dabLockEma = dabLockEma * 0.7d + rawLock * 0.3d;
		}
		double lock = dabLockEma;
		String state = lock >= 55d ? "DAB OFDM candidate" : lock >= 35d ? "weak OFDM candidate" : "searching OFDM";
		return new DabConstellation(points, lock, nullSearch.ratio, state,
				timingCorrelation, correctionBins * 1000d);
	}

	private ConstellationCandidate buildDabConstellationCandidate(byte[] iqData, int[] symbolStarts,
			int usefulSamples, int timingOffset, int carrierStep, int displaySymbol, double correctionBins) {
		int maxPairs = DAB_MODE_I_ACTIVE_CARRIERS / carrierStep;
		float[] points = new float[maxPairs * 2];
		float[] magnitudes = new float[maxPairs];
		int[] carriers = new int[maxPairs];
		int out = 0;
		int pointCount = 0;
		double sumCarrierMagnitude = 0d;
		int previousStart = symbolStarts[displaySymbol - 1] + timingOffset;
		int currentStart = symbolStarts[displaySymbol] + timingOffset;
			for (int carrier = -DAB_MODE_I_ACTIVE_CARRIERS / 2; carrier <= DAB_MODE_I_ACTIVE_CARRIERS / 2; carrier += carrierStep) {
				if (carrier == 0) {
					continue;
				}
				Complex previous = dftCarrier(iqData, previousStart, usefulSamples, carrier, correctionBins);
				Complex current = dftCarrier(iqData, currentStart, usefulSamples, carrier, correctionBins);
				double real = current.real * previous.real + current.imag * previous.imag;
				double imag = current.imag * previous.real - current.real * previous.imag;
				double magnitude = Math.sqrt(real * real + imag * imag);
				if (magnitude <= 1e-9 || out + 1 >= points.length) {
					continue;
				}
				double normalizedReal = real / magnitude;
				double normalizedImag = imag / magnitude;
				points[out++] = (float) normalizedReal;
				points[out++] = (float) normalizedImag;
				double currentMagnitude = Math.sqrt(current.real * current.real + current.imag * current.imag);
				magnitudes[pointCount] = (float) currentMagnitude;
				carriers[pointCount] = carrier;
				pointCount++;
				sumCarrierMagnitude += currentMagnitude;
			}
		if (out != points.length) {
			float[] trimmed = new float[out];
			System.arraycopy(points, 0, trimmed, 0, out);
			points = trimmed;
		}
		double phaseSlope = estimateDabPhaseSlope(points, carriers, pointCount, carrierStep);
		double fourthReal = 0d;
		double fourthImag = 0d;
		for (int point = 0; point < pointCount; point++) {
			int offset = point * 2;
			double correction = -phaseSlope * carriers[point];
			double cos = Math.cos(correction);
			double sin = Math.sin(correction);
			double real = points[offset];
			double imag = points[offset + 1];
			points[offset] = (float) (real * cos - imag * sin);
			points[offset + 1] = (float) (real * sin + imag * cos);
			Complex fourth = fourthPower(points[offset], points[offset + 1]);
			fourthReal += fourth.real;
			fourthImag += fourth.imag;
		}
		int fourthCount = pointCount;
		double symmetryScore = fourthCount <= 0 ? 0
				: Math.sqrt(fourthReal * fourthReal + fourthImag * fourthImag) / fourthCount;
		if (points.length >= 2 && fourthCount > 0) {
			double rotation = Math.PI / 4d - Math.atan2(fourthImag, fourthReal) / 4d;
			double cos = Math.cos(rotation);
			double sin = Math.sin(rotation);
			double averageCarrierMagnitude = pointCount <= 0 ? 1d : sumCarrierMagnitude / pointCount;
			double phaseConfidence = clamp01((symmetryScore - 0.15d) / 0.65d);
			for (int i = 0; i + 1 < points.length; i += 2) {
				double real = points[i];
				double imag = points[i + 1];
				int point = i / 2;
				double currentMagnitude = point < pointCount ? magnitudes[point] : averageCarrierMagnitude;
				double noiseMagnitude = currentMagnitude / Math.max(1e-9d, averageCarrierMagnitude);
				double noiseRadius = 0.58d * Math.min(2d, noiseMagnitude);
				double carrierReference = point < dabCarrierLevelEma.length ? dabCarrierLevelEma[point] : 0d;
				if (phaseConfidence > 0.15d && point < dabCarrierLevelEma.length) {
					double alpha = carrierReference <= 0d || currentMagnitude > carrierReference * 2d ? 0.5d : 0.08d;
					carrierReference = carrierReference <= 0d
							? currentMagnitude
							: carrierReference * (1d - alpha) + currentMagnitude * alpha;
					dabCarrierLevelEma[point] = carrierReference;
				}
				double equalizedMagnitude = carrierReference <= 0d
						? 1d
						: currentMagnitude / Math.max(1e-9d, carrierReference);
				double equalizedRadius = 0.79d
						* Math.max(0.65d, Math.min(1.35d, 1d + (equalizedMagnitude - 1d) * 1.2d));
				double radiusScale = noiseRadius * (1d - phaseConfidence)
						+ equalizedRadius * phaseConfidence;
				points[i] = (float) ((real * cos - imag * sin) * radiusScale);
				points[i + 1] = (float) ((real * sin + imag * cos) * radiusScale);
			}
		}
		return new ConstellationCandidate(points, symmetryScore);
	}

	private double estimateDabPhaseSlope(float[] points, int[] carriers, int pointCount, int carrierStep) {
		double weightedSlope = 0d;
		int weightedCount = 0;
		int segmentStart = 0;
		while (segmentStart < pointCount) {
			int segmentEnd = segmentStart + 1;
			while (segmentEnd < pointCount
					&& carriers[segmentEnd] - carriers[segmentEnd - 1] == carrierStep) {
				segmentEnd++;
			}
			int count = segmentEnd - segmentStart;
			if (count >= 4) {
				double sumX = 0d;
				double sumY = 0d;
				double sumXX = 0d;
				double sumXY = 0d;
				double previousPhase = 0d;
				double unwrappedPhase = 0d;
				for (int point = segmentStart; point < segmentEnd; point++) {
					Complex fourth = fourthPower(points[point * 2], points[point * 2 + 1]);
					double phase = Math.atan2(fourth.imag, fourth.real);
					if (point == segmentStart) {
						unwrappedPhase = phase;
					} else {
						double delta = phase - previousPhase;
						while (delta > Math.PI) {
							delta -= 2d * Math.PI;
						}
						while (delta < -Math.PI) {
							delta += 2d * Math.PI;
						}
						unwrappedPhase += delta;
					}
					previousPhase = phase;
					double x = carriers[point];
					sumX += x;
					sumY += unwrappedPhase;
					sumXX += x * x;
					sumXY += x * unwrappedPhase;
				}
				double denominator = count * sumXX - sumX * sumX;
				if (Math.abs(denominator) > 1e-9d) {
					double fourthPowerSlope = (count * sumXY - sumX * sumY) / denominator;
					weightedSlope += fourthPowerSlope * count;
					weightedCount += count;
				}
			}
			segmentStart = segmentEnd;
		}
		return weightedCount <= 0 ? 0d : weightedSlope / weightedCount / 4d;
	}

	private Complex fourthPower(double real, double imag) {
		double squaredReal = real * real - imag * imag;
		double squaredImag = 2d * real * imag;
		return new Complex(squaredReal * squaredReal - squaredImag * squaredImag,
				2d * squaredReal * squaredImag);
	}

	private SymbolTiming refineDabSymbolTiming(byte[] iqData, int totalSamples, int expectedUsefulStart,
			int usefulSamples, int guardSamples) {
		int searchRadius = Math.max(8, guardSamples / 3);
		int first = Math.max(guardSamples, expectedUsefulStart - searchRadius);
		int last = Math.min(totalSamples - usefulSamples - 1, expectedUsefulStart + searchRadius);
		int coarseStep = Math.max(1, guardSamples / 48);
		int best = expectedUsefulStart;
		double bestScore = -1d;
		PrefixCorrelation bestCorrelation = PrefixCorrelation.EMPTY;
		for (int candidate = first; candidate <= last; candidate += coarseStep) {
			PrefixCorrelation correlation = cyclicPrefixCorrelation(iqData, candidate, usefulSamples, guardSamples, 2);
			if (correlation.score > bestScore) {
				bestScore = correlation.score;
				best = candidate;
				bestCorrelation = correlation;
			}
		}
		int refineFirst = Math.max(first, best - coarseStep);
		int refineLast = Math.min(last, best + coarseStep);
		for (int candidate = refineFirst; candidate <= refineLast; candidate++) {
			PrefixCorrelation correlation = cyclicPrefixCorrelation(iqData, candidate, usefulSamples, guardSamples, 1);
			if (correlation.score > bestScore) {
				bestScore = correlation.score;
				best = candidate;
				bestCorrelation = correlation;
			}
		}
		double correctionBins = -Math.atan2(bestCorrelation.imag, bestCorrelation.real) / (2d * Math.PI);
		return new SymbolTiming(best, correctionBins, bestScore);
	}

	private PrefixCorrelation cyclicPrefixCorrelation(byte[] iqData, int usefulStart, int usefulSamples,
			int guardSamples, int stride) {
		double real = 0d;
		double imag = 0d;
		double firstEnergy = 0d;
		double secondEnergy = 0d;
		int prefixStart = usefulStart - guardSamples;
		for (int n = 0; n < guardSamples; n += stride) {
			int firstOffset = (prefixStart + n) * 2;
			int secondOffset = (prefixStart + n + usefulSamples) * 2;
			double firstI = iqData[firstOffset];
			double firstQ = iqData[firstOffset + 1];
			double secondI = iqData[secondOffset];
			double secondQ = iqData[secondOffset + 1];
			real += firstI * secondI + firstQ * secondQ;
			imag += firstQ * secondI - firstI * secondQ;
			firstEnergy += firstI * firstI + firstQ * firstQ;
			secondEnergy += secondI * secondI + secondQ * secondQ;
		}
		double denominator = Math.sqrt(firstEnergy * secondEnergy);
		double score = denominator <= 0d ? 0d : Math.sqrt(real * real + imag * imag) / denominator;
		return new PrefixCorrelation(real, imag, score);
	}

	private NullSearch findDabNull(byte[] iqData, int totalSamples, int nullSamples, int latestStart) {
		long[] prefix = new long[totalSamples + 1];
		for (int i = 0; i < totalSamples; i++) {
			int offset = i * 2;
			int iv = iqData[offset];
			int qv = iqData[offset + 1];
			prefix[i + 1] = prefix[i] + iv * iv + qv * qv;
		}
		int step = Math.max(1, nullSamples / 48);
		int limit = Math.max(0, Math.min(totalSamples - nullSamples - 1, latestStart));
		long bestEnergy = Long.MAX_VALUE;
		long sumEnergy = 0;
		int count = 0;
		int best = 0;
		for (int start = 0; start <= limit; start += step) {
			long energy = prefix[start + nullSamples] - prefix[start];
			sumEnergy += energy;
			count++;
			if (energy < bestEnergy) {
				bestEnergy = energy;
				best = start;
			}
		}
		int refineStart = Math.max(0, best - step * 2);
		int refineStop = Math.min(limit, best + step * 2);
		for (int start = refineStart; start <= refineStop; start++) {
			long energy = prefix[start + nullSamples] - prefix[start];
			if (energy < bestEnergy) {
				bestEnergy = energy;
				best = start;
			}
		}
		long localEnergy = 0;
		int localCount = 0;
		if (best >= nullSamples) {
			localEnergy += prefix[best] - prefix[best - nullSamples];
			localCount++;
		}
		if (best + nullSamples * 2 <= totalSamples) {
			localEnergy += prefix[best + nullSamples * 2] - prefix[best + nullSamples];
			localCount++;
		}
		double average = localCount <= 0
				? (count <= 0 ? bestEnergy : sumEnergy / (double) count)
				: localEnergy / (double) localCount;
		double ratio = bestEnergy <= 0 ? 0 : average / bestEnergy;
		return new NullSearch(best, ratio);
	}

	private Complex dftCarrier(byte[] iqData, int startSample, int usefulSamples, int carrier, double correctionBins) {
		double real = 0;
		double imag = 0;
		double angleStep = -2d * Math.PI * (carrier + correctionBins) / usefulSamples;
		double stepReal = Math.cos(angleStep);
		double stepImag = Math.sin(angleStep);
		double oscReal = 1d;
		double oscImag = 0d;
		for (int n = 0; n < usefulSamples; n++) {
			int offset = (startSample + n) * 2;
			double i = iqData[offset] / 128d;
			double q = iqData[offset + 1] / 128d;
			real += i * oscReal - q * oscImag;
			imag += i * oscImag + q * oscReal;
			double nextReal = oscReal * stepReal - oscImag * stepImag;
			oscImag = oscReal * stepImag + oscImag * stepReal;
			oscReal = nextReal;
		}
		return new Complex(real, imag);
	}

	private Average averageDb(float[] values, int from, int to) {
		int start = Math.max(0, Math.min(values.length, from));
		int stop = Math.max(start, Math.min(values.length, to));
		if (stop <= start) {
			return new Average(-120d, 0);
		}
		double sumPower = 0;
		for (int i = start; i < stop; i++) {
			sumPower += Math.pow(10d, values[i] / 10d);
		}
		double db = 10d * Math.log10(sumPower / (stop - start) + 1e-12);
		return new Average(db, stop - start);
	}

	private double estimateSpectralOffsetHz(float[] spectrum, int from, int to, double binHz, int centerBin) {
		int start = Math.max(0, Math.min(spectrum.length, from));
		int stop = Math.max(start, Math.min(spectrum.length, to));
		double weighted = 0;
		double total = 0;
		for (int i = start; i < stop; i++) {
			double power = Math.pow(10d, spectrum[i] / 10d);
			weighted += (i - centerBin) * binHz * power;
			total += power;
		}
		return total <= 0 ? 0 : weighted / total;
	}

	private double clamp01(double value) {
		return Math.max(0d, Math.min(1d, value));
	}

	private float[] computeSpectrum(byte[] samples, int sampleCount) {
		if (sampleCount < SPECTRUM_SIZE) {
			return new float[0];
		}
		double[] real = new double[SPECTRUM_SIZE];
		double[] imag = new double[SPECTRUM_SIZE];
		int startSample = Math.max(0, (sampleCount - SPECTRUM_SIZE) / 2);
		for (int i = 0; i < SPECTRUM_SIZE; i++) {
			int source = (startSample + i) * 2;
			double window = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / (SPECTRUM_SIZE - 1));
			real[i] = samples[source] / 128d * window;
			imag[i] = samples[source + 1] / 128d * window;
		}
		fft(real, imag);
		float[] db = new float[SPECTRUM_SIZE];
		for (int i = 0; i < SPECTRUM_SIZE; i++) {
			int shifted = (i + SPECTRUM_SIZE / 2) % SPECTRUM_SIZE;
			double power = real[shifted] * real[shifted] + imag[shifted] * imag[shifted];
			db[i] = (float) (10d * Math.log10(power / SPECTRUM_SIZE + 1e-12));
		}
		return db;
	}

	private void fft(double[] real, double[] imag) {
		int size = real.length;
		for (int i = 1, j = 0; i < size; i++) {
			int bit = size >> 1;
			for (; (j & bit) != 0; bit >>= 1) {
				j ^= bit;
			}
			j ^= bit;
			if (i < j) {
				double temp = real[i];
				real[i] = real[j];
				real[j] = temp;
				temp = imag[i];
				imag[i] = imag[j];
				imag[j] = temp;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = -2d * Math.PI / length;
			double wLengthReal = Math.cos(angle);
			double wLengthImag = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double wReal = 1d;
				double wImag = 0d;
				for (int j = 0; j < length / 2; j++) {
					int even = start + j;
					int odd = even + length / 2;
					double oddReal = real[odd] * wReal - imag[odd] * wImag;
					double oddImag = real[odd] * wImag + imag[odd] * wReal;
					real[odd] = real[even] - oddReal;
					imag[odd] = imag[even] - oddImag;
					real[even] += oddReal;
					imag[even] += oddImag;
					double nextReal = wReal * wLengthReal - wImag * wLengthImag;
					wImag = wReal * wLengthImag + wImag * wLengthReal;
					wReal = nextReal;
				}
			}
		}
	}

	private double calculateRawQuality(double dbfs, double clippingPercent, double dcPercent) {
		double levelScore;
		if (dbfs < -50d) {
			levelScore = 0;
		} else if (dbfs < -25d) {
			levelScore = (dbfs + 50d) / 25d * 100d;
		} else if (dbfs <= -8d) {
			levelScore = 100d;
		} else if (dbfs <= -3d) {
			levelScore = 100d - (dbfs + 8d) / 5d * 45d;
		} else {
			levelScore = 25d;
		}

		if (Double.isNaN(emaDbfs)) {
			emaDbfs = dbfs;
			stabilityDb = 0;
		} else {
			double delta = Math.abs(dbfs - emaDbfs);
			stabilityDb = stabilityDb * 0.85d + delta * 0.15d;
			emaDbfs = emaDbfs * 0.9d + dbfs * 0.1d;
		}
		double clippingScore = Math.max(0, 100d - clippingPercent * 60d);
		double dcScore = Math.max(0, 100d - dcPercent * 3d);
		double stabilityScore = Math.max(0, 100d - stabilityDb * 20d);
		double quality = levelScore * 0.45d + clippingScore * 0.25d + dcScore * 0.15d + stabilityScore * 0.15d;
		if (clippingPercent >= 1d) {
			quality = Math.min(quality, 45d);
		}
		return Math.max(0, Math.min(100, quality));
	}

	private void updateView() {
		Snapshot active = snapshot;
		scatterPanel.setSnapshot(active);
		spectrumPanel.setSnapshot(active);
		dabConstellationPanel.setSnapshot(active);
		qualityPanel.setSnapshot(active);
		dabLockPanel.setSnapshot(active);
		if (active == null) {
			statsLabel.setText("Waiting for IQ samples...");
			detailLabel.setText("RAW quality waits for signal level, clipping, DC offset and stability");
			return;
		}
		statsLabel.setText(String.format(Locale.US, "%.3f MHz   %.3f MS/s   RMS %.1f dBFS   peak %d",
				active.centerFreqHz / 1_000_000d, active.sampleRateHz / 1_000_000d, active.dbfs, active.peak));
		if (dabMode && active.dabMetrics != null) {
			DabMetrics dab = active.dabMetrics;
			DabConstellation constellation = dab.constellation;
			String sync = constellation == null ? "sync --" : constellation.state;
			detailLabel.setText(String.format(Locale.US,
					"%s   %s %.0f%%   contrast %.1f dB   offset %.0f kHz   null %.1fx   CP %.2f   CFO %.0f Hz",
					dab.state, sync, constellation == null ? 0 : constellation.lockScore,
					dab.contrastDb, dab.offsetHz / 1000d,
					constellation == null ? 0 : constellation.nullRatio,
					constellation == null ? 0 : constellation.timingCorrelation,
					constellation == null ? 0 : constellation.frequencyCorrectionHz));
		} else {
			detailLabel.setText(String.format(Locale.US, "DC %.1f%%   clipping %.2f%%   stability %.2f dB",
					active.dcPercent, active.clippingPercent, active.stabilityDb));
		}
	}

	private static final class Average {
		final double db;
		final int count;

		Average(double db, int count) {
			this.db = db;
			this.count = count;
		}
	}

	private static final class Complex {
		final double real;
		final double imag;

		Complex(double real, double imag) {
			this.real = real;
			this.imag = imag;
		}
	}

	private static final class PrefixCorrelation {
		static final PrefixCorrelation EMPTY = new PrefixCorrelation(0d, 0d, 0d);

		final double real;
		final double imag;
		final double score;

		PrefixCorrelation(double real, double imag, double score) {
			this.real = real;
			this.imag = imag;
			this.score = score;
		}
	}

	private static final class SymbolTiming {
		final int usefulStart;
		final double correctionBins;
		final double correlation;

		SymbolTiming(int usefulStart, double correctionBins, double correlation) {
			this.usefulStart = usefulStart;
			this.correctionBins = correctionBins;
			this.correlation = correlation;
		}
	}

	private static final class NullSearch {
		final int startSample;
		final double ratio;

		NullSearch(int startSample, double ratio) {
			this.startSample = startSample;
			this.ratio = ratio;
		}
	}

	private static final class DabConstellation {
		final float[] points;
		final double lockScore;
		final double nullRatio;
		final String state;
		final double timingCorrelation;
		final double frequencyCorrectionHz;

		DabConstellation(float[] points, double lockScore, double nullRatio, String state) {
			this(points, lockScore, nullRatio, state, 0d, 0d);
		}

		DabConstellation(float[] points, double lockScore, double nullRatio, String state,
				double timingCorrelation, double frequencyCorrectionHz) {
			this.points = points;
			this.lockScore = lockScore;
			this.nullRatio = nullRatio;
			this.state = state;
			this.timingCorrelation = timingCorrelation;
			this.frequencyCorrectionHz = frequencyCorrectionHz;
		}
	}

	private static final class ConstellationCandidate {
		final float[] points;
		final double symmetryScore;

		ConstellationCandidate(float[] points, double symmetryScore) {
			this.points = points;
			this.symmetryScore = symmetryScore;
		}
	}

	private static final class DabMetrics {
		final double score;
		final double contrastDb;
		final double offsetHz;
		final double balanceDb;
		final int bandwidthHz;
		final String state;
		DabConstellation constellation;

		DabMetrics(double score, double contrastDb, double offsetHz, double balanceDb, int bandwidthHz, String state) {
			this.score = score;
			this.contrastDb = contrastDb;
			this.offsetHz = offsetHz;
			this.balanceDb = balanceDb;
			this.bandwidthHz = bandwidthHz;
			this.state = state;
		}
	}

	private static final class Snapshot {
		final long centerFreqHz;
		final int sampleRateHz;
		final byte[] samples;
		final int sampleCount;
		final double dbfs;
		final int peak;
		final double dcPercent;
		final double clippingPercent;
		final double stabilityDb;
		final double quality;
		final float[] spectrumDb;
		final DabMetrics dabMetrics;
		final long createdMillis;

		Snapshot(long centerFreqHz, int sampleRateHz, byte[] samples, int sampleCount, double dbfs, int peak,
				double dcPercent, double clippingPercent, double stabilityDb, double quality, float[] spectrumDb,
				DabMetrics dabMetrics, long createdMillis) {
			this.centerFreqHz = centerFreqHz;
			this.sampleRateHz = sampleRateHz;
			this.samples = samples;
			this.sampleCount = sampleCount;
			this.dbfs = dbfs;
			this.peak = peak;
			this.dcPercent = dcPercent;
			this.clippingPercent = clippingPercent;
			this.stabilityDb = stabilityDb;
			this.quality = quality;
			this.spectrumDb = spectrumDb;
			this.dabMetrics = dabMetrics;
			this.createdMillis = createdMillis;
		}
	}

	private static final class QualityPanel extends JPanel {
		private volatile Snapshot snapshot;

		QualityPanel() {
			setBackground(PANEL_BG);
			setPreferredSize(new Dimension(100, 24));
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				int width = getWidth();
				int height = getHeight();
				int barX = 92;
				int barY = 5;
				int barW = Math.max(30, width - barX - 6);
				int barH = Math.max(8, height - 10);
				Snapshot active = snapshot;
				double quality = active == null ? 0 : active.quality;
				Color color = quality >= 70 ? QUALITY_GOOD : quality >= 40 ? QUALITY_WARN : QUALITY_BAD;
				g.setColor(TEXT_FG);
				String label = active != null && active.dabMetrics != null ? "INPUT" : "RAW";
				g.drawString(active == null ? label + " --%" : String.format(Locale.US, "%s %.0f%%", label, quality), 4, 17);
				g.setColor(GRID);
				g.fillRect(barX, barY, barW, barH);
				g.setColor(color);
				g.fillRect(barX, barY, (int) Math.round(barW * quality / 100d), barH);
				g.setColor(Color.DARK_GRAY);
				g.drawRect(barX, barY, barW, barH);
			} finally {
				g.dispose();
			}
		}
	}

	private static final class DabLockPanel extends JPanel {
		private volatile Snapshot snapshot;

		DabLockPanel() {
			setBackground(PANEL_BG);
			setPreferredSize(new Dimension(100, 22));
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				int width = getWidth();
				int height = getHeight();
				int barX = 92;
				int barY = 4;
				int barW = Math.max(30, width - barX - 6);
				int barH = Math.max(8, height - 8);
				Snapshot active = snapshot;
				DabConstellation constellation = active == null || active.dabMetrics == null
						? null : active.dabMetrics.constellation;
				double lock = constellation == null ? 0 : constellation.lockScore;
				Color color = lock >= 55 ? QUALITY_GOOD : lock >= 35 ? QUALITY_WARN : QUALITY_BAD;
				g.setColor(TEXT_FG);
				g.drawString(constellation == null ? "QUALITY --%"
						: String.format(Locale.US, "QUALITY %.0f%%", lock), 4, 16);
				g.setColor(GRID);
				g.fillRect(barX, barY, barW, barH);
				g.setColor(color);
				g.fillRect(barX, barY, (int) Math.round(barW * lock / 100d), barH);
				g.setColor(Color.DARK_GRAY);
				g.drawRect(barX, barY, barW, barH);
			} finally {
				g.dispose();
			}
		}
	}

	private static final class SpectrumPanel extends JPanel {
		private volatile Snapshot snapshot;

		SpectrumPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int left = 12;
				int right = width - 10;
				int top = 22;
				int bottom = height - 22;
				g.setColor(GRID);
				for (int i = 0; i <= 4; i++) {
					int y = top + (bottom - top) * i / 4;
					g.drawLine(left, y, right, y);
				}
				g.drawLine(width / 2, top, width / 2, bottom);
				g.setColor(MUTED_FG);
				g.drawString("FFT spectrum", left, 15);
				Snapshot active = snapshot;
				if (active == null || active.spectrumDb == null || active.spectrumDb.length == 0) {
					g.drawString("Waiting for spectrum...", left, top + 24);
					return;
				}
				if (active.dabMetrics != null && active.dabMetrics.bandwidthHz > 0 && active.sampleRateHz > 0) {
					int halfWidth = Math.round((right - left) * (active.dabMetrics.bandwidthHz
							/ (float) active.sampleRateHz) * 0.5f);
					g.setColor(new Color(0x667733));
					g.drawLine(width / 2 - halfWidth, top, width / 2 - halfWidth, bottom);
					g.drawLine(width / 2 + halfWidth, top, width / 2 + halfWidth, bottom);
				}
				float[] spectrum = active.spectrumDb;
				float min = Float.MAX_VALUE;
				float max = -Float.MAX_VALUE;
				for (float value : spectrum) {
					if (value < min) {
						min = value;
					}
					if (value > max) {
						max = value;
					}
				}
				float floor = Math.max(min, max - 70f);
				int previousX = left;
				int previousY = scaleSpectrumY(spectrum[0], floor, max, top, bottom);
				g.setColor(POINT);
				for (int i = 1; i < spectrum.length; i++) {
					int x = left + Math.round((right - left) * i / (float) (spectrum.length - 1));
					int y = scaleSpectrumY(spectrum[i], floor, max, top, bottom);
					g.drawLine(previousX, previousY, x, y);
					previousX = x;
					previousY = y;
				}
				g.setColor(MUTED_FG);
				g.drawString("-span/2", left, height - 6);
				g.drawString("0", Math.max(left, width / 2 - 4), height - 6);
				String rightLabel = "+span/2";
				g.drawString(rightLabel, Math.max(left, right - g.getFontMetrics().stringWidth(rightLabel)), height - 6);
			} finally {
				g.dispose();
			}
		}

		private int scaleSpectrumY(float value, float min, float max, int top, int bottom) {
			float range = Math.max(1f, max - min);
			float normalized = Math.max(0f, Math.min(1f, (value - min) / range));
			return bottom - Math.round((bottom - top) * normalized);
		}
	}

	private static final class DabConstellationPanel extends JPanel {
		private volatile Snapshot snapshot;

		DabConstellationPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int cx = width / 2;
				int cy = height / 2;
				int radius = Math.max(20, Math.min(width, height) / 2 - 28);
				g.setColor(GRID);
				g.drawLine(16, cy, width - 16, cy);
				g.drawLine(cx, 18, cx, height - 18);
				g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
				g.setColor(new Color(0x446644));
				int markerRadius = Math.max(3, radius / 28);
				int markerDistance = Math.round(radius * 0.48f);
				g.drawOval(cx + markerDistance - markerRadius, cy - markerDistance - markerRadius,
						markerRadius * 2, markerRadius * 2);
				g.drawOval(cx - markerDistance - markerRadius, cy - markerDistance - markerRadius,
						markerRadius * 2, markerRadius * 2);
				g.drawOval(cx + markerDistance - markerRadius, cy + markerDistance - markerRadius,
						markerRadius * 2, markerRadius * 2);
				g.drawOval(cx - markerDistance - markerRadius, cy + markerDistance - markerRadius,
						markerRadius * 2, markerRadius * 2);
				g.setColor(MUTED_FG);
				g.drawString("DAB differential DQPSK", 12, 16);
				Snapshot active = snapshot;
				DabConstellation constellation = active == null || active.dabMetrics == null
						? null : active.dabMetrics.constellation;
				if (constellation == null || constellation.points == null || constellation.points.length < 2) {
					g.drawString("Searching for DAB OFDM sync...", 18, 36);
					return;
				}
				g.setColor(constellation.lockScore >= 65d ? QUALITY_GOOD
						: constellation.lockScore >= 35d ? QUALITY_WARN : POINT);
				float[] points = constellation.points;
				for (int i = 0; i + 1 < points.length; i += 2) {
					int x = cx + Math.round(points[i] * radius * 0.86f);
					int y = cy - Math.round(points[i + 1] * radius * 0.86f);
					g.fillRect(x, y, 2, 2);
				}
				g.setColor(MUTED_FG);
				g.drawString(constellation.state, 12, height - 8);
				long ageMillis = Math.max(0, System.currentTimeMillis() - active.createdMillis);
				String status = String.format(Locale.US, "lock %.0f%%  %d ms", constellation.lockScore, ageMillis);
				g.drawString(status, Math.max(12, width - g.getFontMetrics().stringWidth(status) - 8), height - 8);
			} finally {
				g.dispose();
			}
		}
	}

	private static final class ScatterPanel extends JPanel {
		private volatile Snapshot snapshot;

		ScatterPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int cx = width / 2;
				int cy = height / 2;
				int radius = Math.max(20, Math.min(width, height) / 2 - 24);
				g.setColor(GRID);
				g.drawLine(16, cy, width - 16, cy);
				g.drawLine(cx, 16, cx, height - 16);
				g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
				Snapshot active = snapshot;
				if (active == null || active.sampleCount <= 0) {
					g.setColor(MUTED_FG);
					g.drawString("Waiting for IQ samples...", 18, 28);
					return;
				}
				g.setColor(POINT);
				byte[] samples = active.samples;
				for (int i = 0; i + 1 < samples.length; i += 2) {
					int x = cx + Math.round(samples[i] / 128f * radius);
					int y = cy - Math.round(samples[i + 1] / 128f * radius);
					g.fillRect(x, y, 2, 2);
				}
			} finally {
				g.dispose();
			}
		}
	}
}
