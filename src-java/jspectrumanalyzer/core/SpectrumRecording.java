package jspectrumanalyzer.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Spectrum recording writer and backwards-compatible HSR reader. */
public class SpectrumRecording implements Closeable {
	/* HSR1/HSR2 are one logical stream inside a single GZIP member. */
	private static final int LEGACY_MAGIC = 0x48535231; // HSR1
	private static final int LEGACY_VERSION = 2;

	/* HSR3 is an uncompressed container of independently compressed chunks. */
	private static final long FILE_MAGIC_1 = 0x4853523346494c45L; // HSR3FILE
	private static final long FILE_MAGIC_2 = 0x2d4348554e4b4544L; // -CHUNKED
	private static final long CHUNK_MAGIC_1 = 0x4853523343484e4bL; // HSR3CHNK
	private static final long CHUNK_MAGIC_2 = 0x535045435452554dL; // SPECTRUM
	private static final long INDEX_MAGIC_1 = 0x48535233494e4458L; // HSR3INDX
	private static final long INDEX_MAGIC_2 = 0x535045435452554dL; // SPECTRUM
	private static final int CHUNK_VERSION = 1;
	private static final int INDEX_VERSION = 1;
	private static final long DEFAULT_CHUNK_MILLIS = 5_000L;
	private static final int MAX_CHUNK_UNCOMPRESSED = 8 * 1024 * 1024;
	private static final int MAX_HEADER_BYTES = 1024 * 1024;
	private static final int MAX_INDEX_BYTES = 64 * 1024 * 1024;
	private static final int PENDING_CHUNK_CAPACITY = 4;
	private static final float DEFAULT_MIN_DB = -150f;
	private static final float DEFAULT_DB_STEP = 0.55f;

	private static final byte[] CHUNK_MAGIC_BYTES = longPairBytes(CHUNK_MAGIC_1, CHUNK_MAGIC_2);

	public static class Header {
		public final int freqStartMHz;
		public final int freqStopMHz;
		public final float fftBinHz;
		public final int freqShift;
		public final int binCount;
		public final float minDb;
		public final float dbStep;
		public final String ranges;
		public final long startEpochMillis;

		private Header(int freqStartMHz, int freqStopMHz, float fftBinHz, int freqShift, int binCount,
				float minDb, float dbStep, String ranges, long startEpochMillis) {
			this.freqStartMHz = freqStartMHz;
			this.freqStopMHz = freqStopMHz;
			this.fftBinHz = fftBinHz;
			this.freqShift = freqShift;
			this.binCount = binCount;
			this.minDb = minDb;
			this.dbStep = dbStep;
			this.ranges = ranges == null ? "" : ranges;
			this.startEpochMillis = startEpochMillis;
		}
	}

	public static class Frame {
		public final long timeOffsetMillis;
		public final float[] spectrum;

		private Frame(long timeOffsetMillis, float[] spectrum) {
			this.timeOffsetMillis = timeOffsetMillis;
			this.spectrum = spectrum;
		}
	}

	private static final class ChunkInfo {
		final long payloadOffset;
		final int compressedLength;
		final int uncompressedLength;
		final int frameCount;
		final long firstOffsetMillis;
		final long lastOffsetMillis;
		final Header header;

		ChunkInfo(long payloadOffset, int compressedLength, int uncompressedLength, int frameCount,
				long firstOffsetMillis, long lastOffsetMillis, Header header) {
			this.payloadOffset = payloadOffset;
			this.compressedLength = compressedLength;
			this.uncompressedLength = uncompressedLength;
			this.frameCount = frameCount;
			this.firstOffsetMillis = firstOffsetMillis;
			this.lastOffsetMillis = lastOffsetMillis;
			this.header = header;
		}
	}

	private static final class PendingChunk {
		final ByteArrayOutputStream bytes;
		final int frameCount;
		final long firstOffsetMillis;
		final long lastOffsetMillis;
		final boolean end;

