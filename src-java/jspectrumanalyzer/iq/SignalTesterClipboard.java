package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JPanel;
import javax.swing.Timer;

/** Creates consistent, stable clipboard snapshots for signal-tester windows. */
final class SignalTesterClipboard {
	private static final String BUTTON_TEXT = "COPY DATA";

	private SignalTesterClipboard() { }

	static JPanel createDetailsHeader(Component root, JLabel status) {
		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBackground(Color.BLACK);
		header.add(status, BorderLayout.CENTER);
		header.add(createButton(root, true), BorderLayout.EAST);
		return header;
	}

	static JButton createButton(Component root, boolean includeAllTabs) {
		JButton button = new JButton(BUTTON_TEXT);
		button.setToolTipText(includeAllTabs
				? "Copy a stable TSV snapshot of all detail tabs to the clipboard"
				: "Copy a stable TSV snapshot of this tester to the clipboard");
		button.addActionListener(e -> copy(root, includeAllTabs, button));
		return button;
	}

	private static void copy(Component root, boolean includeAllTabs, JButton sourceButton) {
		StringBuilder output = new StringBuilder(8192);
		appendComponent(root, output, includeAllTabs, false);
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
					new StringSelection(output.toString().trim() + "\n"), null);
			showFeedback(sourceButton, "COPIED");
		} catch (RuntimeException error) {
			sourceButton.setToolTipText("Clipboard unavailable: " + error.getMessage());
			showFeedback(sourceButton, "COPY FAILED");
		}
	}

	private static void appendComponent(Component component, StringBuilder output,
			boolean includeAllTabs, boolean allowHidden) {
		if (component == null || (!allowHidden && !component.isVisible())) return;
		if (component instanceof JTable) {
			appendTable((JTable)component, output);
			return;
		}
		if (includeAllTabs && component instanceof JTabbedPane) {
			JTabbedPane tabs = (JTabbedPane)component;
			for (int index = 0; index < tabs.getTabCount(); index++) {
				appendSection(output, tabs.getTitleAt(index));
				appendComponent(tabs.getComponentAt(index), output, true, true);
			}
			return;
		}
		if (component instanceof JLabel) {
			String value = plainText(((JLabel)component).getText()).trim();
			if (value.length() > 0) output.append(value).append('\n');
		}
		if (component instanceof Container) {
			for (Component child : ((Container)component).getComponents())
				appendComponent(child, output, includeAllTabs, allowHidden);
		}
	}

	private static void appendSection(StringBuilder output, String title) {
		if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
		if (output.length() > 0) output.append('\n');
		output.append('[').append(tsvCell(title)).append("]\n");
	}

	private static void appendTable(JTable table, StringBuilder output) {
		if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
		for (int column = 0; column < table.getColumnCount(); column++) {
			if (column > 0) output.append('\t');
			output.append(tsvCell(table.getColumnName(column)));
		}
		output.append('\n');
		for (int row = 0; row < table.getRowCount(); row++) {
			for (int column = 0; column < table.getColumnCount(); column++) {
				if (column > 0) output.append('\t');
				Component rendered = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
				Object value = rendered instanceof JLabel ? ((JLabel)rendered).getText()
						: table.getValueAt(row, column);
				output.append(tsvCell(value == null ? "" : value.toString()));
			}
			output.append('\n');
		}
		output.append('\n');
	}

	private static String tsvCell(String value) {
		return plainText(value).replace('\t', ' ').replace("\r", " ").replace("\n", " / ").trim();
	}

	private static String plainText(String value) {
		if (value == null) return "";
		return value.replaceAll("(?i)<br\\s*/?>", "\n")
				.replaceAll("(?i)</tr\\s*>", "\n")
				.replaceAll("(?i)</t[dh]\\s*>", "\t")
				.replaceAll("(?s)<[^>]*>", "")
				.replace("&nbsp;", " ").replace("&amp;", "&")
				.replace("&lt;", "<").replace("&gt;", ">");
	}

	private static void showFeedback(JButton button, String text) {
		button.setText(text);
		Timer restore = new Timer(1200, e -> button.setText(BUTTON_TEXT));
		restore.setRepeats(false);
		restore.start();
	}
}
