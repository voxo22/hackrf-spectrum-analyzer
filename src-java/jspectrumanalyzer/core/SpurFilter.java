package jspectrumanalyzer.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * Simple spur removal filter.
 */
public class SpurFilter
{
	private static final float			SPUR_MASK_EPSILON_DB		= 0.1f;
	private static final float			SPUR_CLAMP_HEADROOM_DB		= 0.75f;
	private static final float			SPUR_CLAMP_THRESHOLD_DB		= 1.0f;
	private static final float			SPUR_EDGE_THRESHOLD_RATIO	= 0.5f;
	private static final int				REFERENCE_MIN_BINS		= 4;
	private final DatasetSpectrum		avgSpectrum;
	private boolean						calibrated		= false;
	private int							debug			= 0;
	/**
	 * contains spur correction power values 
	 */
	private final DatasetSpectrum		filter;
	private ArrayList<DatasetSpectrum>	filterInputs	= new ArrayList<>();
	private final DatasetSpectrum		input;
	private final int					maxPeakBins;
	/**
	 * to be marked as a spur, the power value fft bin 
	 * should not fall outside of this variable's value 
	 * from average value during calibration   
	 */
	private final float					maxPeakJitterdB;
	private final DatasetSpectrum		noiseFloor;
	private final float					peakThresholdAboveNoise;
	private final int					validIterations;

	public SpurFilter(float maxPeakJitterdB, float peakThresholdAboveNoise, int maxPeakBins, int validIterations, DatasetSpectrum input)
	{
		this.maxPeakJitterdB = maxPeakJitterdB;
		this.peakThresholdAboveNoise = peakThresholdAboveNoise;
		this.maxPeakBins = maxPeakBins;
		this.validIterations = validIterations;
		this.input = input;
		this.filter = new DatasetSpectrum(input.getFFTBinSizeHz(), input.getFreqStartMHz(), input.getFreqStopMHz(), 0, input.getFreqShift());
		this.avgSpectrum = input.cloneMe();
		this.noiseFloor = avgSpectrum.cloneMe();
	}

	/**
	 * Filters input dataset with the calibrated filter data
	 */
	public void filterDataset()
	{
		if (input.getFFTBinSizeHz() != filter.getFFTBinSizeHz() || input.spectrumLength() != filter.spectrumLength())
		{
			throw new IllegalArgumentException("Input dataset not the same size as output dataset");
		}

		if (calibrated == false)
		{
			calibrate();
			return;
		}

		filterDatasetExec();

		return;
	}

	public boolean isFilterCalibrated()
	{
		return calibrated;
	}

	public void filterCenterSpikeHz(long centerFrequencyHz)
	{
		float input[] = this.input.getSpectrumArray();
		if (input.length == 0)
		{
			return;
		}
		int centerIndex = findNearestSpectrumIndex(centerFrequencyHz);
		if (centerIndex < 0)
		{
			return;
		}
		int peakIndex = findLocalPeakIndex(input, centerIndex, Math.max(2, maxPeakBins));
		int[] spikeRange = findCenterSpikeRange(input, peakIndex);
		int start = spikeRange[0];
		int end = spikeRange[1];
		smoothSpurGroup(input, null, start, end);
	}

	public void recalibrate()
	{
		calibrated = false;
		filterInputs.clear();
	}

	private int findNearestSpectrumIndex(long frequencyHz)
	{
		double index = (frequencyHz - input.freqStartHz) / (double) input.getFFTBinSizeHz();
		int rounded = (int) Math.round(index);
		if (rounded < 0 || rounded >= input.spectrumLength())
		{
			return -1;
		}
		return rounded;
	}

	private int findLocalPeakIndex(float[] input, int centerIndex, int searchRadius)
	{
		int start = Math.max(0, centerIndex - searchRadius);
		int end = Math.min(input.length - 1, centerIndex + searchRadius);
		int peakIndex = centerIndex;
		for (int i = start; i <= end; i++)
		{
			if (input[i] > input[peakIndex])
			{
				peakIndex = i;
			}
		}
		return peakIndex;
	}

