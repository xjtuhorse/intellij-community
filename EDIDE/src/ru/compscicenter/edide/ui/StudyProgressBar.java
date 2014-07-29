package ru.compscicenter.edide.ui;

/**
 * author: liana
 * data: 7/29/14.
 */

import com.intellij.openapi.ui.GraphicsConfig;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;

public class StudyProgressBar extends JComponent {
  public static final Color BLUE = JBColor.BLUE;
  private static final Color SHADOW1 = new JBColor(Gray._190, UIUtil.getBorderColor());
  private static final Color SHADOW2 = Gray._105;
  private static final int BRICK_WIDTH = 10;
  private static final int BRICK_SPACE = 2;
  private final int myHeight;
  private final int myIndent;
  private double myFraction = 0.0;
  private Color myColor = BLUE;

  public StudyProgressBar(double fraction, Color color, int height, int indent) {
    myFraction = fraction;
    myColor = color;
    myHeight = height;
    myIndent = indent;
  }

  public void setFraction(double fraction) {
    if (Double.isNaN(fraction)) {
      fraction = 1.0;
    }
    boolean changed = myFraction == 0 || getBricksToDraw(myFraction) != getBricksToDraw(fraction);
    myFraction = fraction;
    if (changed) {
      repaint();
    }
  }

  private int getBricksToDraw(double fraction) {
    int bricksTotal = (getWidth() - 8) / (BRICK_WIDTH + BRICK_SPACE);
    return (int)(bricksTotal * fraction) + 1;
  }

  protected void paintComponent(Graphics g) {
    final GraphicsConfig config = GraphicsUtil.setupAAPainting(g);
    Graphics2D g2 = (Graphics2D)g;
    if (myFraction > 1) {
      myFraction = 1;
    }

    Dimension size = getSize();
    double width = size.getWidth() - 2*myIndent;
    g2.setPaint(UIUtil.getListBackground());
    Rectangle2D rect = new Rectangle2D.Double(myIndent, 0, width, myHeight);
    g2.fill(rect);

    g2.setPaint(new JBColor(SHADOW1, UIUtil.getBorderColor()));
    rect.setRect(myIndent, 0, width, myHeight);
    int arcWidth = 5;
    int arcHeight = 5;
    g2.drawRoundRect(myIndent, 0, (int)width, myHeight, arcWidth, arcHeight);
    g2.setPaint(SHADOW2);
    g2.drawRoundRect(myIndent, 0, (int)width, myHeight, arcWidth, arcHeight);

    int y_center = myHeight / 2;
    int y_steps = myHeight / 2 - 3;
    int alpha_step = y_steps > 0 ? (255 - 70) / y_steps : 255 - 70;
    int x_offset = 4;

    g.setClip(4 + myIndent, 3, (int)width - 6, myHeight - 4);

    int bricksToDraw = myFraction == 0 ? 0 : getBricksToDraw(myFraction);
    for (int i = 0; i < bricksToDraw; i++) {
      g2.setPaint(myColor);
      UIUtil.drawLine(g2, x_offset, y_center, x_offset + BRICK_WIDTH - 1, y_center);
      for (int j = 0; j < y_steps; j++) {
        Color color = ColorUtil.toAlpha(myColor, 255 - alpha_step * (j + 1));
        g2.setPaint(color);
        UIUtil.drawLine(g2, x_offset, y_center - 1 - j, x_offset + BRICK_WIDTH - 1, y_center - 1 - j);
        if (!(y_center % 2 != 0 && j == y_steps - 1)) {
          UIUtil.drawLine(g2, x_offset, y_center + 1 + j, x_offset + BRICK_WIDTH - 1, y_center + 1 + j);
        }
      }
      g2.setColor(
        ColorUtil.toAlpha(myColor, 255 - alpha_step * (y_steps / 2 + 1)));
      g2.drawRect(x_offset, y_center - y_steps, BRICK_WIDTH - 1, myHeight - 7);
      x_offset += BRICK_WIDTH + BRICK_SPACE;
    }
    config.restore();
  }
}
