package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/** Prominent DAB ensemble summary shown above the per-service table. */
final class DabEnsemblePanel extends JPanel {
	private static final Color BACKGROUND = Color.BLACK;
	private static final Color MUTED = new Color(0xdddddd);
	private static final Color GOOD = new Color(0x44cc44);
	private static final Color WARN = new Color(0xffcc33);

	private final JLabel name = new JLabel("---");
	private final JLabel identifier = new JLabel("Ensemble ID --");
	private final JLabel country = valueLabel();
	private final JLabel mode = valueLabel();
	private final JLabel services = valueLabel();
	private final JLabel fic = valueLabel();
	private final JLabel time = valueLabel();
	private final JLabel state = new JLabel("Collecting DAB frame IQ");

	DabEnsemblePanel() {
		super(new BorderLayout());
		setBackground(BACKGROUND);
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Color.DARK_GRAY),
				BorderFactory.createEmptyBorder(10, 14, 9, 14)));

		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 26f));
		identifier.setForeground(MUTED);
		identifier.setFont(identifier.getFont().deriveFont(Font.BOLD, 13f));
		country.setForeground(MUTED);
		country.setFont(country.getFont().deriveFont(Font.BOLD, 11f));

		state.setForeground(WARN);
		state.setFont(state.getFont().deriveFont(Font.BOLD, 12f));

		JPanel rows = new JPanel();
		rows.setOpaque(false);
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		Box nameRow = Box.createHorizontalBox();
		nameRow.setAlignmentX(LEFT_ALIGNMENT);
		nameRow.add(name);
		nameRow.add(Box.createHorizontalStrut(6));
		nameRow.add(country);
		nameRow.add(Box.createHorizontalGlue());
		rows.add(nameRow);
		identifier.setAlignmentX(LEFT_ALIGNMENT);
		rows.add(identifier);
		rows.add(Box.createVerticalStrut(country.getPreferredSize().height));
		for (JLabel row : new JLabel[] {mode, services, time, fic, state}) {
			row.setAlignmentX(LEFT_ALIGNMENT);
			rows.add(row);
		}
		add(rows, BorderLayout.NORTH);
	}

	void update(DabFicDecoder.Result result) {
		if (result == null) result = DabFicDecoder.Result.empty("Waiting for DAB IQ samples");
		name.setText(result.ensembleLabel.length() == 0 ? "---" : result.ensembleLabel);
		identifier.setText(result.ensembleId < 0 ? "Ensemble ID --"
				: String.format(Locale.US, "Ensemble ID 0x%04X", result.ensembleId));
		country.setText(result.countryCode);
		mode.setText("Transmission mode   I (1 kHz carriers)");
		services.setText(String.format(Locale.US, "Services / subchannels   %d / %d",
				result.services.size(), result.subchannelCount));
		time.setText("MUX time   " + (result.utcTime.length() == 0 ? "--" : result.utcTime));
		fic.setText(String.format(Locale.US, "FIB CRC   %d/%d   rolling %.0f %%",
				result.validFibs, result.attemptedFibs, result.rollingQuality));
		state.setText(result.state);
		state.setForeground(result.validFibs > 0 ? GOOD
				: result.rollingQuality > 0d ? WARN : new Color(0xff7777));
	}

	private static JLabel valueLabel() {
		JLabel label = new JLabel("--", SwingConstants.LEFT);
		label.setForeground(Color.WHITE);
		label.setFont(label.getFont().deriveFont(12f));
		return label;
	}
}