	private int[] findCenterSpikeRange(float[] input, int peakIndex)
	{
		int guardBins = Math.max(1, maxPeakBins);
		int referenceBins = Math.max(REFERENCE_MIN_BINS, maxPeakBins * 4);
		float localFloor = collectReferenceMedian(input, (boolean[]) null, peakIndex, guardBins, referenceBins);
		if (Float.isNaN(localFloor))
		{
			return new int[] { peakIndex, peakIndex };
		}

		float edgeThreshold = Math.max(SPUR_CLAMP_THRESHOLD_DB, peakThresholdAboveNoise * SPUR_EDGE_THRESHOLD_RATIO);
		int maxHalfWidth = Math.max(1, maxPeakBins * 2);
		int start = peakIndex;
		for (int step = 1; step <= maxHalfWidth; step++)
		{
			int index = peakIndex - step;
			if (index < 0 || input[index] - localFloor < edgeThreshold)
			{
				break;
			}
			start = index;
		}
		int end = peakIndex;
		for (int step = 1; step <= maxHalfWidth; step++)
		{
			int index = peakIndex + step;
			if (index >= input.length || input[index] - localFloor < edgeThreshold)
			{
				break;
			}
			end = index;
		}
		return new int[] { start, end };
	}

	private void calibrate()
	{
		if (calibrated)
			return;
		filterInputs.add(input.cloneMe());

		//int validIterations	= 20;
		//float peakThresholdAboveNoise	= 4;
		//float maxPeakJitterdB	= 6;

		if (filterInputs.size() >= validIterations)
		{
			/**
			 * trigger calibration
			 */
			avgSpectrum.setSpectrumInitPower(0);
			avgSpectrum.resetSpectrum();

			/**
			 * use different noise floor for different portions of the spectrum
			 * due to non-linear sensitivity in different bands 
			 */
			float[] noiseFloorArr = noiseFloor.getSpectrumArray();

			/**
			 * Calculate average values 
			 */
			float[] avgSpectrArray = avgSpectrum.getSpectrumArray();
			for (DatasetSpectrum datasetSpectrum : filterInputs)
			{
				float[] spectr = datasetSpectrum.getSpectrumArray();
				for (int i = 0; i < spectr.length; i++)
				{
					avgSpectrArray[i] += spectr[i];
				}
			}
			for (int i = 0; i < avgSpectrArray.length; i++)
			{
				avgSpectrArray[i] /= validIterations;
			}

			LinkedList<Integer> spurIndexes = new LinkedList<>();
			int end = avgSpectrArray.length - maxPeakBins;
			double emaNoiseFloor = avgSpectrArray[0];
			double emaNoiseFloorOrder = Math.max(5, noiseFloorArr.length / 50);

			Arrays.fill(noiseFloorArr, 0, maxPeakBins, (float) emaNoiseFloor);
			/**
			 * find spur candidates
			 */
			for (int i = maxPeakBins; i < end; i++)
			{
				float currAvgVal = avgSpectrArray[i];

				boolean triggered1 = false;
				for (int j = i - maxPeakBins; j < i && triggered1 == false; j++)
				{
					//					if (currAvgVal - avgSpectrArray[j] >= peakThresholdAboveNoise)
					if (currAvgVal - emaNoiseFloor >= peakThresholdAboveNoise)
						triggered1 = true;
				}

				boolean triggered2 = false;
				for (int j = i + 1; j <= i + maxPeakBins && triggered2 == false; j++)
				{
					//					if (currAvgVal - avgSpectrArray[j] >= peakThresholdAboveNoise)
					if (currAvgVal - emaNoiseFloor >= peakThresholdAboveNoise)
						triggered2 = true;
				}

				if (triggered1 && triggered2)
				{
					spurIndexes.add(i);
				}
				else
				{
					//update only with not spur values
					emaNoiseFloor = EMA.calculate(currAvgVal, emaNoiseFloor, emaNoiseFloorOrder);
				}
				noiseFloorArr[i] = (float) emaNoiseFloor;
			}
			Arrays.fill(noiseFloorArr, end, noiseFloorArr.length, (float) emaNoiseFloor);
			addLocalMedianSpurCandidates(spurIndexes, avgSpectrArray);

			/**
			 * check if spurs candidates all fit below maxPeakJitterdB
			 * spurs should be more or less stable
			 */
			for (Iterator<Integer> iterator = spurIndexes.iterator(); iterator.hasNext();)
			{
				Integer spurIndex = iterator.next();
				boolean valid = true;
				for (DatasetSpectrum datasetSpectrum : filterInputs)
				{
					float[] spectr = datasetSpectrum.getSpectrumArray();
					float diff = Math.abs(spectr[spurIndex] - avgSpectrArray[spurIndex]);
					if (diff > maxPeakJitterdB)
					{
						valid = false;
						break;
					}
				}
				if (!valid)
				{
					iterator.remove();
					continue;
				}
			}
			expandSpurIndexes(spurIndexes, avgSpectrArray, noiseFloorArr);

			Arrays.fill(filter.getSpectrumArray(), 0);
			for (Integer s : spurIndexes)
			{
				float spurAboveNoise = avgSpectrArray[s] - noiseFloorArr[s];
				filter.getSpectrumArray()[s] = spurAboveNoise;
			}
			calibrated = true;
			return;
		}
		else
			calibrated = false;
	}

