package jspectrumanalyzer.core;

/**
 * Describes one frequency allocation band by start frequency and end frequency
 */
public class FrequencyBand implements Comparable<FrequencyBand>{
	private final long hzStartIncl;
	private final long hzEndExcl;
	private final String name;
	private final String color;
	
	public FrequencyBand(long hzStartIncl, long hzEndExcl, String name, String color) {
		this.hzStartIncl = hzStartIncl;
		this.hzEndExcl = hzEndExcl;
		this.name = name;
		this.color = color;
	}
	public long getHzStartIncl() {
		return hzStartIncl;
	}
	
	public double getMHzStartIncl() {
		return hzStartIncl/1000000d;
	}
	
	public long getHzEndExcl() {
		return hzEndExcl;
	}
	public double getMHzEndExcl() {
		return hzEndExcl/1000000d;
	}
	public String getName() {
		return name;
	}
	public String getColor() {
		return color;
	}
	
	@Override
	public String toString() {
		return name;
	}
	
	@Override
	public int compareTo(FrequencyBand o) {
		return Long.compare(hzStartIncl, o.hzStartIncl);
	}
	
}