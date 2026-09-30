package jspectrumanalyzer.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class ContextHelpSupport {
	private static final String CONTEXT_HELP_PROPERTY = "jspectrumanalyzer.contextHelp";
	private static final int HOVER_DELAY_MS = 650;
	private static final int AUTO_HIDE_MS = 9000;

	private ContextHelpSupport() {
	}

	public static JButton createHelpButton(JComponent owner) {
		JButton button = new JButton("HELP");
		Dimension size = new Dimension(50, 20);
		button.setPreferredSize(size);
		button.setMinimumSize(size);
		button.setMaximumSize(size);
		button.setMargin(new java.awt.Insets(0, 4, 0, 4));
		button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		button.setBackground(Color.BLACK);
		button.setForeground(Color.BLACK);
		button.setBorder(UIManager.getBorder("Button.border"));
		button.setFocusPainted(false);
		button.setFocusable(false);
		button.setToolTipText("Context help");
		setContextHelp(button, "spectrum.help");
		Controller controller = new Controller(owner);
		button.addActionListener(e -> controller.toggle());
		return button;
	}

	public static void setContextHelp(Component component, String key) {
		if (component instanceof JComponent) {
			((JComponent) component).putClientProperty(CONTEXT_HELP_PROPERTY, key);
		}
	}

	public static void setContextHelp(String key, Component... components) {
		for (Component component : components) {
			setContextHelp(component, key);
		}
	}

	private static final class Controller {
		private final JComponent owner;
		private Cursor helpCursor;
		private Cursor previousCursor;
		private JComponent glassPane;
		private MouseAdapter mouseListener;
		private KeyAdapter keyListener;
		private javax.swing.Timer hoverTimer;
		private javax.swing.Timer autoHideTimer;
		private Popup popup;
		private Component hoverComponent;
		private Point hoverPoint;

		Controller(JComponent owner) {
			this.owner = owner;
		}

		void toggle() {
			if (glassPane != null && glassPane.isVisible()) {
				stop(true);
			} else {
				start();
			}
		}

		private void start() {
			Window window = SwingUtilities.getWindowAncestor(owner);
			if (!(window instanceof RootPaneContainer)) {
				return;
			}
			glassPane = (JComponent) ((RootPaneContainer) window).getGlassPane();
			previousCursor = glassPane.getCursor();
			mouseListener = new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					show(e, true);
					e.consume();
				}
				@Override public void mouseMoved(MouseEvent e) {
					scheduleHover(e);
				}
				@Override public void mouseDragged(MouseEvent e) {
					scheduleHover(e);
				}
				@Override public void mouseExited(MouseEvent e) {
					hoverComponent = null;
					stopHoverTimer();
					hidePopup();
				}
			};
			keyListener = new KeyAdapter() {
				@Override public void keyPressed(KeyEvent e) {
					if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
						stop(true);
						e.consume();
					}
				}
			};
			glassPane.setCursor(getCursor());
			glassPane.addMouseListener(mouseListener);
			glassPane.addMouseMotionListener(mouseListener);
			glassPane.addKeyListener(keyListener);
			glassPane.setFocusable(true);
			glassPane.setVisible(true);
			glassPane.requestFocusInWindow();
		}

		private void stop(boolean hidePopup) {
			if (glassPane == null) {
				if (hidePopup) {
					hidePopup();
				}
				return;
			}
			if (mouseListener != null) {
				glassPane.removeMouseListener(mouseListener);
				glassPane.removeMouseMotionListener(mouseListener);
			}
			if (keyListener != null) {
				glassPane.removeKeyListener(keyListener);
			}
			stopHoverTimer();
			glassPane.setCursor(previousCursor == null ? Cursor.getDefaultCursor() : previousCursor);
			glassPane.setVisible(false);
			glassPane = null;
			previousCursor = null;
			mouseListener = null;
			keyListener = null;
			hoverComponent = null;
			hoverPoint = null;
			if (hidePopup) {
				hidePopup();
			}
		}

		private void scheduleHover(MouseEvent event) {
			Component component = findComponent(event);
			if (component == null) {
				hoverComponent = null;
				stopHoverTimer();
				hidePopup();
				return;
			}
			Point point = event.getPoint();
			if (component == hoverComponent && hoverPoint != null && hoverPoint.distance(point) < 4d) {
				return;
			}
			hoverComponent = component;
			hoverPoint = point;
			stopHoverTimer();
			hoverTimer = new javax.swing.Timer(HOVER_DELAY_MS, e -> showForComponent(component, point, false));
			hoverTimer.setRepeats(false);
			hoverTimer.start();
		}

		private void stopHoverTimer() {
			if (hoverTimer != null) {
				hoverTimer.stop();
				hoverTimer = null;
			}
		}

		private void stopAutoHideTimer() {
			if (autoHideTimer != null) {
				autoHideTimer.stop();
				autoHideTimer = null;
			}
		}

		private void show(MouseEvent event, boolean deactivateAfterShow) {
			Component component = findComponent(event);
			Point point = event.getPoint();
			if (component == null) {
				showPopup("No context help is available for this part of the window.", point);
				startAutoHideTimer();
			} else {
				showForComponent(component, point, true);
			}
			if (deactivateAfterShow) {
				stop(false);
			}
		}

		private void showForComponent(Component component, Point point, boolean autoHide) {
			String text = getText(component);
			if (text == null || text.trim().length() == 0) {
				return;
			}
			showPopup(text, point);
			if (autoHide) {
				startAutoHideTimer();
			}
		}

		private void startAutoHideTimer() {
			stopAutoHideTimer();
			autoHideTimer = new javax.swing.Timer(AUTO_HIDE_MS, e -> hidePopup());
			autoHideTimer.setRepeats(false);
			autoHideTimer.start();
		}

		private Component findComponent(MouseEvent event) {
			if (glassPane == null) {
				return null;
			}
			Window window = SwingUtilities.getWindowAncestor(owner);
			if (!(window instanceof RootPaneContainer)) {
				return null;
			}
			Container contentPane = ((RootPaneContainer) window).getContentPane();
			Point contentPoint = SwingUtilities.convertPoint(glassPane, event.getPoint(), contentPane);
			Component deepest = SwingUtilities.getDeepestComponentAt(contentPane, contentPoint.x, contentPoint.y);
			while (deepest != null) {
				if (getText(deepest) != null) {
					return deepest;
				}
				deepest = deepest.getParent();
			}
			return null;
		}

		private String getText(Component component) {
			if (!(component instanceof JComponent)) {
				return null;
			}
			Object key = ((JComponent) component).getClientProperty(CONTEXT_HELP_PROPERTY);
			if (key == null) {
				return null;
			}
			String text = ContextHelpLibrary.get(key.toString());
			return text == null ? key.toString() : text;
		}

		private void showPopup(String text, Point glassPanePoint) {
			if (glassPane == null) {
				return;
			}
			hidePopup();
			JLabel label = new JLabel(toHtml(text));
			label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
			label.setForeground(Color.BLACK);
			label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
			JPanel panel = new JPanel(new BorderLayout());
			panel.setBackground(new Color(255, 255, 220));
			panel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
			panel.add(label, BorderLayout.CENTER);
			Point screenPoint = new Point(glassPanePoint);
			SwingUtilities.convertPointToScreen(screenPoint, glassPane);
			Dimension popupSize = panel.getPreferredSize();
			java.awt.Rectangle screenBounds = glassPane.getGraphicsConfiguration().getBounds();
			java.awt.Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(glassPane.getGraphicsConfiguration());
			int minX = screenBounds.x + screenInsets.left;
			int maxX = screenBounds.x + screenBounds.width - screenInsets.right;
			int minY = screenBounds.y + screenInsets.top;
			int maxY = screenBounds.y + screenBounds.height - screenInsets.bottom;
			int x = screenPoint.x + 14;
			if (x + popupSize.width > maxX) {
				x = screenPoint.x - popupSize.width - 14;
			}
			x = Math.max(minX, Math.min(maxX - popupSize.width, x));
			int y = screenPoint.y + 18;
			if (y + popupSize.height > maxY) {
				y = screenPoint.y - popupSize.height - 18;
			}
			y = Math.max(minY, Math.min(maxY - popupSize.height, y));
			popup = PopupFactory.getSharedInstance().getPopup(glassPane, panel, x, y);
			popup.show();
		}

		private void hidePopup() {
			stopAutoHideTimer();
			if (popup != null) {
				popup.hide();
				popup = null;
			}
		}

		private Cursor getCursor() {
			if (helpCursor == null) {
				helpCursor = createCursor();
			}
			return helpCursor;
		}

		private Cursor createCursor() {
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			Dimension size = toolkit.getBestCursorSize(32, 32);
			if (size.width <= 0 || size.height <= 0) {
				return Cursor.getDefaultCursor();
			}
			BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			try {
				int[] arrowX = { 0, 0, 5, 8, 11, 8, 14 };
				int[] arrowY = { 0, 20, 14, 23, 22, 13, 13 };
				g.setColor(Color.WHITE);
				g.fillPolygon(arrowX, arrowY, arrowX.length);
				g.setColor(Color.BLACK);
				g.drawPolygon(arrowX, arrowY, arrowX.length);
				g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
				g.setColor(Color.BLACK);
				g.drawString("?", 16, 14);
				g.drawString("?", 14, 14);
				g.drawString("?", 15, 15);
				g.drawString("?", 15, 13);
				g.setColor(Color.WHITE);
				g.drawString("?", 15, 14);
			} finally {
				g.dispose();
			}
			return toolkit.createCustomCursor(image, new Point(0, 0), "Context help");
		}
	}

	private static String toHtml(String text) {
		String[] lines = text.split("\\r?\\n");
		StringBuilder html = new StringBuilder("<html><div style='width: 280px;'>");
		boolean inList = false;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith("- ")) {
				if (!inList) {
					html.append("<ul style='margin-left: 14px;'>");
					inList = true;
				}
				html.append("<li>").append(escape(trimmed.substring(2).trim())).append("</li>");
			} else {
				if (inList) {
					html.append("</ul>");
					inList = false;
				}
				html.append(trimmed.length() == 0 ? "<br>" : escape(trimmed) + "<br>");
			}
		}
		if (inList) {
			html.append("</ul>");
		}
		return html.append("</div></html>").toString();
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