		PendingChunk(ByteArrayOutputStream bytes, int frameCount, long firstOffsetMillis,
				long lastOffsetMillis, boolean end) {
			this.bytes = bytes;
			this.frameCount = frameCount;
			this.firstOffsetMillis = firstOffsetMillis;
			this.lastOffsetMillis = lastOffsetMillis;
			this.end = end;
		}
	}

	private static final class RandomAccessSliceInputStream extends InputStream {
		private final RandomAccessFile file;
		private long remaining;

		RandomAccessSliceInputStream(RandomAccessFile file, long length) {
			this.file = file;
			this.remaining = length;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0)
				return -1;
			int value = file.read();
			if (value >= 0)
				remaining--;
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (remaining <= 0)
				return -1;
			int requested = (int) Math.min(remaining, length);
			int count = file.read(bytes, offset, requested);
			if (count > 0)
				remaining -= count;
			return count;
		}
	}

	public static class Reader implements Closeable {
		private DataInputStream legacyIn;
		private RandomAccessFile chunkFile;
		private Header header;
		private final List<ChunkInfo> chunks = new ArrayList<ChunkInfo>();
		private DataInputStream chunkIn;
		private int nextChunkIndex;
		private int framesRemaining;
		private long offsetBaseMillis;
		private long durationMillis = -1;

		public Reader(File file) throws IOException {
			RandomAccessFile random = new RandomAccessFile(file, "r");
			boolean keepRandom = false;
			try {
				if (isChunkedFile(random)) {
					chunkFile = random;
					keepRandom = true;
					openChunked(true);
					return;
				}
				if (isGzipFile(random)) {
					random.close();
					openLegacy(file);
					return;
				}

				/* A hard crop can remove both the file header and part of a chunk. */
				chunkFile = random;
				keepRandom = true;
				openChunked(false);
			} catch (IOException e) {
				closeAfterOpenFailure();
				throw e;
			} catch (RuntimeException e) {
				closeAfterOpenFailure();
				throw e;
			} finally {
				if (!keepRandom) {
					try {
						random.close();
					} catch (IOException ignored) {
					}
				}
			}
		}

		private void closeAfterOpenFailure() {
			try {
				close();
			} catch (IOException ignored) {
			}
		}

		private void openLegacy(File file) throws IOException {
			legacyIn = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))));
			int magic = legacyIn.readInt();
			int version = legacyIn.readInt();
			if (magic != LEGACY_MAGIC || (version != 1 && version != LEGACY_VERSION))
				throw new IOException("Unsupported spectrum recording format");
			int freqStartMHz = legacyIn.readInt();
			int freqStopMHz = legacyIn.readInt();
			float fftBinHz = legacyIn.readFloat();
			int freqShift = legacyIn.readInt();
			int binCount = legacyIn.readInt();
			float minDb = legacyIn.readFloat();
			float dbStep = legacyIn.readFloat();
			String ranges = legacyIn.readUTF();
			long startEpochMillis = version >= 2 ? legacyIn.readLong() : 0;
			header = new Header(freqStartMHz, freqStopMHz, fftBinHz, freqShift, binCount,
					minDb, dbStep, ranges, startEpochMillis);
		}

		private void openChunked(boolean hasFileHeader) throws IOException {
			long chunksStart = 0;
			Header fileHeader = null;
			if (hasFileHeader) {
				chunkFile.seek(16);
				int headerLength = chunkFile.readInt();
				if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES)
					throw new IOException("Invalid HSR3 header length");
				byte[] headerBytes = new byte[headerLength];
				chunkFile.readFully(headerBytes);
				int expectedCrc = chunkFile.readInt();
				if (crc32(headerBytes) != expectedCrc)
					throw new IOException("Invalid HSR3 header checksum");
				fileHeader = decodeHeader(headerBytes);
				chunksStart = chunkFile.getFilePointer();
			}

			if (fileHeader == null || !readIndex(fileHeader, chunksStart))
				scanChunks(chunksStart, fileHeader);
			if (chunks.isEmpty()) {
				if (fileHeader == null)
					throw new IOException("Unsupported or incomplete spectrum recording format");
				header = fileHeader;
				durationMillis = 0;
				return;
			}

			if (fileHeader == null) {
				ChunkInfo first = chunks.get(0);
				offsetBaseMillis = first.firstOffsetMillis;
				header = shiftedHeader(first.header, offsetBaseMillis);
			} else {
				header = fileHeader;
			}
			durationMillis = Math.max(0,
					chunks.get(chunks.size() - 1).lastOffsetMillis - offsetBaseMillis);
		}

		private boolean readIndex(Header fileHeader, long chunksStart) throws IOException {
			final int trailerLength = 4 + 8 + 8 + 4;
			long fileLength = chunkFile.length();
			if (fileLength < chunksStart + trailerLength)
				return false;
			chunkFile.seek(fileLength - trailerLength);
			int expectedCrc = chunkFile.readInt();
			if (chunkFile.readLong() != INDEX_MAGIC_1 || chunkFile.readLong() != INDEX_MAGIC_2)
				return false;
			int bodyLength = chunkFile.readInt();
			if (bodyLength < 8 || bodyLength > MAX_INDEX_BYTES
					|| bodyLength > fileLength - chunksStart - trailerLength)
				return false;
			long bodyStart = fileLength - trailerLength - bodyLength;
			chunkFile.seek(bodyStart);
			byte[] body = new byte[bodyLength];
			chunkFile.readFully(body);
			if (crc32(body) != expectedCrc)
				return false;

			DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
			if (in.readInt() != INDEX_VERSION)
				return false;
			int count = in.readInt();
			if (count < 0 || 8L + count * 36L != bodyLength)
				return false;
			List<ChunkInfo> indexed = new ArrayList<ChunkInfo>(count);
			long previousPayload = -1;
			long previousLastTime = -1;
			for (int i = 0; i < count; i++) {
				long payloadOffset = in.readLong();
				int compressedLength = in.readInt();
				int uncompressedLength = in.readInt();
				int frameCount = in.readInt();
				long firstOffsetMillis = in.readLong();
				long lastOffsetMillis = in.readLong();
				long expectedLength = (long) frameCount * (8L + fileHeader.binCount);
				if (payloadOffset < chunksStart || payloadOffset <= previousPayload
						|| compressedLength <= 0 || payloadOffset + compressedLength > bodyStart
						|| frameCount <= 0 || uncompressedLength != expectedLength
						|| firstOffsetMillis < 0 || lastOffsetMillis < firstOffsetMillis
						|| lastOffsetMillis < previousLastTime)
					return false;
				indexed.add(new ChunkInfo(payloadOffset, compressedLength, uncompressedLength, frameCount,
						firstOffsetMillis, lastOffsetMillis, fileHeader));
				previousPayload = payloadOffset;
				previousLastTime = lastOffsetMillis;
			}
			chunks.addAll(indexed);
			return true;
		}

		private void scanChunks(long start, Header expectedHeader) throws IOException {
			long searchAt = start;
			Header effectiveHeader = expectedHeader;
			long fileLength = chunkFile.length();
			while (searchAt + CHUNK_MAGIC_BYTES.length <= fileLength) {
				long marker = findChunkMagic(searchAt);
				if (marker < 0)
					break;
				ChunkInfo chunk = readChunkInfo(marker);
				if (chunk == null) {
					searchAt = marker + 1;
					continue;
				}
				long payloadEnd = chunk.payloadOffset + chunk.compressedLength;
				if (payloadEnd > fileLength)
					break; // A valid header followed by an incomplete final chunk.
				if (effectiveHeader == null)
					effectiveHeader = chunk.header;
				if (!headersCompatible(effectiveHeader, chunk.header)) {
					searchAt = marker + 1;
					continue;
				}
				if (!chunks.isEmpty()
						&& chunk.lastOffsetMillis < chunks.get(chunks.size() - 1).lastOffsetMillis) {
					searchAt = marker + 1;
					continue;
				}
				chunks.add(chunk);
				searchAt = payloadEnd;
			}
		}

		private ChunkInfo readChunkInfo(long marker) throws IOException {
			try {
				chunkFile.seek(marker);
				if (chunkFile.readLong() != CHUNK_MAGIC_1 || chunkFile.readLong() != CHUNK_MAGIC_2)
					return null;
				int version = chunkFile.readInt();
				int bodyLength = chunkFile.readInt();
				if (version != CHUNK_VERSION || bodyLength <= 0 || bodyLength > MAX_HEADER_BYTES)
					return null;
				byte[] body = new byte[bodyLength];
				chunkFile.readFully(body);
				int expectedCrc = chunkFile.readInt();
				if (crc32(body) != expectedCrc)
					return null;

				DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
				int compressedLength = in.readInt();
				int uncompressedLength = in.readInt();
				int frameCount = in.readInt();
				long firstOffsetMillis = in.readLong();
				long lastOffsetMillis = in.readLong();
				int metadataLength = in.readInt();
				if (metadataLength <= 0 || metadataLength > MAX_HEADER_BYTES || metadataLength > in.available())
					return null;
				byte[] metadata = new byte[metadataLength];
				in.readFully(metadata);
				if (in.available() != 0)
					return null;
				Header chunkHeader;
				try {
					chunkHeader = decodeHeader(metadata);
				} catch (IOException e) {
					return null;
				}
				long expectedLength = (long) frameCount * (8L + chunkHeader.binCount);
				if (compressedLength <= 0 || uncompressedLength <= 0 || frameCount <= 0
						|| firstOffsetMillis < 0 || lastOffsetMillis < firstOffsetMillis
						|| expectedLength != uncompressedLength)
					return null;
				return new ChunkInfo(chunkFile.getFilePointer(), compressedLength, uncompressedLength,
						frameCount, firstOffsetMillis, lastOffsetMillis, chunkHeader);
			} catch (EOFException e) {
				return null;
			}
		}

		private long findChunkMagic(long start) throws IOException {
			chunkFile.seek(Math.max(0, start));
			int matched = 0;
			long position = Math.max(0, start);
			int value;
			while ((value = chunkFile.read()) >= 0) {
				byte current = (byte) value;
				if (current == CHUNK_MAGIC_BYTES[matched]) {
					matched++;
					if (matched == CHUNK_MAGIC_BYTES.length)
						return position - CHUNK_MAGIC_BYTES.length + 1;
				} else {
					matched = current == CHUNK_MAGIC_BYTES[0] ? 1 : 0;
				}
				position++;
			}
			return -1;
		}

		public Header getHeader() {
			return header;
		}

		/** Returns the indexed duration for HSR3, or -1 for legacy HSR1/HSR2. */
		public long getDurationMillis() {
			return durationMillis;
		}

		public Frame readFrame() throws IOException {
			if (legacyIn != null)
				return readLegacyFrame();
			while (framesRemaining == 0) {
				finishChunk();
				if (nextChunkIndex >= chunks.size())
					return null;
				loadChunk(chunks.get(nextChunkIndex++));
			}

			try {
				long timeOffsetMillis = chunkIn.readLong() - offsetBaseMillis;
				float[] spectrum = readSpectrum(chunkIn, header);
				framesRemaining--;
				if (framesRemaining == 0)
					finishChunk();
				return new Frame(timeOffsetMillis, spectrum);
			} catch (EOFException e) {
				throw new IOException("Incomplete HSR3 chunk", e);
			}
		}

		/** Positions at and returns the first frame at or after the requested time. */
		public Frame seekToTime(long timeOffsetMillis) throws IOException {
			long target = Math.max(0, timeOffsetMillis);
			if (legacyIn != null) {
				Frame frame;
				while ((frame = readLegacyFrame()) != null) {
					if (frame.timeOffsetMillis >= target)
						return frame;
				}
				return null;
			}

			closeChunkInput();
			long absoluteTarget = target + offsetBaseMillis;
			int low = 0;
			int high = chunks.size();
			while (low < high) {
				int middle = (low + high) >>> 1;
				if (chunks.get(middle).lastOffsetMillis < absoluteTarget)
					low = middle + 1;
				else
					high = middle;
			}
			nextChunkIndex = low;
			Frame frame;
			while ((frame = readFrame()) != null) {
				if (frame.timeOffsetMillis >= target)
					return frame;
			}
			return null;
		}

		private Frame readLegacyFrame() throws IOException {
			try {
				long timeOffsetMillis = legacyIn.readLong();
				return new Frame(timeOffsetMillis, readSpectrum(legacyIn, header));
			} catch (EOFException eof) {
				return null;
			}
		}

		private void loadChunk(ChunkInfo chunk) throws IOException {
			chunkFile.seek(chunk.payloadOffset);
			InputStream compressed = new RandomAccessSliceInputStream(chunkFile, chunk.compressedLength);
			chunkIn = new DataInputStream(new BufferedInputStream(
					new GZIPInputStream(compressed, 64 * 1024), 64 * 1024));
			framesRemaining = chunk.frameCount;
		}

		private void finishChunk() throws IOException {
			if (chunkIn == null || framesRemaining != 0)
				return;
			if (chunkIn.read() != -1)
				throw new IOException("HSR3 chunk contains unexpected trailing data");
			closeChunkInput();
		}

		private void closeChunkInput() throws IOException {
			if (chunkIn != null) {
				chunkIn.close();
				chunkIn = null;
			}
			framesRemaining = 0;
		}

		@Override
		public void close() throws IOException {
			IOException failure = null;
			try {
				closeChunkInput();
			} catch (IOException e) {
				failure = e;
			}
			if (legacyIn != null) {
				try {
					legacyIn.close();
				} catch (IOException e) {
					if (failure == null)
						failure = e;
				}
			}
			if (chunkFile != null) {
				try {
					chunkFile.close();
				} catch (IOException e) {
					if (failure == null)
						failure = e;
				}
			}
			if (failure != null)
				throw failure;
		}
	}

	private final DataOutputStream out;
	private final long startedMillis;
	private final Header header;
	private final long chunkDurationMillis;
	private ByteArrayOutputStream chunkBytes = new ByteArrayOutputStream();
	private DataOutputStream chunkData = new DataOutputStream(chunkBytes);
	private int chunkFrameCount;
	private long chunkFirstOffsetMillis;
	private long chunkLastOffsetMillis;
	private long lastFrameOffsetMillis = -1;
	private long outputPosition;
	private final List<ChunkInfo> writtenChunks = new ArrayList<ChunkInfo>();
	private final ArrayBlockingQueue<PendingChunk> pendingChunks =
			new ArrayBlockingQueue<PendingChunk>(PENDING_CHUNK_CAPACITY);
	private final Thread chunkWriterThread;
	private volatile IOException writerFailure;
	private boolean closed;

	public SpectrumRecording(File file, DatasetSpectrum spectrum, String ranges) throws IOException {
		this(file, spectrum, ranges, DEFAULT_CHUNK_MILLIS);
	}

	/* Package-private duration override keeps chunk-boundary tests deterministic. */
	SpectrumRecording(File file, DatasetSpectrum spectrum, String ranges, long chunkDurationMillis) throws IOException {
		if (chunkDurationMillis <= 0)
			throw new IllegalArgumentException("Chunk duration must be positive");
		this.startedMillis = System.currentTimeMillis();
		this.chunkDurationMillis = chunkDurationMillis;
		this.header = new Header(spectrum.getFreqStartMHz(), spectrum.getFreqStopMHz(),
				spectrum.getFFTBinSizeHz(), spectrum.getFreqShift(), spectrum.spectrumLength(),
				DEFAULT_MIN_DB, DEFAULT_DB_STEP, ranges, startedMillis);
		this.out = new DataOutputStream(new FileOutputStream(file));
		writeFileHeader();
		chunkWriterThread = new Thread(this::chunkWriterLoop, "HSR chunk writer");
		chunkWriterThread.setDaemon(true);
		chunkWriterThread.start();
	}

	private void writeFileHeader() throws IOException {
		byte[] metadata = encodeHeader(header);
		out.writeLong(FILE_MAGIC_1);
		out.writeLong(FILE_MAGIC_2);
		out.writeInt(metadata.length);
		out.write(metadata);
		out.writeInt(crc32(metadata));
		out.flush();
		outputPosition = 16L + 4 + metadata.length + 4;
	}

	public synchronized void writeFrame(DatasetSpectrum spectrum) throws IOException {
		long offset = Math.max(0, System.currentTimeMillis() - startedMillis);
		writeFrameAt(spectrum, Math.max(offset, lastFrameOffsetMillis));
	}

	/* Package-private explicit timestamp is useful for deterministic format tests. */
	synchronized void writeFrameAt(DatasetSpectrum spectrum, long timeOffsetMillis) throws IOException {
		if (closed)
			throw new IOException("Spectrum recording is closed");
		checkWriterFailure();
		float[] values = spectrum.getSpectrumArray();
		if (values.length != header.binCount)
			throw new IOException("Spectrum size changed during recording");
		if (timeOffsetMillis < 0 || timeOffsetMillis < lastFrameOffsetMillis)
			throw new IOException("Spectrum frame timestamps must be monotonic");

		int frameLength = 8 + values.length;
		if (chunkFrameCount > 0 && (timeOffsetMillis - chunkFirstOffsetMillis >= chunkDurationMillis
				|| (long) chunkBytes.size() + frameLength > MAX_CHUNK_UNCOMPRESSED))
			flushChunk();

		if (chunkFrameCount == 0)
			chunkFirstOffsetMillis = timeOffsetMillis;
		chunkLastOffsetMillis = timeOffsetMillis;
		chunkData.writeLong(timeOffsetMillis);
		writeQuantizedSpectrum(chunkData, values, header);
		chunkFrameCount++;
		lastFrameOffsetMillis = timeOffsetMillis;
	}

	private void flushChunk() throws IOException {
		if (chunkFrameCount == 0)
			return;
		chunkData.flush();
		PendingChunk pending = new PendingChunk(chunkBytes, chunkFrameCount,
				chunkFirstOffsetMillis, chunkLastOffsetMillis, false);
		chunkBytes = new ByteArrayOutputStream();
		chunkData = new DataOutputStream(chunkBytes);
		chunkFrameCount = 0;
		enqueueChunk(pending);
	}

	private void writeChunk(PendingChunk pending) throws IOException {
		byte[] raw = pending.bytes.toByteArray();
		byte[] compressed = gzip(raw);
		byte[] metadata = encodeHeader(header);

		ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
		DataOutputStream body = new DataOutputStream(bodyBytes);
		body.writeInt(compressed.length);
		body.writeInt(raw.length);
		body.writeInt(pending.frameCount);
		body.writeLong(pending.firstOffsetMillis);
		body.writeLong(pending.lastOffsetMillis);
		body.writeInt(metadata.length);
		body.write(metadata);
		body.close();
		byte[] chunkHeader = bodyBytes.toByteArray();
		long payloadOffset = outputPosition + 16 + 4 + 4 + chunkHeader.length + 4;

		out.writeLong(CHUNK_MAGIC_1);
		out.writeLong(CHUNK_MAGIC_2);
		out.writeInt(CHUNK_VERSION);
		out.writeInt(chunkHeader.length);
		out.write(chunkHeader);
		out.writeInt(crc32(chunkHeader));
		out.write(compressed);
		out.flush();
		writtenChunks.add(new ChunkInfo(payloadOffset, compressed.length, raw.length, pending.frameCount,
				pending.firstOffsetMillis, pending.lastOffsetMillis, header));
		outputPosition = payloadOffset + compressed.length;
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed)
			return;
		closed = true;
		IOException failure = null;
		try {
			flushChunk();
		} catch (IOException e) {
			failure = e;
		}
		try {
			enqueueEnd();
		} catch (IOException e) {
			if (failure == null)
				failure = e;
		}
		try {
			chunkWriterThread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (failure == null)
				failure = new IOException("Interrupted while closing spectrum recording", e);
		}
		if (failure == null)
			failure = writerFailure;
		if (failure != null)
			throw failure;
	}

	private void enqueueChunk(PendingChunk chunk) throws IOException {
		checkWriterFailure();
		try {
			pendingChunks.put(chunk);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while queueing HSR chunk", e);
		}
		checkWriterFailure();
	}

	private void enqueueEnd() throws IOException {
		try {
			pendingChunks.put(new PendingChunk(null, 0, 0, 0, true));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while stopping HSR chunk writer", e);
		}
	}

	private void checkWriterFailure() throws IOException {
		IOException failure = writerFailure;
		if (failure != null)
			throw failure;
	}

	private void chunkWriterLoop() {
		boolean canWrite = true;
		try {
			while (true) {
				PendingChunk pending;
				try {
					pending = pendingChunks.take();
				} catch (InterruptedException e) {
					setWriterFailure(new IOException("HSR chunk writer was interrupted", e));
					canWrite = false;
					continue;
				}
				if (pending.end)
					break;
				if (canWrite) {
					try {
						writeChunk(pending);
					} catch (IOException e) {
						setWriterFailure(e);
						canWrite = false;
					} catch (RuntimeException e) {
						setWriterFailure(new IOException("HSR chunk writer failed", e));
						canWrite = false;
					}
				}
			}
			if (canWrite) {
				try {
					writeIndex();
				} catch (IOException e) {
					setWriterFailure(e);
				}
			}
		} finally {
			try {
				out.close();
			} catch (IOException e) {
				setWriterFailure(e);
			}
		}
	}

	private void setWriterFailure(IOException failure) {
		if (writerFailure == null)
			writerFailure = failure;
	}

	private void writeIndex() throws IOException {
		ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
		DataOutputStream body = new DataOutputStream(bodyBytes);
		body.writeInt(INDEX_VERSION);
		body.writeInt(writtenChunks.size());
		for (ChunkInfo chunk : writtenChunks) {
			body.writeLong(chunk.payloadOffset);
			body.writeInt(chunk.compressedLength);
			body.writeInt(chunk.uncompressedLength);
			body.writeInt(chunk.frameCount);
			body.writeLong(chunk.firstOffsetMillis);
			body.writeLong(chunk.lastOffsetMillis);
		}
		body.close();
		byte[] bytes = bodyBytes.toByteArray();
		out.write(bytes);
		out.writeInt(crc32(bytes));
		out.writeLong(INDEX_MAGIC_1);
		out.writeLong(INDEX_MAGIC_2);
		out.writeInt(bytes.length);
		out.flush();
		outputPosition += bytes.length + 4L + 8 + 8 + 4;
	}

	private static float[] readSpectrum(DataInputStream in, Header header) throws IOException {
		float[] spectrum = new float[header.binCount];
		for (int i = 0; i < spectrum.length; i++) {
			int quantized = in.readUnsignedByte();
			spectrum[i] = header.minDb + quantized * header.dbStep;
		}
		return spectrum;
	}

	private static void writeQuantizedSpectrum(DataOutputStream out, float[] values, Header header)
			throws IOException {
		for (int i = 0; i < values.length; i++) {
			int quantized = Math.round((values[i] - header.minDb) / header.dbStep);
			if (quantized < 0)
				quantized = 0;
			if (quantized > 255)
				quantized = 255;
			out.writeByte(quantized);
		}
	}

	private static byte[] encodeHeader(Header header) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(header.freqStartMHz);
		out.writeInt(header.freqStopMHz);
		out.writeFloat(header.fftBinHz);
		out.writeInt(header.freqShift);
		out.writeInt(header.binCount);
		out.writeFloat(header.minDb);
		out.writeFloat(header.dbStep);
		out.writeUTF(header.ranges);
		out.writeLong(header.startEpochMillis);
		out.close();
		return bytes.toByteArray();
	}

	private static Header decodeHeader(byte[] bytes) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
		int freqStartMHz = in.readInt();
		int freqStopMHz = in.readInt();
		float fftBinHz = in.readFloat();
		int freqShift = in.readInt();
		int binCount = in.readInt();
		float minDb = in.readFloat();
		float dbStep = in.readFloat();
		String ranges = in.readUTF();
		long startEpochMillis = in.readLong();
		if (in.available() != 0 || binCount <= 0 || !Float.isFinite(fftBinHz)
				|| !Float.isFinite(minDb) || !Float.isFinite(dbStep) || fftBinHz <= 0 || dbStep <= 0)
			throw new IOException("Invalid HSR spectrum metadata");
		return new Header(freqStartMHz, freqStopMHz, fftBinHz, freqShift, binCount,
				minDb, dbStep, ranges, startEpochMillis);
	}

	private static Header shiftedHeader(Header header, long offsetMillis) {
		return new Header(header.freqStartMHz, header.freqStopMHz, header.fftBinHz, header.freqShift,
				header.binCount, header.minDb, header.dbStep, header.ranges,
				header.startEpochMillis <= 0 ? 0 : header.startEpochMillis + offsetMillis);
	}

	private static boolean headersCompatible(Header a, Header b) {
		return a.freqStartMHz == b.freqStartMHz && a.freqStopMHz == b.freqStopMHz
				&& Float.floatToIntBits(a.fftBinHz) == Float.floatToIntBits(b.fftBinHz)
				&& a.freqShift == b.freqShift && a.binCount == b.binCount
				&& Float.floatToIntBits(a.minDb) == Float.floatToIntBits(b.minDb)
				&& Float.floatToIntBits(a.dbStep) == Float.floatToIntBits(b.dbStep)
				&& a.ranges.equals(b.ranges) && a.startEpochMillis == b.startEpochMillis;
	}

	private static boolean isChunkedFile(RandomAccessFile file) throws IOException {
		if (file.length() < 16)
			return false;
		file.seek(0);
		return file.readLong() == FILE_MAGIC_1 && file.readLong() == FILE_MAGIC_2;
	}

	private static boolean isGzipFile(RandomAccessFile file) throws IOException {
		if (file.length() < 2)
			return false;
		file.seek(0);
		return file.readUnsignedByte() == 0x1f && file.readUnsignedByte() == 0x8b;
	}

	private static int crc32(byte[] bytes) {
		CRC32 crc = new CRC32();
		crc.update(bytes);
		return (int) crc.getValue();
	}

	private static byte[] gzip(byte[] raw) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		GZIPOutputStream gzip = new GZIPOutputStream(bytes);
		gzip.write(raw);
		gzip.close();
		return bytes.toByteArray();
	}

	private static byte[] longPairBytes(long first, long second) {
		byte[] bytes = new byte[16];
		for (int i = 0; i < 8; i++) {
			bytes[i] = (byte) (first >>> (56 - i * 8));
			bytes[i + 8] = (byte) (second >>> (56 - i * 8));
		}
		return bytes;
	}
}
