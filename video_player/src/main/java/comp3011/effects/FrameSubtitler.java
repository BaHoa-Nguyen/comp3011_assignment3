package comp3011.effects;

import comp3011.dto.InfoFrame;

// Java2D, the JDK's own 2D drawing API
// process() runs on a pipeline worker thread, and JavaFX objects must only ever
// be touched by the JavaFX thread. Java2D has no such rule and works headless.
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

// this is for the decoded frame and its raw pixel buffer
// we edit the pixel in place instead of copying them
import java.nio.ByteBuffer;
import org.bytedeco.javacv.Frame;

public class FrameSubtitler extends FrameProcessor {

  // toggle subtitle
  private volatile boolean isSubtitleEnabled = true;

  // placeholder only to test if the subtitle does appear on the screen before
  // using OpenAI for the transcription
  private static final String PLACEHOLDER_SUBTITLE_TEXT = "Test subtitle feature";

  // this is the gap between the subtitle and the bottom edge of the frame;
  private static final int BOTTOM_MARGIN = 12;

  public void setEnableSubtitle(boolean enabled) {
    this.isSubtitleEnabled = enabled;
  }

  public boolean isSubtitleEnabled() {
    return isSubtitleEnabled;
  }

  @Override
  public void process(Frame frame, InfoFrame info) throws Exception {

    if (!isSubtitleEnabled) {
      return;
    }

    if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
      return;
    }

    // unpack the pixel
    if (!(frame.image[0] instanceof ByteBuffer pixels)) {
      throw new Exception("FrameSubtitler requires byte-backed image frames");
    }

    // ensure that the color sample is 1 byte
    if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
      throw new Exception("FrameSubtitler requires 8-bit image frames");
    }

    int fontSize = Math.max(12, videoInfo.imageHeight() / 24);
    printSubtitle(pixels, buildSubtitleImage(PLACEHOLDER_SUBTITLE_TEXT, fontSize));
  }

  private BufferedImage buildSubtitleImage(String text, int fontSize) {

    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);

    // moderate outline thickness based on the font size
    // Math.max(1, …) again stops a 0-width outline on small frames.
    int outlineThickness = Math.max(1, fontSize / 16);

    // padding = thickness * 2 + 2 covers the outward drift plus a couple of spare
    // pixels for antialiasing.
    int padding = outlineThickness * 2 + 2;

    // measuring the subtitle before drawing it so we know how much space it needs
    // and where to position it on the video.
    BufferedImage measuringCanvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D measuringGraphics = measuringCanvas.createGraphics();

    measuringGraphics.setFont(font);

    FontMetrics textMetrics = measuringGraphics.getFontMetrics();
    measuringGraphics.dispose(); // once we finish measuring the subtitle, we dont need the graphic context
                                 // anymore so we dispose it
    // the width depends on the actual text width
    int subtitleImageWidth = textMetrics.stringWidth(text) + padding * 2; // padding on both sides (left + right)
    int subtitleImageHeight = textMetrics.getHeight() + padding * 2;

    BufferedImage subtitleImage = new BufferedImage(subtitleImageWidth, subtitleImageHeight,
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D subtitleGraphic = subtitleImage.createGraphics();

    subtitleGraphic.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    subtitleGraphic.setFont(font);

    // draw the outline of the text black
    subtitleGraphic.setColor(Color.BLACK);

    // first for loop for shifting left and right
    for (int posX = -outlineThickness; posX <= outlineThickness; posX++) {
      // inner for loop for shifting up and down
      for (int posY = -outlineThickness; posY <= outlineThickness; posY++) {
        subtitleGraphic.drawString(text, posX + padding, posY + textMetrics.getAscent() + padding);
      }
    }

    // draw the subtitle in white
    subtitleGraphic.setColor(Color.WHITE);
    subtitleGraphic.drawString(text, padding, padding + textMetrics.getAscent());
    subtitleGraphic.dispose();

    return subtitleImage;

  }

  private void printSubtitle(ByteBuffer pixels, BufferedImage subtitleImage) {
    int subtitleImageWidth = subtitleImage.getWidth();
    int subtitleImageHeight = subtitleImage.getHeight();

    // drawing points
    int startX = (videoInfo.imageWidth() - subtitleImageWidth) / 2; // center the subtitle
    int startY = videoInfo.imageHeight() - BOTTOM_MARGIN - subtitleImageHeight;

    // frame too small for subtitle
    if (startX < 0 || startY < 0) {
      return;
    }

    for (int row = 0; row < subtitleImageHeight; row++) {
      for (int column = 0; column < subtitleImageWidth; column++) {
        int subtitleRgbPixel = subtitleImage.getRGB(column, row);
        int opacity = (subtitleRgbPixel >>> 24) & 0xff; // the ARGB is 8-bit for each color, so we want to get the A
                                                        // (Alpha) we need to shift 24 bits

        // skip complete transparent
        if (opacity == 0) {
          continue;
        }

        int subtitleShade = (subtitleRgbPixel >>> 16) & 0xff;
        int videoPixelOffset = (startY + row) * videoInfo.imageStride()
            + (startX + column) * videoInfo.imageChannels();

        for (int channel = 0; channel < videoInfo.imageChannels(); channel++) {
          int videoShade = pixels.get(videoPixelOffset + channel) & 0xff;

          // The formula uses how solid the white is plus what the video already has there
          // to get the colour to write.
          // The point is to draw the right words at the right moment, cleanly, over the
          // video, hiding as little of it as possible.
          pixels.put(videoPixelOffset + channel,
              (byte) ((subtitleShade * opacity + videoShade * (255 - opacity)) / 255));
        }
      }
    }
  }
}
