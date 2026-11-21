package comp3011.effects;

import comp3011.dto.InfoFrame;
import comp3011.dto.SubtitleSegment;
import comp3011.dto.SubtitleResponse;
import comp3011.dto.Cue;

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
import java.util.ArrayList;
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
  private static final String PLACEHOLDER_SUBTITLE_TEXT = "Loading subtitle...";

  private static final HttpClient HTTP = HttpClient.newHttpClient(); // use to send the request to the Spring server
  private static final ObjectMapper MAPPER = new ObjectMapper(); // for conversion: JSON -> Java Object and vice versa

  // toggle subtitle
  private volatile boolean isSubtitleEnabled = true;

  // this is the gap between the subtitle and the bottom edge of the frame;
  private static final int BOTTOM_MARGIN = 12;

  private static final int MAX_CHARACTER_PER_LINE = 42;
  private static final int MAX_CUE_CHARACTERS = MAX_CHARACTER_PER_LINE * 2; // 42 characters per line => 84 in 2 lines
                                                                            // of subtitle
  // Reference:
  // https://partnerhelp.netflixstudios.com/hc/en-us/articles/215758617-Timed-Text-Style-Guide-General-Requirements
  private static final double MAX_CUE_SECONDS = 7.0;
  private static final double MIN_CUE_SECONDS = 1.0;

  private volatile List<Cue> cues;

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

    String captionText = cueTextAt(info.timestampUs() / 1_000_000.0);

    if (captionText == null) {
      return; // silence: leave the frame untouched
    }

    int fontSize = Math.max(12, videoInfo.imageHeight() / 24);
    printSubtitle(pixels, buildSubtitleImage(captionText, fontSize));
  }

  private BufferedImage buildSubtitleImage(String captionText, int fontSize) {
    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);

    // Thickness scales with the font, and max() keeps a visible outline on small
    // frames where integer division would give zero.
    int outlineThickness = Math.max(1, fontSize / 16);

    // The outline drifts outward from the glyphs, so the padding covers that drift
    // plus
    // a couple of pixels for antialiasing.
    int padding = outlineThickness * 2 + 2;

    // A cue holds at most two lines, separated by '\n' when buildCues wrapped it.
    String[] lines = captionText.split("\n", -1);

    // Measure first: the bitmap must be as wide as the widest line and as tall as
    // all
    // lines together, otherwise centring would be wrong.
    BufferedImage measuringCanvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D measuringGraphics = measuringCanvas.createGraphics();
    measuringGraphics.setFont(font);
    FontMetrics textMetrics = measuringGraphics.getFontMetrics();

    int widestLineWidth = 0;
    for (String line : lines) {
      widestLineWidth = Math.max(widestLineWidth, textMetrics.stringWidth(line));
    }

    int lineHeight = textMetrics.getHeight();
    measuringGraphics.dispose();

    int subtitleImageWidth = widestLineWidth + padding * 2;
    int subtitleImageHeight = lineHeight * lines.length + padding * 2;

    BufferedImage subtitleImage = new BufferedImage(subtitleImageWidth, subtitleImageHeight,
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D subtitleGraphic = subtitleImage.createGraphics();
    subtitleGraphic.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    subtitleGraphic.setFont(font);

    for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      // Each line is centred on its own, so a short second line sits under the middle
      // of
      // the first the way broadcast subtitles look.
      int lineWidth = textMetrics.stringWidth(lines[lineIndex]);
      int lineX = (subtitleImageWidth - lineWidth) / 2;
      int lineBaselineY = padding + textMetrics.getAscent() + lineIndex * lineHeight;

      // Stamp the line at every offset around it in black; that is what makes the
      // outline follow the glyph shapes instead of drawing a rectangle.
      subtitleGraphic.setColor(Color.BLACK);
      for (int offsetX = -outlineThickness; offsetX <= outlineThickness; offsetX++) {
        for (int offsetY = -outlineThickness; offsetY <= outlineThickness; offsetY++) {
          subtitleGraphic.drawString(lines[lineIndex], lineX + offsetX, lineBaselineY + offsetY);
        }
      }

      // The white fill goes on top of the outline.
      subtitleGraphic.setColor(Color.WHITE);
      subtitleGraphic.drawString(lines[lineIndex], lineX, lineBaselineY);
    }

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
        cues = buildCues(response.segments());

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

  private static List<Cue> buildCues(List<SubtitleSegment> segments) {
    List<Cue> builtCues = new ArrayList<>(); // store the cues in an ArrayList

    if (segments == null) {
      return builtCues;
    }

    for (SubtitleSegment segment : segments) {
      addCuesForSegment(segment, builtCues);
    }

    extendTooShortCuesIntoFollowingGaps(builtCues);
    return builtCues;
  }

  private static void addCuesForSegment(SubtitleSegment segment, List<Cue> builtCues) {
    String transcribedText = segment.outputText() == null ? "" : segment.outputText().trim();

    // calculate how long the cues will last
    double segmentLastingSeconds = segment.end() - segment.start();

    if (transcribedText.isEmpty() || segmentLastingSeconds <= 0) {
      return; // nothing was said, or the timestamps make no sense
    }

    // how many cues needed for the transcribed text
    int cuesRequiredForTextLength = Math.ceilDiv(transcribedText.length(), MAX_CUE_CHARACTERS);

    // how many cues needed within 7 seconds
    int cuesRequiredForSegmentDuration = (int) Math.ceil(segmentLastingSeconds / MAX_CUE_SECONDS);

    // we'll take the one that covers both requirements (by text length AND by
    // segment duration)
    // at the same time preventing creating empty cues
    int totalCuesToCreate = Math.min(Math.max(cuesRequiredForTextLength, cuesRequiredForSegmentDuration),
        transcribedText.length());

    int cueStartIndex = 0; // first characters of the cue being built

    // now we build the cue
    for (int cueNumber = 0; cueNumber < totalCuesToCreate; cueNumber++) {
      boolean isLastCue = cueNumber == totalCuesToCreate - 1; // we dont want to accidentally remove the last cue

      // divide the remaining characters evenly among the remaining cues, rounding to
      // a whole number and ensuring at least one character is allocated.
      int numberOfCharacterToAdd = Math.max(1,
          Math.round((transcribedText.length() - cueStartIndex) / (float) (totalCuesToCreate - cueNumber)));

      int desiredEndingIndex = cueStartIndex + numberOfCharacterToAdd;

      int cueEndIndex = isLastCue ? transcribedText.length()
          : findGoodPositionToSplit(transcribedText, cueStartIndex, desiredEndingIndex);

      if (cueEndIndex <= cueStartIndex) {
        break; // text ran out; never emit an empty caption
      }

      // we need the elapsed time to decide the time that the next cue would appear
      double elapsedTime = segmentLastingSeconds * cueStartIndex / transcribedText.length();

      double cueStartSeconds = segment.start() + elapsedTime;
      double cueEndSeconds = isLastCue ? segment.end()
          : segment.start() + segmentLastingSeconds * cueEndIndex / transcribedText.length();

      String cueText = transcribedText.substring(cueStartIndex, cueEndIndex).trim();
      builtCues.add(new Cue(cueStartSeconds, cueEndSeconds, wrapIntoTwoLines(cueText)));

      cueStartIndex = cueEndIndex;
    }

  }

  private static int findGoodPositionToSplit(String text, int startIndex, int desiredIndex) {
    int searchRadius = 12; // randomly chosen, may fix if visually looking bad

    // ensure the current cue gets at least one character
    // and allows the method to search up to 15 characters before the ideal split
    // position
    // This is the left boundary of the search window
    int earliestValidSplitIndex = Math.max(startIndex + 1, desiredIndex - searchRadius);

    // wantedIndex + searchRadius: limits how far right we search.
    // startIndex + MAX_CUE_CHARACTERS - 1: enforces the character limit.
    // text.length() - 1: prevents going beyond the string
    // This is the right boundary of the search window
    int latestValidSplitIndex = Math.min(Math.min(desiredIndex + searchRadius, startIndex + MAX_CUE_CHARACTERS - 1),
        text.length() - 1);

    int bestIndex = -1;
    int bestScore = Integer.MAX_VALUE;

    for (int index = earliestValidSplitIndex; index <= latestValidSplitIndex; index++) {
      if (text.charAt(index) != ' ') {
        continue;
      }

      int distanceFromDesiredLocation = Math.abs(index - desiredIndex);

      // A cut right after . ! ? ends a thought, so it beats a nearer mid-sentence
      // cut.
      // The 13-character bonus is one more than the whole search radius, which means
      // any
      // punctuation break inside the window wins.
      boolean afterPunctuation = ".!?".indexOf(text.charAt(index - 1)) >= 0;
      int score = afterPunctuation ? distanceFromDesiredLocation - 13 : distanceFromDesiredLocation;

      if (score < bestScore) {
        bestScore = score;
        bestIndex = index;
      }
    }

    if (bestIndex >= 0) {
      return bestIndex + 1; // include the space; trim() removes it
    }

    // No usable space in the window: cut at the fair share, even through a word.
    return desiredIndex;
  }

  // Fold a cue into at most two lines, preferring a break that is balanced and,
  // when
  // possible, sits after punctuation. The caller guarantees the cue is at most 84
  // characters, which is why two lines are always enough.
  private static String wrapIntoTwoLines(String cueText) {
    if (cueText.length() <= MAX_CHARACTER_PER_LINE) {
      return cueText; // fits on one line
    }

    // Both halves must fit, so only breaks in this range are legal. The +1/-1
    // account
    // for the space that is removed at the break.
    int firstPossibleBreak = Math.max(1, cueText.length() - MAX_CHARACTER_PER_LINE - 1);
    int lastPossibleBreak = Math.min(MAX_CHARACTER_PER_LINE, cueText.length() - 2);

    int bestBreak = -1;
    int bestDistanceFromCentre = Integer.MAX_VALUE;

    for (int index = firstPossibleBreak; index <= lastPossibleBreak; index++) {
      if (cueText.charAt(index) != ' ') {
        continue;
      }

      // Doubling avoids floats; only the comparison matters, not the value.
      int distanceFromCentre = Math.abs(index * 2 - cueText.length());

      // A comma or full stop makes the break read naturally, so it beats any even
      // split.
      boolean afterPunctuation = ".!?,".indexOf(cueText.charAt(index - 1)) >= 0;
      if (afterPunctuation) {
        distanceFromCentre -= MAX_CHARACTER_PER_LINE;
      }

      if (distanceFromCentre < bestDistanceFromCentre) {
        bestDistanceFromCentre = distanceFromCentre;
        bestBreak = index;
      }
    }

    if (bestBreak < 0) {
      // One unbreakable word longer than a line. A hard cut is the only way to keep
      // the
      // two-line limit; it cannot happen with ordinary transcripts.
      return cueText.substring(0, MAX_CHARACTER_PER_LINE) + "\n"
          + cueText.substring(MAX_CHARACTER_PER_LINE);
    }

    return cueText.substring(0, bestBreak) + "\n" + cueText.substring(bestBreak + 1);
  }

  // The spec wants a caption visible for at least one second, and Whisper
  // sometimes
  // returns a few tenths for a short word. Borrow time from the silence before
  // the next
  // cue, but never overlap it.
  private static void extendTooShortCuesIntoFollowingGaps(List<Cue> builtCues) {
    for (int index = 0; index + 1 < builtCues.size(); index++) {
      Cue cue = builtCues.get(index);
      double earliestAcceptableEnd = cue.startSeconds() + MIN_CUE_SECONDS;
      double nextCueStart = builtCues.get(index + 1).startSeconds();

      if (cue.endSeconds() < earliestAcceptableEnd && earliestAcceptableEnd <= nextCueStart) {
        builtCues.set(index, new Cue(cue.startSeconds(), earliestAcceptableEnd, cue.displayText()));
      }
    }
  }

  // Which caption belongs on screen at this media time. A linear walk over a few
  // dozen
  // cues, once per frame, is cheaper than any index or map, and needs no
  // synchronisation
  // because the list itself never changes after it is published.
  private String cueTextAt(double mediaSeconds) {
    List<Cue> currentCues = cues;

    if (currentCues == null) {
      return PLACEHOLDER_SUBTITLE_TEXT; // still transcribing, or the request failed
    }

    for (Cue cue : currentCues) {
      if (mediaSeconds >= cue.startSeconds() && mediaSeconds < cue.endSeconds()) {
        return cue.displayText();
      }
    }

    return null; // a gap between captions: draw nothing
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
