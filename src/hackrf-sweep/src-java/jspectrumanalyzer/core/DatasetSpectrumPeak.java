package jspectrumanalyzer.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.jfree.data.xy.XYSeries;

import jspectrumanalyzer.core.jfc.XYSeriesImmutable;

public class DatasetSpectrumPeak extends DatasetSpectrum
{
	protected long		lastAdded			= System.currentTimeMillis();
	protected long		peakFalloutMillis	= 1000;
	protected float		peakFallThreshold;
	protected int		iteration			= 0;
	protected float[]	sumVal;
	protected float[][]	spectrumVal;
	protected int		avgIterations;
	protected int		avgOffset;
	
	/**
	 * stores EMA decaying peaks
	 */
	protected float[]	spectrumPeak;

	/**
	 * stores real peaks and if {@link #spectrumPeak} falls more than preset value below it, start using values from {@link #spectrumPeak}
	 */
	protected float[]	spectrumMaxHold;
	protected float[]	spectrumPeakHold;
	protected float[]	spectrumAverage;
	
	public DatasetSpectrumPeak(float fftBinSizeHz, int freqStartMHz, int freqStopMHz, float spectrumInitPower, float peakFallThreshold, long peakFalloutMillis, int freqShift, int avgIterations, int avgOffset)
	{
		super(fftBinSizeHz, freqStartMHz, freqStopMHz, spectrumInitPower, freqShift);

		this.peakFalloutMillis = peakFalloutMillis;
		this.spectrumInitPower = spectrumInitPower;
		this.peakFallThreshold = peakFallThreshold;
		this.avgIterations = avgIterations;
		this.avgOffset = avgOffset;
		int datapoints = (int) (Math.ceil(freqStopMHz - freqStartMHz) * 1000000d / fftBinSizeHz);
		spectrum = new float[datapoints];
		Arrays.fill(spectrum, spectrumInitPower);
		spectrumPeak = new float[datapoints];
		Arrays.fill(spectrumPeak, spectrumInitPower);
		spectrumPeakHold = new float[datapoints];
		Arrays.fill(spectrumPeakHold, spectrumInitPower);
		spectrumMaxHold = new float[datapoints];
		Arrays.fill(spectrumMaxHold, spectrumInitPower);
		spectrumAverage = new float[datapoints];
		Arrays.fill(spectrumAverage, spectrumInitPower);
		spectrumVal = new float[avgIterations][datapoints];
		for (float[] row : spectrumVal) 
            Arrays.fill(row, spectrumInitPower);
		sumVal = new float[datapoints];
		Arrays.fill(sumVal, avgIterations * spectrumInitPower);
		
	}

	public void setPeakFalloutMillis(long peakFalloutMillis) {
		this.peakFalloutMillis = peakFalloutMillis;
	}
	
	public void setPeakFallThreshold(int peakFallThreshold) {
		this.peakFallThreshold = peakFallThreshold;
	}
	
	public void setAvgIterations(int avgIterations) {
		this.avgIterations = avgIterations;
	}
	
	public void setAvgOffset(int avgOffset) {
		this.avgOffset = avgOffset;
	}
	
	public void copyTo(DatasetSpectrumPeak filtered)
	{
		super.copyTo(filtered);
		System.arraycopy(spectrumPeak, 0, filtered.spectrumPeak, 0, spectrumPeak.length);
		System.arraycopy(spectrumPeakHold, 0, filtered.spectrumPeakHold, 0, spectrumPeakHold.length);
	}

	/**
	 * Fills data to {@link XYSeries}, uses x units in MHz
	 * @param series
	 */
	public void fillPeaksToXYSeries(XYSeries series)
	{
		fillToXYSeriesPriv(series, spectrumPeakHold);
//		fillToXYSeriesPriv(series, spectrumPeak);
	}
	
	public XYSeriesImmutable createPeaksDataset(String name) {
		float[] xValues	= new float[spectrum.length];
		float[] yValues	= spectrumPeakHold;
		for (int i = 0; i < spectrum.length; i++)
		{
			float freq = (freqStartHz + fftBinSizeHz * i) / 1000000f;
			xValues[i]	= freq + freqShift;
		}
		XYSeriesImmutable xySeriesF	= new XYSeriesImmutable(name, xValues, yValues);
		return xySeriesF;
	}

	public XYSeriesImmutable createMaxHoldDataset(String name) {
		float[] xValues	= new float[spectrum.length];
		float[] yValues	= spectrumMaxHold;
		for (int i = 0; i < spectrum.length; i++)
		{
			float freq = (freqStartHz + fftBinSizeHz * i) / 1000000f;
			xValues[i]	= freq + freqShift;
		}
		XYSeriesImmutable xySeriesF	= new XYSeriesImmutable(name, xValues, yValues);
		return xySeriesF;
	}
	