	private void addLocalMedianSpurCandidates(LinkedList<Integer> spurIndexes, float[] spectrum)
	{
		boolean[] spurMask = createSpurMask(spurIndexes, spectrum.length);
		int guardBins = Math.max(1, maxPeakBins);
		int referenceBins = Math.max(REFERENCE_MIN_BINS, maxPeakBins * 4);
		int end = spectrum.length - guardBins;
		for (int i = guardBins; i < end; i++)
		{
			float localFloor = collectReferenceMedian(spectrum, spurMask, i, guardBins, referenceBins);
			if (!Float.isNaN(localFloor) && spectrum[i] - localFloor >= peakThresholdAboveNoise)
			{
				addSpurIndex(spurIndexes, spurMask, i);
			}
		}
	}

	private void expandSpurIndexes(LinkedList<Integer> spurIndexes, float[] avgSpectrArray, float[] noiseFloorArr)
	{
		boolean[] spurMask = createSpurMask(spurIndexes, avgSpectrArray.length);
		int i = 0;
		while (i < spurMask.length)
		{
			if (!spurMask[i])
			{
				i++;
				continue;
			}
			int start = i;
			while (i + 1 < spurMask.length && spurMask[i + 1])
			{
				i++;
			}
			int end = i;
			expandSpurGroup(spurIndexes, spurMask, avgSpectrArray, noiseFloorArr, start, end);
			i++;
		}
	}

	private void expandSpurGroup(LinkedList<Integer> spurIndexes, boolean[] spurMask, float[] avgSpectrArray,
			float[] noiseFloorArr, int start, int end)
	{
		float threshold = Math.max(1f, peakThresholdAboveNoise * SPUR_EDGE_THRESHOLD_RATIO);
		int maxExpansion = Math.max(1, maxPeakBins);
		for (int step = 1; step <= maxExpansion; step++)
		{
			int index = start - step;
			if (index < 0 || spurMask[index])
			{
				break;
			}
			if (avgSpectrArray[index] - noiseFloorArr[index] < threshold)
			{
				break;
			}
			addSpurIndex(spurIndexes, spurMask, index);
		}
		for (int step = 1; step <= maxExpansion; step++)
		{
			int index = end + step;
			if (index >= spurMask.length || spurMask[index])
			{
				break;
			}
			if (avgSpectrArray[index] - noiseFloorArr[index] < threshold)
			{
				break;
			}
			addSpurIndex(spurIndexes, spurMask, index);
		}
	}

	private boolean[] createSpurMask(LinkedList<Integer> spurIndexes, int length)
	{
		boolean[] spurMask = new boolean[length];
		for (Integer index : spurIndexes)
		{
			if (index != null && index >= 0 && index < spurMask.length)
			{
				spurMask[index] = true;
			}
		}
		return spurMask;
	}

	private void addSpurIndex(LinkedList<Integer> spurIndexes, boolean[] spurMask, int index)
	{
		if (index < 0 || index >= spurMask.length || spurMask[index])
		{
			return;
		}
		spurMask[index] = true;
		spurIndexes.add(index);
	}

	private void filterDatasetExec()
	{
		float input[] = this.input.getSpectrumArray();
		float filterSpectrum[] = filter.getSpectrumArray();
		/**
		 * 0 = normal operation
		 * 1 = output averaged spectrum
		 * 2 = noise floor
		 * 3 = noise floor + output spur corrections
		 */
		debug = 0;
		if (debug == 0)
		{
			smoothSpursToLocalFloor(input, filterSpectrum);
		}
		else
		{
			if (debug == 1)
			{
				for (int i = 0; i < input.length; i++)
				{
					input[i] = avgSpectrum.getSpectrumArray()[i];
				}
			}
			else if (debug == 2)
			{
				for (int i = 0; i < input.length; i++)
				{
					input[i] = noiseFloor.getSpectrumArray()[i];
				}
			}
			else if (debug == 3)
			{
				for (int i = 0; i < input.length; i++)
				{
					input[i] = noiseFloor.getSpectrumArray()[i] + filter.getSpectrumArray()[i];
				}
			}
			else if (debug == 4)
			{

			}
		}
	}

