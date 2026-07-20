package jspectrumanalyzer.iq;

import java.util.Arrays;

/** Lightweight DVB-T (EN 300 744) OFDM synchronizer/constellation monitor. */
final class DvbtSignalAnalyzer {
	static final int BANDWIDTH_HZ = 8_000_000;
	private static final double BASE_RATE_HZ = 64_000_000d / 7d;
	private final boolean tpsEnabled;
	private static final int[] FFT_SIZES = {8192, 2048};
	private static final int[] GUARD_DIVISORS = {4, 8, 16, 32};
	private static final int MAX_POINTS = 1800;
	private String stableModulation;
	private String modulationCandidate;
	private int modulationCandidateHits;
	private String normalizationModulation;
	private double normalizationScale = Double.NaN;

	DvbtSignalAnalyzer() {
		this(false);
	}

	DvbtSignalAnalyzer(boolean tpsEnabled) {
		this.tpsEnabled = tpsEnabled;
	}

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		if (sampleRateHz < BANDWIDTH_HZ || length < 16_384) {
			return Result.empty("need >= 8 MS/s IQ");
		}
		int sourceSamples = Math.min(length / 2,
				(int) Math.ceil(sampleRateHz * (tpsEnabled ? 0.090 : 0.012)));
		int sourceStart = length / 2 - sourceSamples;
		int resampledCount = (int) Math.floor(sourceSamples * BASE_RATE_HZ / sampleRateHz);
		if (resampledCount < 4096) {
			return Result.empty("need longer IQ block");
		}
		double[] i = new double[resampledCount];
		double[] q = new double[resampledCount];
		double sourceStep = sampleRateHz / BASE_RATE_HZ;
		for (int n = 0; n < resampledCount; n++) {
			double source = sourceStart + n * sourceStep;
			int a = Math.min(length / 2 - 2, (int) source);
			double fraction = source - a;
			i[n] = (iq[a * 2] * (1d - fraction) + iq[(a + 1) * 2] * fraction) / 128d;
			q[n] = (iq[a * 2 + 1] * (1d - fraction) + iq[(a + 1) * 2 + 1] * fraction) / 128d;
		}