	public XYSeriesImmutable createAverageDataset(String name) {
		float[] xValues	= new float[spectrum.length];
		float[] yValues	= spectrumAverage;
		for (int i = 0; i < spectrum.length; i++)
		{
			float freq = (freqStartHz + fftBinSizeHz * i) / 1000000f;
			xValues[i]	= freq + freqShift;
		}
		XYSeriesImmutable xySeriesF	= new XYSeriesImmutable(name, xValues, yValues);
		return xySeriesF;
	}
	
	public double calculateSpectrumPeakPower(){
		double powerSum	= 0;
		for (int i = 0; i < spectrumPeakHold.length; i++) {
			if (spectrumPeakHold[i] > -90) {powerSum += Math.pow(10, spectrumPeakHold[i]/10);} /*convert dB to mW to sum power in linear form*/
		}
		powerSum	= 10*Math.log10(powerSum); /*convert back to dB*/ 
		return powerSum;
	}
	
	private long debugLastPeakRerfreshTime	= 0;
	public void refreshPeakSpectrum()
	{
		if (false) {
			long debugMinPeakRefreshTime	= 100;
			if (System.currentTimeMillis()-debugLastPeakRerfreshTime < debugMinPeakRefreshTime)
				return;
			debugLastPeakRerfreshTime	= System.currentTimeMillis();
		}
		
		long timeDiffFromPrevValueMillis = System.currentTimeMillis() - lastAdded;
		if (timeDiffFromPrevValueMillis < 1)
			timeDiffFromPrevValueMillis = 1;
		
		lastAdded = System.currentTimeMillis();
		
//		peakFallThreshold = 10;
//		peakFalloutMillis	= 30000;
		for (int spectrIndex = 0; spectrIndex < spectrum.length; spectrIndex++)
		{
			float spectrumVal = spectrum[spectrIndex];
			if (spectrumVal > spectrumPeakHold[spectrIndex])
			{
				spectrumPeakHold[spectrIndex] = spectrumPeak[spectrIndex] = spectrumVal;
			}
			spectrumPeak[spectrIndex] = (float) EMA.calculateTimeDependent(spectrumVal, spectrumPeak[spectrIndex], timeDiffFromPrevValueMillis, peakFalloutMillis);

			if (spectrumPeakHold[spectrIndex] - spectrumPeak[spectrIndex] > peakFallThreshold)
			{
				spectrumPeakHold[spectrIndex] = spectrumPeak[spectrIndex];
			}
		}
	}
	
	public void refreshMaxHoldSpectrum()
	{
		for (int spectrIndex = 0; spectrIndex < spectrum.length; spectrIndex++)
		{
			if (spectrum[spectrIndex] > spectrumMaxHold[spectrIndex])
			{
				spectrumMaxHold[spectrIndex] = spectrum[spectrIndex];
			}
		}
	}
	
	public void refreshAverageSpectrum()
	{
		for (int spectrIndex = 0; spectrIndex < spectrum.length; spectrIndex++)
		{
			float previousVal = spectrumVal[iteration][spectrIndex];
			spectrumVal[iteration][spectrIndex] = spectrum[spectrIndex];
			sumVal[spectrIndex] = sumVal[spectrIndex] + spectrum[spectrIndex] - previousVal;
			spectrumAverage[spectrIndex] = sumVal[spectrIndex]/avgIterations + avgOffset;
		}
		iteration++;
		if (iteration == avgIterations) { iteration = 0; }
	}
	
	private float getMax(float[] inputArray){ 
	   float maxValue = inputArray[0]; 
	   for(int i=1;i < inputArray.length;i++){ 
	      if(inputArray[i] > maxValue){ 
	         maxValue = inputArray[i]; 
	      } 
	   } 
	   return maxValue; 
	}

	public void resetPeaks()
	{
		Arrays.fill(spectrumPeak, spectrumInitPower);
		Arrays.fill(spectrumPeakHold, spectrumInitPower);
	}
	
	public void resetMaxHold()
	{
		Arrays.fill(spectrumMaxHold, spectrumInitPower);
	}
	
	public void resetAverage()
	{
		Arrays.fill(spectrumAverage, spectrumInitPower);
	}

	@Override protected Object clone() throws CloneNotSupportedException
	{
		DatasetSpectrumPeak copy = (DatasetSpectrumPeak) super.clone();
		copy.spectrumPeakHold = spectrumPeakHold.clone();
		copy.spectrumPeak = spectrumPeak.clone();
		return super.clone();
	}

}
