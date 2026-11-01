package jspectrumanalyzer.iq;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Timeline-oriented SDS diagnostic used to correlate display observations with IQ captures. */
public final class TetraSdsCorrelationProbe {
	private static final double[] DEFAULT_EVENTS = { 52d, 109d, 169d, 185d, 231d };
	private static final int[][] DEFAULT_DISPLAY = {
		{ 1, 7 }, { 1, 5 }, { 0, 5 }, { 4, 9 }, { 3, 9 }
	};
	private static final String[] NEEDLES = {
			"line 32", "service suspended", "hlavna stanica", "hlavná stanica",
			"jelacicova", "jelačičova", "sokolska", "sokolská", "pod stanicou",
			"prazska", "pražská", "sancova", "šancová", "linka 32"
	};

	private TetraSdsCorrelationProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: TetraSdsCorrelationProbe [--frequency Hz] "
					+ "[--event seconds] [--display first,second] <IQ.wav|IQ.pcm>");
			System.exit(2);
		}
		List<Long> frequencies = new ArrayList<Long>();
		List<Double> events = new ArrayList<Double>();
		List<int[]> observations = new ArrayList<int[]>();
		File file = null;
		for (int n = 0; n < args.length; n++) {
			if ("--frequency".equals(args[n]) && n + 1 < args.length)
				frequencies.add(Long.parseLong(args[++n]));
			else if ("--event".equals(args[n]) && n + 1 < args.length)
				events.add(Double.parseDouble(args[++n]));
			else if ("--display".equals(args[n]) && n + 1 < args.length) {
				String[] values = args[++n].split(",", -1);
				if (values.length != 2) throw new IllegalArgumentException(
						"Display must be first,second");
				observations.add(new int[] { Integer.parseInt(values[0]),
						Integer.parseInt(values[1]) });
			}
			else file = new File(args[n]);
		}
		if (file == null) throw new IllegalArgumentException("Missing IQ file");
		if (frequencies.isEmpty()) {
			frequencies.add(423_825_000L);
			frequencies.add(423_900_000L);
		}
		if (events.isEmpty()) for (double event : DEFAULT_EVENTS) events.add(event);
		if (observations.isEmpty() && events.size() == DEFAULT_DISPLAY.length)
			for (int[] display : DEFAULT_DISPLAY) observations.add(display);
		if (!observations.isEmpty() && observations.size() != events.size())
			throw new IllegalArgumentException("Each --event needs one --display");
		for (long frequency : frequencies) analyze(file, frequency, events, observations);
	}

	private static void analyze(File file, long frequency, List<Double> events,
			List<int[]> observations) throws Exception {
		List<TimedMessage> messages = new ArrayList<TimedMessage>();
		TetraDecoderCore.Result result;
		long duration;
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			TetraDecoderCore decoder = new TetraDecoderCore();
			int rate = replay.getSampleRateHz();
			duration = replay.getDurationMillis();
			int blockBytes = Math.max(65_536, Math.min(524_288, rate / 8 * 2)) & ~1;
			byte[] iq = new byte[blockBytes];
			long processed = 0L;
			long epoch = IQReplayFile.getRecordingStartEpochMillis(file);
			while (processed < duration) {
				long remaining = Math.max(1L, Math.round((duration - processed) * rate / 1000d));
				int wanted = (int)Math.min(iq.length, remaining * 2L) & ~1;
				byte[] block = wanted == iq.length ? iq : new byte[wanted];
				int read = replay.readLoopedSigned(block);
				if (read <= 0) break;
				decoder.accept(replay.getCenterFrequencyHz(), frequency, rate, block, read,
						epoch + processed);
				for (TetraMacDecoder.SdsMessage message : decoder.takeSdsMessages())
					messages.add(new TimedMessage((message.timestampMillis - epoch) / 1000d,
							message));
				processed += Math.round(read / 2d / rate * 1000d);
			}
			result = decoder.latest();
		}

		Collections.sort(messages, new Comparator<TimedMessage>() {
			@Override public int compare(TimedMessage left, TimedMessage right) {
				return Double.compare(left.seconds, right.seconds);
			}
		});
		System.out.printf(Locale.US, "%n=== %.6f MHz / %.3f s ===%n",
				frequency / 1e6, duration / 1000d);
		System.out.printf(Locale.US, "lock %s, BSCH %d, SCH/F %d, SDS %d%n",
				result.systemInfo == null ? "no" : String.format(Locale.US,
						"MCC %d MNC %d CC %d", result.systemInfo.mcc,
						result.systemInfo.mnc, result.systemInfo.colourCode),
				result.decodedBsch, result.decodedSchf, messages.size());
		printInventory(messages);
		printTextSearch(messages);
		printPid192Samples(messages);
		printEventWindows(messages, events);
		printBestCandidates(messages, events);
		if (!observations.isEmpty())
			printDisplayModelCandidates(messages, events,
					observations.toArray(new int[observations.size()][]));
	}

	private static void printInventory(List<TimedMessage> messages) {
		Map<Integer, PidStats> stats = new HashMap<Integer, PidStats>();
		for (TimedMessage timed : messages) {
			TetraMacDecoder.SdsMessage message = timed.message;
			PidStats value = stats.get(message.protocol);
			if (value == null) { value = new PidStats(); stats.put(message.protocol, value); }
			value.count++;
			value.minimumBits = Math.min(value.minimumBits, message.lengthBits);
			value.maximumBits = Math.max(value.maximumBits, message.lengthBits);
			value.destinations.add(message.destination);
			value.sources.add(message.source);
		}
		List<Integer> pids = new ArrayList<Integer>(stats.keySet());
		Collections.sort(pids);
		System.out.println("PID inventory:");
		for (int pid : pids) {
			PidStats value = stats.get(pid);
			System.out.printf(Locale.US, "  PID %d: %d messages, %d..%d bits, dst %s, src %s%n",
					pid, value.count, value.minimumBits, value.maximumBits,
					value.destinations, value.sources);
		}
	}

	private static void printTextSearch(List<TimedMessage> messages) {
		int hits = 0;
		System.out.println("Known advisory text search across every individual PID payload:");
		for (TimedMessage timed : messages) {
			List<String> candidates = decodedTextCandidates(timed.message.userDataBits);
			for (String candidate : candidates) {
				String folded = fold(candidate);
				boolean known = false;
				for (String needle : NEEDLES) if (folded.contains(fold(needle))) { known = true; break; }
				if (known) {
					hits++;
					System.out.printf(Locale.US, "  %7.3f s PID %d dst %d: %s%n",
							timed.seconds, timed.message.protocol, timed.message.destination,
							visible(candidate));
				}
			}
		}
		Map<String, ByteArrayOutputStream> streams = new LinkedHashMap<String, ByteArrayOutputStream>();
		for (TimedMessage timed : messages) {
			TetraMacDecoder.SdsMessage message = timed.message;
			String key = message.protocol + "/" + message.source + "/" + message.destination;
			ByteArrayOutputStream stream = streams.get(key);
			if (stream == null) { stream = new ByteArrayOutputStream(); streams.put(key, stream); }
			byte[] bytes = pack(message.userDataBits, 0, 8);
			stream.write(bytes, 0, bytes.length);
		}
		for (Map.Entry<String, ByteArrayOutputStream> entry : streams.entrySet()) {
			byte[] bytes = entry.getValue().toByteArray();
			String[] candidates = { new String(bytes, StandardCharsets.ISO_8859_1),
					new String(bytes, Charset.forName("windows-1250")),
					new String(bytes, StandardCharsets.UTF_8), gsm7(bytes) };
			for (String candidate : candidates) {
				String folded = fold(candidate);
				boolean known = false;
				for (String needle : NEEDLES) if (folded.contains(fold(needle))) { known = true; break; }
				if (known) {
					hits++;
					System.out.printf("  concatenated %s: %s%n", entry.getKey(), visible(candidate));
				}
			}
		}
		if (hits == 0) System.out.println("  none of the supplied Slovak/English phrase fragments found");
	}

	private static void printPid192Samples(List<TimedMessage> messages) {
		Map<String, Integer> unique = new LinkedHashMap<String, Integer>();
		Map<String, Double> first = new LinkedHashMap<String, Double>();
		for (TimedMessage timed : messages) {
			if (timed.message.protocol != 192) continue;
			String key = timed.message.destination + "/" + timed.message.source + "/"
					+ timed.message.lengthBits + "/" + timed.message.dataHex();
			Integer count = unique.get(key);
			unique.put(key, count == null ? 1 : count + 1);
			if (!first.containsKey(key)) first.put(key, timed.seconds);
		}
		if (unique.isEmpty()) return;
		System.out.println("PID 192 unique payloads (first 20):");
		int shown = 0;
		for (Map.Entry<String, Integer> entry : unique.entrySet()) {
			if (shown++ >= 20) break;
			System.out.printf(Locale.US, "  %7.3f s  count %d  %s%n",
					first.get(entry.getKey()), entry.getValue(), entry.getKey());
		}
		if (unique.size() > 20) System.out.printf("  ... %d more unique payloads%n", unique.size() - 20);
	}

	private static void printEventWindows(List<TimedMessage> messages, List<Double> events) {
		for (double event : events) {
			System.out.printf(Locale.US, "%nEvent %.3f s, PID 209 within +/-5 s:%n", event);
			int count = 0;
			for (TimedMessage timed : messages) {
				if (Math.abs(timed.seconds - event) > 5d || timed.message.protocol != 209) continue;
				TetraPid209Decoder.Update update = TetraPid209Decoder.decode(timed.message);
				System.out.printf(Locale.US, "  %+6.3f s  dst %d  %s  raw %s%n",
						timed.seconds - event, timed.message.destination,
						update == null ? "unparsed" : rows(update), timed.message.dataHex());
				count++;
			}
			if (count == 0) System.out.println("  none");
		}
	}

	private static void printBestCandidates(List<TimedMessage> messages, List<Double> events) {
		Map<Integer, List<TimedMessage>> byDestination = new LinkedHashMap<Integer, List<TimedMessage>>();
		for (TimedMessage timed : messages) {
			if (timed.message.protocol != 209 || TetraPid209Decoder.decode(timed.message) == null) continue;
			List<TimedMessage> list = byDestination.get(timed.message.destination);
			if (list == null) { list = new ArrayList<TimedMessage>(); byDestination.put(
					timed.message.destination, list); }
			list.add(timed);
		}
		List<DestinationScore> scores = new ArrayList<DestinationScore>();
		for (Map.Entry<Integer, List<TimedMessage>> entry : byDestination.entrySet()) {
			double total = 0d; int close = 0;
			for (double event : events) {
				double nearest = Double.POSITIVE_INFINITY;
				for (TimedMessage timed : entry.getValue()) nearest = Math.min(nearest,
						Math.abs(timed.seconds - event));
				if (nearest <= 5d) close++;
				total += Math.min(30d, nearest);
			}
			scores.add(new DestinationScore(entry.getKey(), close, total, entry.getValue()));
		}
		Collections.sort(scores, new Comparator<DestinationScore>() {
			@Override public int compare(DestinationScore left, DestinationScore right) {
				int close = Integer.compare(right.closeEvents, left.closeEvents);
				return close != 0 ? close : Double.compare(left.totalDistance, right.totalDistance);
			}
		});
		System.out.println("\nBest PID 209 destinations by temporal proximity:");
		for (int n = 0; n < Math.min(12, scores.size()); n++) {
			DestinationScore score = scores.get(n);
			System.out.printf(Locale.US, "  dst %d: %d/%d events within 5 s; updates",
					score.destination, score.closeEvents, events.size());
			for (TimedMessage timed : score.messages) {
				TetraPid209Decoder.Update update = TetraPid209Decoder.decode(timed.message);
				System.out.printf(Locale.US, "  %.3f=%s", timed.seconds, rows(update));
			}
			System.out.println();
		}
	}

	private static void printDisplayModelCandidates(List<TimedMessage> messages,
			List<Double> events, int[][] observations) {
		Map<ModelKey, List<TimedUpdate>> histories = new LinkedHashMap<ModelKey, List<TimedUpdate>>();
		for (TimedMessage timed : messages) {
			if (timed.message.protocol != 209) continue;
			TetraPid209Decoder.Update update = TetraPid209Decoder.decode(timed.message);
			if (update == null) continue;
			Map<RouteKey, List<TetraPid209Decoder.Arrival>> groups =
					new LinkedHashMap<RouteKey, List<TetraPid209Decoder.Arrival>>();
			for (TetraPid209Decoder.Arrival arrival : update.arrivals) {
				RouteKey route = new RouteKey(arrival.fieldA, arrival.fieldB);
				List<TetraPid209Decoder.Arrival> rows = groups.get(route);
				if (rows == null) {
					rows = new ArrayList<TetraPid209Decoder.Arrival>();
					groups.put(route, rows);
				}
				rows.add(arrival);
			}
			groups.put(new RouteKey(-1, -1), update.arrivals);
			for (Map.Entry<RouteKey, List<TetraPid209Decoder.Arrival>> group : groups.entrySet()) {
				ModelKey key = new ModelKey(timed.message.destination, group.getKey());
				List<TimedUpdate> history = histories.get(key);
				if (history == null) {
					history = new ArrayList<TimedUpdate>();
					histories.put(key, history);
				}
				history.add(new TimedUpdate(timed.seconds, group.getValue()));
			}
		}
		List<ModelScore> scores = new ArrayList<ModelScore>();
		for (Map.Entry<ModelKey, List<TimedUpdate>> entry : histories.entrySet()) {
			List<ModeledState> states = new ArrayList<ModeledState>();
			double score = 0d;
			int covered = 0;
			for (int n = 0; n < events.size(); n++) {
				double event = events.get(n);
				TimedUpdate latest = null;
				for (TimedUpdate update : entry.getValue())
					if (update.seconds <= event + 5d) latest = update;
				if (latest == null || event - latest.seconds > 90d) {
					score += 30d;
					states.add(null);
					continue;
				}
				ModeledState state = bestState(latest, event, observations[n]);
				states.add(state);
				score += state.error;
				covered++;
			}
			if (covered >= 3) scores.add(new ModelScore(entry.getKey(), score, covered, states));
		}
		Collections.sort(scores, new Comparator<ModelScore>() {
			@Override public int compare(ModelScore left, ModelScore right) {
				int coverage = Integer.compare(right.covered, left.covered);
				return coverage != 0 ? coverage : Double.compare(left.score, right.score);
			}
		});
		StringBuilder expected = new StringBuilder();
		for (int[] observation : observations) {
			if (expected.length() > 0) expected.append(" -> ");
			expected.append(observation[0]).append(',').append(observation[1]);
		}
		System.out.println("\nPID 209 state-model candidates for display " + expected + ':');
		for (int n = 0; n < Math.min(20, scores.size()); n++) {
			ModelScore candidate = scores.get(n);
			String route = candidate.key.route.fieldA < 0 ? "ALL"
					: String.format(Locale.US, "%04X/%04X", candidate.key.route.fieldA,
							candidate.key.route.fieldB);
			System.out.printf(Locale.US, "  dst %d route %s score %.1f:",
					candidate.key.destination, route, candidate.score);
			for (ModeledState state : candidate.states) {
				if (state == null) System.out.print("  missing");
				else System.out.printf(Locale.US, "  %.3fs(age %.1f)=%s",
						state.updateSeconds, state.ageSeconds, Arrays.toString(state.values));
			}
			System.out.println();
		}
		if (!scores.isEmpty()) {
			int destination = scores.get(0).key.destination;
			System.out.printf("Top candidate dst %d complete PID 209 timeline:%n", destination);
			for (TimedMessage timed : messages) {
				if (timed.message.protocol != 209 || timed.message.destination != destination) continue;
				TetraPid209Decoder.Update update = TetraPid209Decoder.decode(timed.message);
				System.out.printf(Locale.US, "  %7.3f s  %s  raw %s%n", timed.seconds,
						update == null ? "unparsed" : rows(update), timed.message.dataHex());
			}
		}
	}

	private static ModeledState bestState(TimedUpdate update, double event, int[] target) {
		List<Integer> raw = new ArrayList<Integer>();
		for (TetraPid209Decoder.Arrival row : update.rows)
			if (row.timeValue >= 0 && row.timeValue <= 120) raw.add(row.timeValue);
		Collections.sort(raw);
		double age = Math.max(0d, event - update.seconds);
		int wholeMinutes = (int)Math.floor(age / 60d);
		ModeledState best = null;
		for (int decrement = wholeMinutes; decrement <= wholeMinutes + 1; decrement++) {
			int[] values = new int[raw.size()];
			for (int n = 0; n < raw.size(); n++)
				values[n] = Math.max(0, raw.get(n) - decrement);
			double error = pairError(values, target);
			ModeledState state = new ModeledState(update.seconds, age, values, error);
			if (best == null || state.error < best.error) best = state;
		}
		return best == null ? new ModeledState(update.seconds, age, new int[0], 20d) : best;
	}

	private static double pairError(int[] values, int[] target) {
		if (values.length < 2) return 20d;
		double best = Double.POSITIVE_INFINITY;
		for (int first = 0; first < values.length; first++) {
			for (int second = first + 1; second < values.length; second++) {
				double error = Math.abs(values[first] - target[0])
						+ Math.abs(values[second] - target[1]);
				best = Math.min(best, error);
			}
		}
		return best;
	}

	private static String rows(TetraPid209Decoder.Update update) {
		StringBuilder text = new StringBuilder(String.format(Locale.US, "fmt %02X", update.formatByte));
		if (update.variantByte >= 0) text.append(String.format(Locale.US, "/%02X", update.variantByte));
		text.append(" seq ").append(update.counterCandidate).append(" [");
		for (TetraPid209Decoder.Arrival row : update.arrivals) {
			if (text.charAt(text.length() - 1) != '[') text.append("; ");
			text.append(String.format(Locale.US, "%04X/%04X t=%d f=%02X",
					row.fieldA, row.fieldB, row.timeValue, row.flags));
			if (row.extensionCode >= 0) text.append(String.format(Locale.US, " x=%04X",
					row.extensionCode));
		}
		return text.append(']').toString();
	}

	private static List<String> decodedTextCandidates(int[] bits) {
		Set<String> found = new LinkedHashSet<String>();
		for (int shift = 0; shift < 8; shift++) {
			byte[] bytes = pack(bits, shift, 8);
			add(found, new String(bytes, StandardCharsets.ISO_8859_1));
			add(found, new String(bytes, Charset.forName("windows-1250")));
			add(found, new String(bytes, StandardCharsets.UTF_8));
			if (bytes.length >= 4) {
				add(found, new String(bytes, StandardCharsets.UTF_16BE));
				add(found, new String(bytes, StandardCharsets.UTF_16LE));
			}
		}
		for (int shift = 0; shift < 7; shift++) add(found, gsm7(pack(bits, shift, 7)));
		return new ArrayList<String>(found);
	}

	private static void add(Set<String> found, String value) {
		if (value != null && !value.isEmpty()) found.add(value);
	}

	private static byte[] pack(int[] bits, int start, int width) {
		int count = Math.max(0, (bits.length - start) / width);
		byte[] result = new byte[count];
		for (int n = 0; n < count; n++) {
			int value = 0;
			for (int bit = 0; bit < width; bit++)
				value = value << 1 | bits[start + n * width + bit] & 1;
			result[n] = (byte)value;
		}
		return result;
	}

	private static String gsm7(byte[] values) {
		StringBuilder text = new StringBuilder(values.length);
		for (byte raw : values) {
			int value = raw & 0x7f;
			text.append(value >= 32 && value <= 126 ? (char)value : ' ');
		}
		return text.toString();
	}

	private static String fold(String value) {
		String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT),
				Normalizer.Form.NFD);
		return normalized.replaceAll("\\p{M}+", "");
	}

	private static String visible(String value) {
		StringBuilder text = new StringBuilder();
		for (int n = 0; n < value.length() && text.length() < 240; n++) {
			char ch = value.charAt(n);
			if (ch == '\r') text.append("\\r");
			else if (ch == '\n') text.append("\\n");
			else if (ch == '\t') text.append("\\t");
			else if (Character.isISOControl(ch)) text.append('.');
			else text.append(ch);
		}
		return text.toString();
	}

	private static final class TimedMessage {
		final double seconds; final TetraMacDecoder.SdsMessage message;
		TimedMessage(double seconds, TetraMacDecoder.SdsMessage message) {
			this.seconds = seconds; this.message = message;
		}
	}
	private static final class PidStats {
		int count, minimumBits = Integer.MAX_VALUE, maximumBits;
		final Set<Integer> destinations = new LinkedHashSet<Integer>();
		final Set<Integer> sources = new LinkedHashSet<Integer>();
	}
	private static final class DestinationScore {
		final int destination, closeEvents; final double totalDistance;
		final List<TimedMessage> messages;
		DestinationScore(int destination, int closeEvents, double totalDistance,
				List<TimedMessage> messages) {
			this.destination = destination; this.closeEvents = closeEvents;
			this.totalDistance = totalDistance; this.messages = messages;
		}
	}
	private static final class RouteKey {
		final int fieldA, fieldB;
		RouteKey(int fieldA, int fieldB) { this.fieldA = fieldA; this.fieldB = fieldB; }
		@Override public boolean equals(Object other) {
			if (!(other instanceof RouteKey)) return false;
			RouteKey that = (RouteKey)other;
			return fieldA == that.fieldA && fieldB == that.fieldB;
		}
		@Override public int hashCode() { return fieldA * 65537 + fieldB; }
	}
	private static final class ModelKey {
		final int destination; final RouteKey route;
		ModelKey(int destination, RouteKey route) { this.destination = destination; this.route = route; }
		@Override public boolean equals(Object other) {
			if (!(other instanceof ModelKey)) return false;
			ModelKey that = (ModelKey)other;
			return destination == that.destination && route.equals(that.route);
		}
		@Override public int hashCode() { return destination * 31 + route.hashCode(); }
	}
	private static final class TimedUpdate {
		final double seconds; final List<TetraPid209Decoder.Arrival> rows;
		TimedUpdate(double seconds, List<TetraPid209Decoder.Arrival> rows) {
			this.seconds = seconds;
			this.rows = new ArrayList<TetraPid209Decoder.Arrival>(rows);
		}
	}
	private static final class ModeledState {
		final double updateSeconds, ageSeconds, error; final int[] values;
		ModeledState(double updateSeconds, double ageSeconds, int[] values, double error) {
			this.updateSeconds = updateSeconds; this.ageSeconds = ageSeconds;
			this.values = values; this.error = error;
		}
	}
	private static final class ModelScore {
		final ModelKey key; final double score; final int covered; final List<ModeledState> states;
		ModelScore(ModelKey key, double score, int covered, List<ModeledState> states) {
			this.key = key; this.score = score; this.covered = covered; this.states = states;
		}
	}
}
