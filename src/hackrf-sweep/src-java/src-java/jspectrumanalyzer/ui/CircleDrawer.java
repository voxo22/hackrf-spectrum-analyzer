package jspectrumanalyzer.ui;

import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;

import org.jfree.ui.Drawable;

public class CircleDrawer implements Drawable {

	/** The outline paint. */
	private Paint outlinePaint;
	
	/** The outline stroke. */
	private Stroke outlineStroke;
	
	/** The fill paint. */
	private Paint fillPaint;
	
	/**
	* @param outlinePaint the outline paint.
	* @param outlineStroke the outline stroke.
	* @param fillPaint the fill paint.
	*/
	public CircleDrawer(final Stroke outlineStroke,
		final Paint outlinePaint,
		final Paint fillPaint) {
			this.outlineStroke = outlineStroke;
			this.outlinePaint = outlinePaint;
			this.fillPaint = fillPaint;
		}
	
	/**
	* Draws the circle.
	*
	* @param g2 the graphics device.
	* @param area the area in which to draw.
	*/
	public void draw(final Graphics2D g2, final Rectangle2D area) {
		final Ellipse2D circle = new Ellipse2D.Double(area.getX(), area.getY(),
		area.getWidth(), area.getHeight());
		if (fillPaint != null) {
			g2.setPaint(fillPaint);
			g2.fill(circle);
		}
		if (this.outlinePaint != null && this.outlineStroke != null) {
			g2.setStroke(outlineStroke);
			g2.setPaint(outlinePaint);
			g2.draw(circle);
		}
	}
}
