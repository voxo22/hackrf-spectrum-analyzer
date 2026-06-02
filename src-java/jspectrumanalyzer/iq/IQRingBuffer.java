package jspectrumanalyzer.iq;

public class IQRingBuffer {
	private final byte[] buffer;
	private long totalBytesWritten = 0;

	public IQRingBuffer(int capacityBytes) {
		if (capacityBytes < 2) {
			throw new IllegalArgumentException("capacityBytes must be at least 2");
		}
		if ((capacityBytes & 1) != 0) {
			capacityBytes++;
		}
		this.buffer = new byte[capacityBytes];
	}

	public synchronized void write(byte[] data) {
		if (data == null || data.length == 0) {
			return;
		}
		int length = data.length;
		if (length > buffer.length) {
			int offset = length - buffer.length;
			System.arraycopy(data, offset, buffer, 0, buffer.length);
			totalBytesWritten += length;
			return;
		}

		int writeIndex = (int) (totalBytesWritten % buffer.length);
		int firstPart = Math.min(length, buffer.length - writeIndex);
		System.arraycopy(data, 0, buffer, writeIndex, firstPart);
		if (firstPart < length) {
			System.arraycopy(data, firstPart, buffer, 0, length - firstPart);
		}
		totalBytesWritten += length;
	}

	public synchronized int readLatest(byte[] destination, int requestedBytes) {
		if (destination == null || requestedBytes <= 0 || totalBytesWritten <= 0) {
			return 0;
		}

		int available = (int) Math.min(Math.min(totalBytesWritten, buffer.length),
				Math.min(destination.length, requestedBytes));
		if ((available & 1) != 0) {
			available--;
		}
		if (available <= 0) {
			return 0;
		}

		long start = totalBytesWritten - available;
		int readIndex = (int) (start % buffer.length);
		int firstPart = Math.min(available, buffer.length - readIndex);
		System.arraycopy(buffer, readIndex, destination, 0, firstPart);
		if (firstPart < available) {
			System.arraycopy(buffer, 0, destination, firstPart, available - firstPart);
		}
		return available;
	}

	public synchronized int readLatestEnvelopeMax(int[] destination, int bins, int requestedSamples) {
		if (destination == null || bins <= 0 || requestedSamples <= 0 || totalBytesWritten <= 0) {
			return 0;
		}
		bins = Math.min(bins, destination.length);
		int availableSamples = (int) Math.min(Math.min(totalBytesWritten / 2, buffer.length / 2), requestedSamples);
		if (availableSamples <= 0) {
			return 0;
		}

		long startSample = totalBytesWritten / 2 - availableSamples;
		int maxSamplesPerBin = 8;
		for (int bin = 0; bin < bins; bin++) {
			int firstSample = bin * availableSamples / bins;
			int lastSample = (bin + 1) * availableSamples / bins;
			if (lastSample <= firstSample) {
				lastSample = Math.min(availableSamples, firstSample + 1);
			}
			int span = lastSample - firstSample;
			int stride = Math.max(1, span / maxSamplesPerBin);
			int maxPower = 0;
			for (int sample = firstSample; sample < lastSample; sample += stride) {
				int byteIndex = (int) (((startSample + sample) * 2) % buffer.length);
				int i = buffer[byteIndex];
				int q = buffer[(byteIndex + 1) % buffer.length];
				int power = i * i + q * q;
				if (power > maxPower) {
					maxPower = power;
				}
			}
			destination[bin] = (int) Math.round(Math.sqrt(maxPower));
		}
		return availableSamples;
	}

	public synchronized long getTotalBytesWritten() {
		return totalBytesWritten;
	}

	public int capacityBytes() {
		return buffer.length;
	}
}