	private void smoothSpursToLocalFloor(float[] input, float[] filterSpectrum)
	{
		int i = 0;
		while (i < input.length)
		{
			if (!isSpurBin(filterSpectrum, i))
			{
				i++;
				continue;
			}

			int start = i;
			while (i + 1 < input.length && isSpurBin(filterSpectrum, i + 1))
			{
				i++;
			}
			int end = i;
			smoothSpurGroup(input, filterSpectrum, start, end);
			i++;
		}
	}

	private void smoothSpurGroup(float[] input, float[] filterSpectrum, int start, int end)
	{
		int guardBins = Math.max(1, maxPeakBins / 2);
		int referenceBins = Math.max(REFERENCE_MIN_BINS, maxPeakBins * 4);
		float leftFloor = collectReferenceMedian(input, filterSpectrum, start - guardBins - 1, -1, referenceBins);
		float rightFloor = collectReferenceMedian(input, filterSpectrum, end + guardBins + 1, 1, referenceBins);

		if (Float.isNaN(leftFloor) && Float.isNaN(rightFloor))
		{
			return;
		}
		if (Float.isNaN(leftFloor))
		{
			leftFloor = rightFloor;
		}
		if (Float.isNaN(rightFloor))
		{
			rightFloor = leftFloor;
		}

		int width = end - start + 1;
		for (int index = start; index <= end; index++)
		{
			float fraction = (index - start + 1) / (float) (width + 1);
			float localFloor = leftFloor + (rightFloor - leftFloor) * fraction;
			float clampLevel = localFloor + SPUR_CLAMP_HEADROOM_DB;
			if (input[index] > clampLevel + SPUR_CLAMP_THRESHOLD_DB)
			{
				input[index] = clampLevel;
			}
		}
	}

	private boolean isSpurBin(float[] filterSpectrum, int index)
	{
		if (filterSpectrum == null)
		{
			return false;
		}
		return filterSpectrum[index] > SPUR_MASK_EPSILON_DB;
	}

	private float collectReferenceMedian(float[] input, float[] filterSpectrum, int start, int direction, int maxBins)
	{
		float[] values = new float[maxBins];
		int count = 0;
		for (int index = start; index >= 0 && index < input.length && count < maxBins; index += direction)
		{
			if (isSpurBin(filterSpectrum, index) || Float.isNaN(input[index]) || Float.isInfinite(input[index]))
			{
				continue;
			}
			values[count++] = input[index];
		}
		if (count == 0)
		{
			return Float.NaN;
		}
		Arrays.sort(values, 0, count);
		if ((count & 1) == 1)
		{
			return values[count / 2];
		}
		return (values[count / 2 - 1] + values[count / 2]) * 0.5f;
	}

	private float collectReferenceMedian(float[] spectrum, boolean[] excludeMask, int centerIndex, int guardBins,
			int maxBinsPerSide)
	{
		float[] values = new float[maxBinsPerSide * 2];
		int count = collectReferenceValues(spectrum, excludeMask, centerIndex - guardBins - 1, -1, maxBinsPerSide,
				values, 0);
		count = collectReferenceValues(spectrum, excludeMask, centerIndex + guardBins + 1, 1, maxBinsPerSide,
				values, count);
		if (count == 0)
		{
			return Float.NaN;
		}
		Arrays.sort(values, 0, count);
		if ((count & 1) == 1)
		{
			return values[count / 2];
		}
		return (values[count / 2 - 1] + values[count / 2]) * 0.5f;
	}

	private int collectReferenceValues(float[] spectrum, boolean[] excludeMask, int start, int direction, int maxBins,
			float[] output, int count)
	{
		int collected = 0;
		for (int index = start; index >= 0 && index < spectrum.length && collected < maxBins; index += direction)
		{
			if ((excludeMask != null && excludeMask[index]) || Float.isNaN(spectrum[index])
					|| Float.isInfinite(spectrum[index]))
			{
				continue;
			}
			output[count++] = spectrum[index];
			collected++;
		}
		return count;
	}
}
