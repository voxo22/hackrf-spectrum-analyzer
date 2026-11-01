package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.JScrollBar;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Live TETRA acquisition header and decoded SDS event stream. */
final class TetraAnalyzerPanel extends JPanel {
	/* Retain a useful PID/SDS session history without unbounded Swing-model growth. */
	private static final int MAX_ROWS = 50_000;
	private final JLabel parameters = new JLabel();
	private final JButton cellDetailsButton = new JButton("CELL DETAILS");
	private final JCheckBox logCheck = new JCheckBox("LOG");
	private TetraCellDetailsFrame cellDetailsFrame;
	private TetraDecoderCore.Result latestResult =
			TetraDecoderCore.Result.empty("Waiting for TETRA IQ samples");
	private final DefaultTableModel model = new DefaultTableModel(
			new Object[] { "Time", "PID", "DL SSI", "Calling SSI", "UDT", "Bits",
					"Data", "Position", "Text candidate" }, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			return column >= 1 && column <= 5 ? Integer.class : String.class;
		}
	};
	private final JTable table = new JTable(model);
	private final JScrollPane tableScroll = new JScrollPane(table);
	private final TetraVoiceCallTablePanel voiceCalls = new TetraVoiceCallTablePanel();
	private final JTabbedPane tabs = new JTabbedPane();
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
	private final SimpleDateFormat logTimeFormat = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
	private final SimpleDateFormat fileTimeFormat = new SimpleDateFormat(
			"yyyy-MM-dd HH-mm-ss", Locale.ROOT);
	private BufferedWriter logWriter;
	private Path logPath;
	private long latestCenterHz;

	TetraAnalyzerPanel() {
		super(new BorderLayout(0, 7));
		setBackground(Color.BLACK);
		parameters.setOpaque(true);
		parameters.setBackground(Color.BLACK);
		parameters.setForeground(Color.WHITE);
		parameters.setVerticalAlignment(SwingConstants.TOP);
		parameters.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Color.DARK_GRAY), new EmptyBorder(8, 12, 8, 12)));
		parameters.setFont(parameters.getFont().deriveFont(Font.PLAIN, 12f));
		table.setBackground(new Color(0x101010));
		table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x303030));
		table.setSelectionBackground(new Color(0x245070));
		table.setFillsViewportHeight(true);
		table.setAutoCreateRowSorter(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(90);
		table.getColumnModel().getColumn(1).setPreferredWidth(55);
		table.getColumnModel().getColumn(2).setPreferredWidth(85);
		table.getColumnModel().getColumn(3).setPreferredWidth(85);
		table.getColumnModel().getColumn(4).setPreferredWidth(45);
		table.getColumnModel().getColumn(5).setPreferredWidth(55);
		table.getColumnModel().getColumn(6).setPreferredWidth(430);
		table.getColumnModel().getColumn(7).setPreferredWidth(155);
		table.getColumnModel().getColumn(8).setPreferredWidth(260);
		tableScroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBackground(Color.BLACK);
		header.add(parameters, BorderLayout.CENTER);
		cellDetailsButton.addActionListener(e -> showCellDetails());
		logCheck.setOpaque(true);
		logCheck.setBackground(Color.BLACK);
		logCheck.setForeground(Color.WHITE);
		logCheck.setFocusPainted(false);
		logCheck.setBorder(new EmptyBorder(8, 8, 8, 12));
		logCheck.setToolTipText("Log every decoded SDS event to a UTF-8 TSV file");
		logCheck.addActionListener(e -> toggleLogging());
		header.add(logCheck, BorderLayout.EAST);
		add(header, BorderLayout.NORTH);
		tabs.addTab("PID / SDS MESSAGES", tableScroll);
		tabs.addTab("VOICE CALLS", voiceCalls);
		add(tabs, BorderLayout.CENTER);
		refresh(TetraDecoderCore.Result.empty("Waiting for TETRA IQ samples"), null, null);
	}

	void refresh(TetraDecoderCore.Result result, List<TetraDecoderCore.Burst> bursts,
			List<TetraMacDecoder.SdsMessage> messages) {
		refresh(result, bursts, messages, null);
	}

	void refresh(TetraDecoderCore.Result result, List<TetraDecoderCore.Burst> bursts,
			List<TetraMacDecoder.SdsMessage> messages,
			List<TetraMacDecoder.TetraMessage> callEvents) {
		boolean hasNewMessages = messages != null && !messages.isEmpty();
		boolean followNewest = hasNewMessages && isScrolledToBottom()
				&& (table.getRowSorter() == null || table.getRowSorter().getSortKeys().isEmpty());
		if (result == null) result = TetraDecoderCore.Result.empty("Waiting for TETRA IQ samples");
		latestResult = result;
		latestCenterHz = result.centerHz;
		if (cellDetailsFrame != null && cellDetailsFrame.isDisplayable())
			cellDetailsFrame.refresh(result);
		String cfo = Double.isNaN(result.frequencyErrorHz) ? "--"
				: String.format(Locale.US, "%+.1f Hz", result.frequencyErrorHz);
		String last = result.lastBurst == null ? "--" : result.lastBurst.type.toString();
		String network = result.systemInfo == null ? "not decoded yet"
				: String.format(Locale.US, "MCC %d / MNC %d / colour code %d / FN %d / MN %d",
						result.systemInfo.mcc, result.systemInfo.mnc,
						result.systemInfo.colourCode, result.systemInfo.frame,
						result.systemInfo.multiframe);
		String mainCarrier = "Main: not decoded yet";
		if (result.cellInfo != null && result.cellInfo.hasSysInfo) {
			mainCarrier = String.format(Locale.US, "Main: <b>%d</b> / <b>%.6f MHz</b>",
					result.cellInfo.mainCarrier, result.cellInfo.mainCarrierHz / 1e6);
			if (result.cellInfo.role == TetraCellInfo.Role.MAIN)
				mainCarrier += " &nbsp;&nbsp; <font color='#66ff88'><b>MAIN CELL</b></font>";
		}
		parameters.setText("<html><b>TETRA DOWNLINK ACQUISITION</b><br>"
				+ mainCarrier + "<br>"
				+ "State: " + escape(result.state) + "<br>"
				+ String.format(Locale.US,
						"Input: %.6f MHz / %.3f kS/s &nbsp;&nbsp; Processing: %.3f kS/s<br>"
						+ "Bursts: %d &nbsp; NDB1: %d &nbsp; NDB2: %d &nbsp; SYNC: %d<br>"
						+ "Last: %s &nbsp; Best correlation: %.1f %% &nbsp; CFO: %s<br>"
						+ "BSCH CRC OK: %d &nbsp; SCH CRC OK: %d &nbsp; Network: %s<br>"
						+ "MAC resources clear/encrypted: %d/%d &nbsp; fragments start/end: %d/%d<br>"
						+ "D-SDS-DATA: %d</html>",
						result.centerHz / 1e6, result.inputRateHz / 1e3, result.processingRateHz / 1e3,
						result.bursts, result.ndb1Bursts, result.ndb2Bursts, result.syncBursts,
						last, result.bestCorrelation * 100d, cfo, result.decodedBsch,
						result.decodedSchf, escape(network), result.clearResources,
						result.encryptedResources, result.fragmentStarts, result.fragmentEnds,
						result.sdsMessages));
		if (messages != null) {
			for (TetraMacDecoder.SdsMessage message : messages) {
				model.addRow(new Object[] { timeFormat.format(new Date(message.timestampMillis)),
						optionalValue(message.protocol), optionalValue(message.destination),
						optionalValue(message.source), optionalValue(message.userDataType),
						message.lengthBits, message.dataHex(),
						positionCandidate(message), textCandidate(message) });
				writeLog(message);
			}
			flushLog();
		}
		voiceCalls.accept(callEvents);
		tabs.setTitleAt(1, "VOICE CALLS" + (voiceCalls.callCount() == 0
				? "" : " (" + voiceCalls.callCount() + ")"));
		trimRows();
		int lastRow = model.getRowCount() - 1;
		if (followNewest && lastRow >= 0) {
			int viewRow = table.convertRowIndexToView(lastRow);
			if (viewRow >= 0) table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
		}
	}

	private boolean isScrolledToBottom() {
		JScrollBar bar = tableScroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 2;
	}

	private void trimRows() {
		while (model.getRowCount() > MAX_ROWS) model.removeRow(0);
	}

	void clear() {
		model.setRowCount(0);
		voiceCalls.clear();
		tabs.setTitleAt(1, "VOICE CALLS");
	}

	void close() {
		logCheck.setSelected(false);
		stopLogging();
		if (cellDetailsFrame != null) cellDetailsFrame.dispose();
		cellDetailsFrame = null;
	}

	JButton createCellHeaderControls() { return cellDetailsButton; }

	private void showCellDetails() {
		if (cellDetailsFrame == null || !cellDetailsFrame.isDisplayable()) {
			cellDetailsFrame = new TetraCellDetailsFrame();
			cellDetailsFrame.setLocationRelativeTo(this);
		}
		cellDetailsFrame.refresh(latestResult);
		cellDetailsFrame.setVisible(true);
		cellDetailsFrame.toFront();
	}

	private void toggleLogging() {
		if (!logCheck.isSelected()) {
			stopLogging();
			return;
		}
		try {
			openLog(createLogPath(), true);
		} catch (IOException error) {
			logCheck.setSelected(false);
			stopLogging();
			JOptionPane.showMessageDialog(this, "Cannot create TETRA SDS log:\n" + error.getMessage(),
					"TETRA SDS log", JOptionPane.ERROR_MESSAGE);
		}
	}

	private Path createLogPath() throws IOException {
		String frequency = latestCenterHz > 0L ? " " + latestCenterHz + "Hz" : "";
		String base = "# TETRA SDS" + frequency + " " + fileTimeFormat.format(new Date());
		Path directory = Paths.get(System.getProperty("user.dir", "."));
		for (int suffix = 0; suffix < 1000; suffix++) {
			Path candidate = directory.resolve(base + (suffix == 0 ? "" : " (" + suffix + ")") + ".txt");
			if (!Files.exists(candidate)) return candidate;
		}
		throw new IOException("No free log filename for " + base);
	}

	private void openLog(Path path, boolean createNew) throws IOException {
		stopLogging();
		logWriter = createNew
				? Files.newBufferedWriter(path, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
				: Files.newBufferedWriter(path, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE);
		logPath = path.toAbsolutePath();
		logWriter.write("# TETRA decoded D-SDS-DATA events\n");
		logWriter.write("Timestamp\tFrequency Hz\tPID\tDL SSI\tCalling SSI\tUDT\tBits\tData\tPosition\tText candidate\n");
		logWriter.flush();
		logCheck.setText("LOG");
		logCheck.setForeground(new Color(0x66ff66));
		logCheck.setToolTipText("Logging SDS events to " + logPath);
	}

	private void writeLog(TetraMacDecoder.SdsMessage message) {
		if (logWriter == null || message == null) return;
		try {
			logWriter.write(tsv(logTimeFormat.format(new Date(message.timestampMillis))));
			logWriter.write('\t'); logWriter.write(Long.toString(latestCenterHz));
			logWriter.write('\t'); logWriter.write(optionalText(message.protocol));
			logWriter.write('\t'); logWriter.write(optionalText(message.destination));
			logWriter.write('\t'); logWriter.write(optionalText(message.source));
			logWriter.write('\t'); logWriter.write(optionalText(message.userDataType));
			logWriter.write('\t'); logWriter.write(Integer.toString(message.lengthBits));
			logWriter.write('\t'); logWriter.write(message.dataHex());
			logWriter.write('\t'); logWriter.write(positionCandidate(message));
			logWriter.write('\t'); logWriter.write(tsv(textCandidate(message)));
			logWriter.newLine();
		} catch (IOException error) {
			loggingFailed(error);
		}
	}

	private void flushLog() {
		if (logWriter == null) return;
		try {
			logWriter.flush();
		} catch (IOException error) {
			loggingFailed(error);
		}
	}

	private void loggingFailed(IOException error) {
		stopLogging();
		logCheck.setSelected(false);
		logCheck.setText("LOG ERROR");
		logCheck.setToolTipText(error.getMessage());
	}

	private void stopLogging() {
		BufferedWriter writer = logWriter;
		logWriter = null;
		if (writer != null) {
			try { writer.close(); } catch (IOException ignored) { }
		}
		logPath = null;
		logCheck.setForeground(Color.WHITE);
		if (!"LOG ERROR".equals(logCheck.getText())) logCheck.setText("LOG");
		if (!logCheck.isSelected())
			logCheck.setToolTipText("Log every decoded SDS event to a UTF-8 TSV file");
	}

	private static String textCandidate(TetraMacDecoder.SdsMessage message) {
		if (message.userDataBits.length <= 8) return "";
		byte[] bytes = bytes(message.userDataBits, 8);
		List<String> runs = new ArrayList<String>();
		try {
			CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes));
			collectPrintableRuns(decoded.toString(), runs);
		} catch (CharacterCodingException ignored) { }
		if (runs.isEmpty()) {
			StringBuilder ascii = new StringBuilder(bytes.length);
			for (byte value : bytes) {
				int c = value & 0xff;
				ascii.append(c >= 0x20 && c <= 0x7e ? (char)c : '\n');
			}
			collectPrintableRuns(ascii.toString(), runs);
		}
		StringBuilder result = new StringBuilder();
		for (String run : runs) {
			if (result.length() > 0) result.append(" | ");
			result.append(run);
			if (result.length() >= 240) break;
		}
		return result.length() <= 240 ? result.toString() : result.substring(0, 237) + "...";
	}

	/** Decode the observed EADS PID-194 AVL frame; leave every other PID blank. */
	private static String positionCandidate(TetraMacDecoder.SdsMessage message) {
		if (message == null || message.protocol != 194 || message.userDataBits.length != 256)
			return "";
		int[] bits = message.userDataBits;
		/* C2, SDS-TRANSFER without storage, followed by the observed 0x80 AVL subtype. */
		if (readBits(bits, 0, 8) != 0xc2 || readBits(bits, 8, 4) != 0
				|| readBits(bits, 15, 1) != 0 || readBits(bits, 24, 8) != 0x80
				|| readBits(bits, 112, 8) != 0x66 || readBits(bits, 184, 8) != 0x6b)
			return "";
		int latitudeValue = readSigned32(bits, 120);
		int longitudeValue = readSigned32(bits, 152);
		double latitude = latitudeValue * (90d / 2147483648d);
		double longitude = longitudeValue * (180d / 2147483648d);
		if (latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d)
			return "";
		return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
	}

	private static int readBits(int[] bits, int start, int length) {
		if (bits == null || start < 0 || length < 0 || start + length > bits.length) return -1;
		int value = 0;
		for (int n = 0; n < length; n++) value = value << 1 | bits[start + n] & 1;
		return value;
	}

	private static int readSigned32(int[] bits, int start) {
		int value = 0;
		for (int n = 0; n < 32; n++) value = value << 1 | bits[start + n] & 1;
		return value;
	}

	private static byte[] bytes(int[] bits, int startBit) {
		int length = Math.max(0, bits.length - startBit);
		byte[] result = new byte[(length + 7) / 8];
		for (int offset = 0; offset < length; offset++)
			result[offset / 8] |= (bits[startBit + offset] & 1) << (7 - offset % 8);
		return result;
	}

	private static void collectPrintableRuns(String text, List<String> result) {
		StringBuilder run = new StringBuilder();
		for (int offset = 0; offset <= text.length(); offset++) {
			char c = offset < text.length() ? text.charAt(offset) : '\n';
			if (c != '\ufffd' && !Character.isISOControl(c)) run.append(c);
			else {
				String candidate = run.toString().trim();
				if (candidate.length() >= 4) result.add(candidate);
				run.setLength(0);
			}
		}
	}

	private static String tsv(String text) {
		return text == null ? "" : text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
	}

	private static Integer optionalValue(int value) { return value < 0 ? null : Integer.valueOf(value); }
	private static String optionalText(int value) { return value < 0 ? "" : Integer.toString(value); }

	int eventCountForTest() { return model.getRowCount(); }
	Object eventValueForTest(int row, int column) { return model.getValueAt(row, column); }
	boolean sortableForTest() { return table.getRowSorter() != null; }
	void startLoggingForTest(File file) throws IOException {
		logCheck.setSelected(true);
		openLog(file.toPath(), false);
	}
	void stopLoggingForTest() {
		logCheck.setSelected(false);
		stopLogging();
	}

	private static String escape(String text) {
		if (text == null) return "";
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
