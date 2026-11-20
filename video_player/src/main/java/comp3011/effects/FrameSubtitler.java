package comp3011.effects;

import comp3011.dto.InfoFrame;
import comp3011.dto.SubtitleSegment;
import comp3011.dto.SubtitleResponse;

// Java2D, the JDK's own 2D drawing API
// process() runs on a pipeline worker thread, and JavaFX objects must only ever
// be touched by the JavaFX thread. Java2D has no such rule and works headless.
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import java.util.List;
import java.util.Properties;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import java.io.InputStream;

// this is for the decoded frame and its raw pixel buffer
// we edit the pixel in place instead of copying them
import java.nio.ByteBuffer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import tools.jackson.databind.ObjectMapper;

public class FrameSubtitler extends FrameProcessor {
  // retrieve the subtitle url from the properties file
  private static final String SUBTITLE_URL = readSubtitleUrl();

  // placeholder only to test if the subtitle does appear on the screen before
  // using OpenAI for the transcription or used as a fallback
  private static final String PLACEHOLDER_SUBTITLE_TEXT = "Test subtitle feature";

  private static final HttpClient HTTP = HttpClient.newHttpClient(); // use to send the request to the Spring server
  private static final ObjectMapper MAPPER = new ObjectMapper(); // for conversion: JSON -> Java Object and vice versa

  private volatile List<SubtitleSegment> segments; // swapped atomically, no locks

  // toggle subtitle
  private volatile boolean isSubtitleEnabled = true;

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

    String subtitleText = subtitleTextAt(info.timestampUs() / 1_000_000.0);

    if (subtitleText == null) {
      return;
    }

    int fontSize = Math.max(12, videoInfo.imageHeight() / 24);
    printSubtitle(pixels, buildSubtitleImage(subtitleText, fontSize));
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

  public void loadSubtitle(File video) {
    if (SUBTITLE_URL == null) {
      System.err.println("No URL found, printing placeholder only");
    }

    Thread subtitleThread = new Thread(() -> {
      File extractedAudio = null;
      try {
        extractedAudio = extractAudio(video);
        // return JSON
        byte[] returnedResponse = postMultipart(extractedAudio);

        // convert to SubtitleResponse (JSON -> SubtitleResponse object)
        SubtitleResponse response = MAPPER.readValue(returnedResponse, SubtitleResponse.class);
        segments = response.segments() == null ? List.of() : response.segments();
      } catch (Exception e) {
        // server down or request rejected: keep the placeholder, playback is
        // unaffected
        System.err.println("Subtitle fetch failed: " + e);
      } finally {
        extractedAudio.delete();
      }
    }, "subtitleThread");

    subtitleThread.setDaemon(true);
    subtitleThread.start();
  }

  private byte[] postMultipart(File video) throws IOException, InterruptedException {
    // separate the header and tail of the request
    String boundary = UUID.randomUUID().toString();

    // use octet stream for unknown data types of the input
    byte[] reqHead = ("--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"audio\"; filename=\""
        + video.getName() + "\"\r\n" + "Content-Type: application/octet-stream\r\n\r\n")
        .getBytes(StandardCharsets.UTF_8);

    byte[] reqTail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

    // build the request
    HttpRequest request = HttpRequest.newBuilder(URI.create(SUBTITLE_URL)).header("Content-Type",
        "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(reqHead, Files.readAllBytes(video.toPath()), reqTail)))
        .build();

    HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());

    if (response.statusCode() != 200) {
      throw new IOException("Something went wrong " + response.statusCode());
    }

    return response.body();
  }

  // determine the subtitle at what seconds
  private String subtitleTextAt(double seconds) {

    List<SubtitleSegment> currentSegments = segments;

    // loading or failed to fetch
    if (currentSegments == null) {
      return PLACEHOLDER_SUBTITLE_TEXT;
    }

    for (SubtitleSegment segment : currentSegments) {
      if (seconds >= segment.start() && seconds < segment.end()) {
        return segment.outputText();
      }
    }
    return null;
  }

  // extract and upload the audio to Whisper only, as uploading the entire video
  // exceeds the size limit of Whisper (26214400 bytes)
  private File extractAudio(File video) throws Exception {
    File audioTempFile = File.createTempFile("subtitle-", ".webm");

    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video);
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(audioTempFile, 1)) {

      grabber.start();

      recorder.setFormat("webm");
      recorder.setAudioCodecName("libopus");
      recorder.setSampleRate(16000);
      recorder.setAudioChannels(1);
      recorder.setAudioBitrate(24000);
      recorder.start();

      Frame frame;

      while ((frame = grabber.grabSamples()) != null) {
        recorder.record(frame);
      }
    }
    return audioTempFile;
  }

  // read the url in the properties file
  private static String readSubtitleUrl() {

    // searsch for the subtitle.properties
    // try-with-resource to automatically close when the try catch block finish
    // Can use any other class instead of FrameSubtitler, as it just gives Java a
    // reference
    // however, using FrameSubtitler here as it makes sense that we need to get the
    // configuration for the subtitle
    try (InputStream inputStream = FrameSubtitler.class.getResourceAsStream("/subtitle.properties")) {

      // if file not exist
      if (inputStream == null) {
        return null;
      }

      // load and return the property
      Properties properties = new Properties();

      properties.load(inputStream);

      return properties.getProperty("subtitle.url");

    } catch (IOException ex) {
      return null;
    }

  }
}