		Sync best = null;
		for (int fftSize : FFT_SIZES) {
			for (int guardDivisor : GUARD_DIVISORS) {
				Sync candidate = findSync(i, q, fftSize, fftSize / guardDivisor);
				if (best == null || candidate.score > best.score + 0.01
						|| (Math.abs(candidate.score - best.score) <= 0.01
								&& candidate.guard / (double) candidate.fftSize > best.guard / (double) best.fftSize)) {
					best = candidate;
				}
			}
		}
		if (best == null || best.usefulStart + best.fftSize > i.length) {
			return Result.empty("searching DVB-T OFDM");
		}
		return buildConstellation(i, q, best);
	}

	private Sync findSync(double[] i, double[] q, int fftSize, int guard) {
		int symbol = fftSize + guard;
		int last = i.length - symbol - fftSize - guard;
		if (last <= 0) {
			return new Sync(fftSize, guard, 0, 0, 0);
		}
		int step = Math.max(1, guard / 24);
		Sync best = new Sync(fftSize, guard, 0, 0, 0);
		int searchFirst = Math.max(0, last - 3 * (8192 + 2048));
		for (int start = searchFirst; start <= last; start += step) {
			Sync value = correlate(i, q, fftSize, guard, start, Math.max(1, guard / 128));
			if (value.score > best.score) best = value;
		}
		int first = Math.max(searchFirst, best.usefulStart - guard - step * 2);
		int stop = Math.min(last, best.usefulStart - guard + step * 2);
		for (int start = first; start <= stop; start++) {
			Sync value = correlate(i, q, fftSize, guard, start, Math.max(1, guard / 256));
			if (value.score > best.score) best = value;
		}
		return best;
	}

	private Sync correlate(double[] i, double[] q, int fftSize, int guard, int prefixStart, int stride) {
		double real = 0, imag = 0, e1 = 0, e2 = 0;
		for (int n = 0; n < guard; n += stride) {
			int a = prefixStart + n;
			int b = a + fftSize;
			real += i[a] * i[b] + q[a] * q[b];
			imag += q[a] * i[b] - i[a] * q[b];
			e1 += i[a] * i[a] + q[a] * q[a];
			e2 += i[b] * i[b] + q[b] * q[b];
		}
		double score = Math.sqrt(real * real + imag * imag) / Math.max(1e-12, Math.sqrt(e1 * e2));
		double correctionBins = -Math.atan2(imag, real) / (2d * Math.PI);
		return new Sync(fftSize, guard, prefixStart + guard, score, correctionBins);
	}

	private Result buildConstellation(double[] inputI, double[] inputQ, Sync sync) {
		int size = sync.fftSize;
		double[] real = new double[size];
		double[] imag = new double[size];
		for (int n = 0; n < size; n++) {
			double angle = -2d * Math.PI * sync.correctionBins * n / size;
			double cos = Math.cos(angle), sin = Math.sin(angle);
			real[n] = inputI[sync.usefulStart + n] * cos - inputQ[sync.usefulStart + n] * sin;
			imag[n] = inputI[sync.usefulStart + n] * sin + inputQ[sync.usefulStart + n] * cos;
		}
		fft(real, imag);
		int active = size == 8192 ? 6817 : 1705;
		int half = active / 2;
		PilotFit best = null;
		for (int phase = 0; phase < 4; phase++) {
			PilotFit fit = pilotFit(real, imag, size, active, phase);
			if (best == null || fit.coherence > best.coherence) best = fit;
		}
		if (best == null || best.pilotCarriers.length < 2) return Result.empty("pilot sync search");

		float[] rawPoints = new float[Math.min(MAX_POINTS, active) * 2];
		int out = 0;
		for (int k = 1; k < active - 1 && out + 1 < rawPoints.length; k += Math.max(1, active / MAX_POINTS)) {
			if ((k - 3 * best.phase) % 12 == 0) continue;
			int bin = (k - half + size) % size;
			Complex h = interpolateChannel(best, k);
			double denominator = h.real * h.real + h.imag * h.imag;
			if (denominator < 1e-12) continue;
			rawPoints[out++] = (float) ((real[bin] * h.real + imag[bin] * h.imag) / denominator);
			rawPoints[out++] = (float) ((imag[bin] * h.real - real[bin] * h.imag) / denominator);
		}
		float[] points = new float[out];
		System.arraycopy(rawPoints, 0, points, 0, out);
		QamFit qam = fitQam(points);
		double cpEvidence = clamp01((sync.score - 0.18) / 0.62);
		double pilotEvidence = clamp01((best.coherence - 0.2) / 0.7);
		double merEvidence = clamp01((qam.merDb - 7d) / 24d);
		double quality = 100d * (0.30 * cpEvidence + 0.25 * pilotEvidence + 0.45 * merEvidence);
		if (sync.score < 0.16 || best.coherence < 0.12) quality = 0;
		String mode = size == 8192 ? "8K" : "2K";
		TpsInfo tps = tpsEnabled ? decodeTps(inputI, inputQ, sync) : null;
		String codeRate = tps == null ? "--" : tps.codeRate;
		if (tps != null) {
			mode = tps.mode;
			stableModulation = tps.modulation;
			modulationCandidate = tps.modulation;
			modulationCandidateHits = 3;
		}
		String detectedModulation = tps == null ? qam.name : tps.modulation;
		String displayModulation = stabilizeModulation(detectedModulation);
		scalePoints(points, displayModulation);
		String state = quality >= 55 ? "DVB-T locked" : quality >= 25 ? "weak DVB-T" : "searching DVB-T";
		return new Result(points, quality, qam.merDb, sync.score, best.coherence,
				sync.correctionBins * BASE_RATE_HZ / size, mode, "1/" + (size / sync.guard), displayModulation,
				codeRate, state);
	}

	private String stabilizeModulation(String detected) {
		if (detected == null || "--".equals(detected)) return stableModulation == null ? "--" : stableModulation;
		if (detected.equals(modulationCandidate)) modulationCandidateHits++;
		else { modulationCandidate = detected; modulationCandidateHits = 1; }
		if (stableModulation == null || modulationCandidateHits >= 3) stableModulation = detected;
		return stableModulation;
	}

	private TpsInfo decodeTps(double[] inputI, double[] inputQ, Sync sync) {
		int symbolSamples = sync.fftSize + sync.guard;
		int availableSymbols = sync.usefulStart / symbolSamples + 1;
		int count = Math.min(82, availableSymbols);
		if (count < 45) return null;
		Complex[] tps = new Complex[count];
		int firstStart = sync.usefulStart - (count - 1) * symbolSamples;
		int[] carriers = {34, 209, 346};
		for (int symbol = 0; symbol < count; symbol++) {
			int start = firstStart + symbol * symbolSamples;
			double sumReal = 0, sumImag = 0;
			for (int carrier : carriers) {
				int centered = carrier - (sync.fftSize == 8192 ? 3408 : 852);
				Complex value = dft(inputI, inputQ, start, sync.fftSize, centered + sync.correctionBins);
				if (symbol > 0) {
					Complex previous = dft(inputI, inputQ, start - symbolSamples, sync.fftSize,
							centered + sync.correctionBins);
					double magnitude = Math.sqrt((value.real*value.real+value.imag*value.imag)
							*(previous.real*previous.real+previous.imag*previous.imag));
					if (magnitude > 1e-12) {
						sumReal += (value.real*previous.real+value.imag*previous.imag)/magnitude;
						sumImag += (value.imag*previous.real-value.real*previous.imag)/magnitude;
					}
				}
			}
			tps[symbol] = new Complex(sumReal, sumImag);
		}
		int[] bits = new int[count - 1];
		for (int n = 1; n < count; n++) bits[n - 1] = tps[n].real < 0 ? 1 : 0;
		for (int start = 0; start + 39 < bits.length; start++) {
			int word = 0;
			for (int b = 0; b < 16; b++) word = (word << 1) | bits[start + b];
			boolean inverted = word == 0xca11;
			if (word != 0x35ee && !inverted) continue;
			int constellation = readBits(bits, start + 24, 2, inverted);
			int code = readBits(bits, start + 29, 3, inverted);
			int transmission = readBits(bits, start + 37, 2, inverted);
			String modulation = constellation == 0 ? "QPSK" : constellation == 1 ? "16-QAM"
					: constellation == 2 ? "64-QAM" : "--";
			String codeRate = code == 0 ? "1/2" : code == 1 ? "2/3" : code == 2 ? "3/4"
					: code == 3 ? "5/6" : code == 4 ? "7/8" : "--";
			String mode = transmission == 0 ? "2K" : transmission == 1 ? "8K" : "--";
			return new TpsInfo(modulation, codeRate, mode);
		}
		return null;
	}

	private int readBits(int[] bits, int start, int count, boolean inverted) {
		int value = 0;
		for (int n = 0; n < count; n++) value = (value << 1) | (bits[start + n] ^ (inverted ? 1 : 0));
		return value;
	}

	private Complex dft(double[] inputI, double[] inputQ, int start, int size, double carrier) {
		double real=0,imag=0,angle=-2*Math.PI*carrier/size,stepReal=Math.cos(angle),stepImag=Math.sin(angle);
		double oscReal=1,oscImag=0;
		for(int n=0;n<size;n++){double i=inputI[start+n],q=inputQ[start+n];real+=i*oscReal-q*oscImag;imag+=i*oscImag+q*oscReal;
			double next=oscReal*stepReal-oscImag*stepImag;oscImag=oscReal*stepImag+oscImag*stepReal;oscReal=next;}
		return new Complex(real,imag);
	}

	private PilotFit pilotFit(double[] real, double[] imag, int size, int active, int phase) {
		int count = (active - 1 - 3 * phase) / 12 + 1;
		int[] carriers = new int[count];
		Complex[] channel = new Complex[count];
		double sumReal = 0, sumImag = 0;
		int half = active / 2;
		for (int p = 0; p < count; p++) {
			int k = 3 * phase + 12 * p;
			int bin = (k - half + size) % size;
			double sign = pilotPolarity(k);
			carriers[p] = k;
			channel[p] = new Complex(real[bin] * sign, imag[bin] * sign);
			if (p > 0) {
				Complex a = channel[p - 1], b = channel[p];
				double den = Math.sqrt((a.real*a.real+a.imag*a.imag)*(b.real*b.real+b.imag*b.imag));
				if (den > 0) { sumReal += (a.real*b.real+a.imag*b.imag)/den; sumImag += (a.real*b.imag-a.imag*b.real)/den; }
			}
		}
		double coherence = count < 2 ? 0 : Math.sqrt(sumReal*sumReal + sumImag*sumImag) / (count - 1);
		return new PilotFit(phase, carriers, channel, coherence);
	}

	private double pilotPolarity(int carrier) {
		int register = 0x7ff;
		int bit = 1;
		for (int k = 0; k <= carrier; k++) {
			bit = register & 1;
			int feedback = ((register >> 0) ^ (register >> 2)) & 1;
			register = (register >> 1) | (feedback << 10);
		}
		return bit == 0 ? 1d : -1d;
	}

	private Complex interpolateChannel(PilotFit fit, int carrier) {
		int index = Math.max(0, Math.min(fit.pilotCarriers.length - 2, (carrier - fit.pilotCarriers[0]) / 12));
		while (index + 1 < fit.pilotCarriers.length - 1 && fit.pilotCarriers[index + 1] < carrier) index++;
		int aCarrier = fit.pilotCarriers[index], bCarrier = fit.pilotCarriers[index + 1];
		double t = (carrier - aCarrier) / (double) Math.max(1, bCarrier - aCarrier);
		Complex a = fit.channel[index], b = fit.channel[index + 1];
		return new Complex(a.real + (b.real - a.real) * t, a.imag + (b.imag - a.imag) * t);
	}

	private QamFit fitQam(float[] points) {
		QamFit best = null;
		for (int levels : new int[] {2, 4, 8}) {
			double rms = 0;
			for (float point : points) rms += point * point;
			rms = Math.sqrt(rms / Math.max(1, points.length));
			double targetRms = Math.sqrt((levels * levels - 1d) / 3d);
			double scale = targetRms / Math.max(1e-9, rms);
			double error = 0, signal = 0;
			for (float point : points) {
				double value = point * scale;
				double nearest = Math.max(-(levels - 1), Math.min(levels - 1, 2d * Math.rint((value - 1d) / 2d) + 1d));
				error += (value - nearest) * (value - nearest);
				signal += nearest * nearest;
			}
			double mer = 10d * Math.log10(signal / Math.max(1e-12, error));
			QamFit fit = new QamFit(levels == 2 ? "QPSK" : levels == 4 ? "16-QAM" : "64-QAM", mer, scale);
			if (best == null || fit.merDb > best.merDb) best = fit;
		}
		return best;
	}

	private void scalePoints(float[] points, String modulation) {
		int levels = "64-QAM".equals(modulation) ? 8 : "16-QAM".equals(modulation) ? 4 : 2;
		if (points.length == 0) return;
		float[] absolute = new float[points.length];
		for (int n = 0; n < points.length; n++) absolute[n] = Math.abs(points[n]);
		Arrays.sort(absolute);
		double median = absolute[absolute.length / 2];
		double targetMedian = levels == 8 ? 4d : levels == 4 ? 2d : 1d;
		double candidateScale = targetMedian / Math.max(1e-9, median);
		if (!modulation.equals(normalizationModulation) || Double.isNaN(normalizationScale)) {
			normalizationModulation = modulation;
			normalizationScale = candidateScale;
		} else {
			// Slow tracking removes display "breathing" while still following genuine gain changes.
			double limited = Math.max(normalizationScale * 0.9d,
					Math.min(normalizationScale * 1.1d, candidateScale));
			normalizationScale = normalizationScale * 0.98d + limited * 0.02d;
		}
		for (int n = 0; n < points.length; n++) points[n] *= normalizationScale;
	}

	private void fft(double[] real, double[] imag) {
		int size = real.length;
		for (int i = 1, j = 0; i < size; i++) { int bit = size >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit;
			if (i < j) { double v=real[i]; real[i]=real[j]; real[j]=v; v=imag[i]; imag[i]=imag[j]; imag[j]=v; } }
		for (int length=2; length<=size; length<<=1) { double angle=-2*Math.PI/length, wr0=Math.cos(angle), wi0=Math.sin(angle);
			for (int start=0; start<size; start+=length) { double wr=1, wi=0; for (int j=0; j<length/2; j++) { int a=start+j,b=a+length/2;
				double br=real[b]*wr-imag[b]*wi, bi=real[b]*wi+imag[b]*wr; real[b]=real[a]-br; imag[b]=imag[a]-bi; real[a]+=br; imag[a]+=bi;
				double nr=wr*wr0-wi*wi0; wi=wr*wi0+wi*wr0; wr=nr; } } }
	}

	private double clamp01(double value) { return Math.max(0, Math.min(1, value)); }

	private static final class Sync { final int fftSize, guard, usefulStart; final double score, correctionBins;
		Sync(int f,int g,int s,double c,double b){fftSize=f;guard=g;usefulStart=s;score=c;correctionBins=b;} }
	private static final class Complex { final double real,imag; Complex(double r,double i){real=r;imag=i;} }
	private static final class PilotFit { final int phase; final int[] pilotCarriers; final Complex[] channel; final double coherence;
		PilotFit(int p,int[] c,Complex[] h,double q){phase=p;pilotCarriers=c;channel=h;coherence=q;} }
	private static final class QamFit { final String name; final double merDb,scale; QamFit(String n,double m,double s){name=n;merDb=m;scale=s;} }
	private static final class TpsInfo { final String modulation,codeRate,mode;
		TpsInfo(String m,String c,String t){modulation=m;codeRate=c;mode=t;} }

	static final class Result {
		final float[] points; final double quality, merDb, cpCorrelation, pilotCoherence, frequencyCorrectionHz;
		final String fftMode, guard, modulation, codeRate, state;
		Result(float[] p,double q,double m,double cp,double pc,double f,String fm,String g,String mod,String cr,String s) {
			points=p;quality=q;merDb=m;cpCorrelation=cp;pilotCoherence=pc;frequencyCorrectionHz=f;fftMode=fm;guard=g;modulation=mod;codeRate=cr;state=s;
		}
		static Result empty(String state) { return new Result(new float[0],0,0,0,0,0,"--","--","--","--",state); }

		Result withSignalling(Result signalling) {
			if (signalling == null || "--".equals(signalling.codeRate)) return this;
			return new Result(points, quality, merDb, cpCorrelation, pilotCoherence, frequencyCorrectionHz,
					signalling.fftMode, signalling.guard, signalling.modulation, signalling.codeRate, state);
		}
	}
}
