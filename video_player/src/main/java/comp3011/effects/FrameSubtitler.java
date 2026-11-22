package comp3011.effects;

import comp3011.dto.Cue;
import comp3011.dto.InfoFrame;
import comp3011.dto.SubtitleResponse;
import comp3011.dto.SubtitleSegment;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import tools.jackson.databind.ObjectMapper;

/**
 * Adds captions to the picture while the video plays.
 *
 * <p>
 * The sound is chopped into ten-second pieces. Each piece is sent to the
 * subtitle server, which asks OpenAI to turn the speech into text and sends
 * back the words and their times. The player turns those into short captions
 * and paints them near the bottom of the picture.
 * </p>
 *
 * <p>
 * All the slow work - reading the sound and waiting for the server - happens on
 * a background helper, so the video never pauses to wait for a transcript.
 * </p>
 *
 * <p>
 * Built with AI assistance (DeepSeek V4.1 Flash Free). Design, review and
 * testing by the author.
 * </p>
 */
public class FrameSubtitler extends FrameProcessor {
  // where the subtitle server lives, read from subtitle.properties
  private static final String SUBTITLE_URL = readSubtitleUrl();

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // ten seconds is short enough that the first caption appears quickly, and
  // long enough that Whisper still has a sentence of context to work with
  private static final int CHUNK_SECONDS = 10;
  private static final int SAMPLE_RATE = 16_000;

  // how far ahead of the picture the helper may work: enough to hide the server
  // round trip, small enough that a viewer who stops early does not pay for the
  // whole video
  private static final int LOOKAHEAD_SECONDS = 20;

  // a WAV file starts with a fixed 44-byte note describing the sound inside
  private static final int WAV_HEADER_BYTES = 44;

  // this is the gap between the caption and the bottom edge of the frame
  private static final int BOTTOM_MARGIN = 12;

  // Reference:
  // https://partnerhelp.netflixstudios.com/hc/en-us/articles/215758617-Timed-Text-Style-Guide-General-Requirements
  private static final int MAX_CHARACTERS_PER_LINE = 42;
  private static final int MAX_CUE_CHARACTERS = MAX_CHARACTERS_PER_LINE * 2;
  private static final double MAX_CUE_SECONDS = 7.0;
  private static final double MIN_CUE_SECONDS = 1.0;

  // off means captions are not drawn. The helper keeps any words it has already
  // fetched, so switching captions back on shows them again immediately.
  private volatile boolean subtitlesVisible = true;

  // every caption for this video, in order. Null means "no transcript yet";
  // an empty list means "the transcript is ready and there is nothing to say".
  private volatile List<Cue> captionCues;

  // where the picture is right now, in seconds from the start of the video.
  // The background helper uses this to stay a little ahead of the viewer.
  private volatile double playheadSeconds;

  // each time a video starts, its helper gets a new number. An older helper
  // that is still running sees a different number and quietly stops.
  private final AtomicInteger workerGeneration = new AtomicInteger();

  // switches captions on or off
  public void setSubtitlesVisible(boolean visible) {
    this.subtitlesVisible = visible;
  }

  public boolean areSubtitlesVisible() {
    return subtitlesVisible;
  }

  // starts a fresh background helper for this video. Called once per play, so a
  // new file never shows the previous file's captions.
  public void startSubtitlesFor(File video) {
    // the old playhead would otherwise look like the viewer is already at the
    // end of the new video, and the helper would skip the first pieces
    playheadSeconds = 0;

    if (SUBTITLE_URL == null) {
      System.err.println("No subtitle server address is configured; captions are unavailable");
      captionCues = List.of();
      return;
    }

    captionCues = null;

    int workerNumber = workerGeneration.incrementAndGet();
    Thread worker = new Thread(() -> transcribeChunks(video, workerNumber), "subtitle-worker");
    worker.setDaemon(true); // never keeps the player open
    worker.start();
  }

  @Override
  public void process(Frame frame, InfoFrame info) throws Exception {
    // Remember where the picture is, every frame, even while captions are
    // switched off. The helper uses this to know how far ahead it may work.
    playheadSeconds = info.timestampUs() / 1_000_000.0;

    if (!subtitlesVisible) {
      return; // off means the very next frame is clean again
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

    String captionText = captionTextAt(playheadSeconds);

    if (captionText == null) {
      return; // nothing to say at this moment: leave the picture untouched
    }

    int fontSize = Math.max(12, videoInfo.imageHeight() / 24);
    drawCaptionOnFrame(pixels, renderCaptionImage(captionText, fontSize));
  }

  // Reads the video's sound, ten seconds at a time, and turns each piece into
  // captions. Runs on its own thread.
  private void transcribeChunks(File video, int workerNumber) {
    List<Cue> collectedCues = new ArrayList<>();
    long samplesRead = 0;

    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
      grabber.setSampleRate(SAMPLE_RATE);
      grabber.setAudioChannels(1);
      grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
      grabber.start();

      int samplesPerChunk = CHUNK_SECONDS * SAMPLE_RATE;

      while (isCurrentWorker(workerNumber)) {
        WavChunk chunk = readChunk(grabber, samplesPerChunk);

        if (chunk == null) {
          break; // the sound has ended
        }

        double chunkStartSeconds = (double) samplesRead / SAMPLE_RATE;
        samplesRead += chunk.sampleCount();

        waitForViewer(workerNumber, chunkStartSeconds);

        if (!isCurrentWorker(workerNumber)) {
          break;
        }

        // shortcut: a piece already well behind the picture is skipped, because
        // nobody can see its caption. If the viewer jumps around and later
        // replays the whole video, those pieces are paid for once on that
        // replay.
        if (chunkStartSeconds + CHUNK_SECONDS < playheadSeconds - CHUNK_SECONDS) {
          continue;
        }

        try {
          SubtitleResponse response = MAPPER.readValue(
              transcribeChunk(chunk.audioBytes()), SubtitleResponse.class);

          collectedCues.addAll(buildCues(response.segments(), chunkStartSeconds));
          extendShortCuesIntoGaps(collectedCues);
          captionCues = List.copyOf(collectedCues);
        } catch (Exception e) {
          // one failed piece should not throw away the captions already shown
          System.err.println("Subtitle piece at " + chunkStartSeconds + "s failed: " + e);

          if (captionCues == null) {
            captionCues = List.of(); // stop showing "not ready" forever
          }
        }
      }
    } catch (Exception e) {
      System.err.println("Subtitle helper stopped: " + e);

      if (captionCues == null) {
        captionCues = List.of();
      }
    }
  }

  // Waits until captions are wanted and until the picture is close to this
  // piece. Without the wait the player would transcribe the whole video in one
  // burst, paying for sound the viewer may never reach.
  private void waitForViewer(int workerNumber, double chunkStartSeconds) throws InterruptedException {
    while (isCurrentWorker(workerNumber)
        && (!subtitlesVisible || playheadSeconds < chunkStartSeconds - LOOKAHEAD_SECONDS)) {
      Thread.sleep(100);
    }
  }

  private boolean isCurrentWorker(int workerNumber) {
    return workerNumber == workerGeneration.get();
  }

  // holds one piece of ready-to-send sound and how many samples it contains
  private record WavChunk(byte[] audioBytes, int sampleCount) {
  }

  // reads up to ten seconds of sound; null means there is no more sound
  private static WavChunk readChunk(FFmpegFrameGrabber grabber, int samplesPerChunk) throws Exception {
    short[] samples = new short[samplesPerChunk];
    int filled = 0;

    while (filled < samplesPerChunk) {
      Frame audioFrame = grabber.grabSamples();

      if (audioFrame == null) {
        break; // end of the sound
      }

      if (audioFrame.samples == null || audioFrame.samples.length == 0) {
        continue; // this frame carried no sound
      }

      Buffer firstChannel = audioFrame.samples[0];

      if (firstChannel == null) {
        continue;
      }

      filled += copySamples(firstChannel, samples, filled);
    }

    if (filled == 0) {
      return null;
    }

    return new WavChunk(wrapAsWav(samples, filled), filled);
  }

  // copies sound samples into the piece being built. The decoder normally hands
  // back whole numbers, which is the format the subtitle server receives.
  // Fractional samples are converted as a safety net in case a machine's
  // decoder ignores the requested format.
  private static int copySamples(Buffer source, short[] destination, int offset) {
    if (source instanceof ShortBuffer shortSamples) {
      int count = Math.min(shortSamples.remaining(), destination.length - offset);
      shortSamples.get(destination, offset, count);
      return count;
    }

    if (source instanceof FloatBuffer floatSamples) {
      int count = Math.min(floatSamples.remaining(), destination.length - offset);

      for (int index = 0; index < count; index++) {
        int value = Math.round(floatSamples.get() * 32767f);
        destination[offset + index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
      }

      return count;
    }

    throw new IllegalStateException("Unsupported sound sample format: " + source.getClass().getSimpleName());
  }

  // The server needs to know the shape of the sound it receives. A WAV header
  // is a fixed 44-byte note at the front saying "16-bit, mono, 16 000 samples a
  // second", followed by the samples themselves. The same sound always becomes
  // exactly the same bytes, which is how the server recognises it on a replay.
  private static byte[] wrapAsWav(short[] samples, int sampleCount) {
    int dataBytes = sampleCount * 2;
    ByteBuffer wav = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN);

    wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(36 + dataBytes); // size of everything after this number
    wav.put("WAVE".getBytes(StandardCharsets.US_ASCII));

    wav.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(16); // size of the format description below
    wav.putShort((short) 1); // 1 = uncompressed sound
    wav.putShort((short) 1); // one channel
    wav.putInt(SAMPLE_RATE); // samples per second
    wav.putInt(SAMPLE_RATE * 2); // bytes per second
    wav.putShort((short) 2); // bytes per sample
    wav.putShort((short) 16); // bits per sample

    wav.put("data".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(dataBytes);

    for (int index = 0; index < sampleCount; index++) {
      wav.putShort(samples[index]);
    }

    return wav.array();
  }

  // sends one piece of sound to the subtitle server and returns its answer
  private byte[] transcribeChunk(byte[] wavChunk) throws IOException, InterruptedException {
    // a boundary is a text marker that separates the parts of a form upload
    String boundary = UUID.randomUUID().toString();

    byte[] requestHead = ("--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"\r\n"
        + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);

    byte[] requestTail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

    HttpRequest request = HttpRequest.newBuilder(URI.create(SUBTITLE_URL))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(requestHead, wavChunk, requestTail)))
        .build();

    HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());

    if (response.statusCode() != 200) {
      throw new IOException("The subtitle server answered with status " + response.statusCode());
    }

    return response.body();
  }

  // Which caption belongs on screen at this moment. A simple walk over a few
  // dozen captions, once per frame, is cheaper than any index or map.
  private String captionTextAt(double mediaSeconds) {
    List<Cue> visibleCues = captionCues;

    if (visibleCues == null) {
      return null; // the first transcript has not arrived yet
    }

    for (Cue cue : visibleCues) {
      if (mediaSeconds >= cue.startSeconds() && mediaSeconds < cue.endSeconds()) {
        return cue.displayText();
      }
    }

    return null; // a pause between captions: draw nothing
  }

  private BufferedImage renderCaptionImage(String captionText, int fontSize) {
    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);

    // Thickness grows with the text, and max() keeps a visible outline on small
    // frames where the division would give zero.
    int outlineThickness = Math.max(1, fontSize / 16);

    // The outline spreads outward from the letters, so the padding covers that
    // spread plus a couple of pixels for the soft edges.
    int padding = outlineThickness * 2 + 2;

    // A caption holds at most two lines, separated by '\n' when the cue logic
    // wrapped it.
    String[] lines = captionText.split("\n", -1);

    // Measure first: the image must be as wide as the widest line and as tall
    // as all lines together, otherwise centring would be wrong.
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

    int captionImageWidth = widestLineWidth + padding * 2;
    int captionImageHeight = lineHeight * lines.length + padding * 2;

    BufferedImage captionImage = new BufferedImage(captionImageWidth, captionImageHeight,
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D captionGraphics = captionImage.createGraphics();
    captionGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    captionGraphics.setFont(font);

    for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      // Each line is centred on its own, so a short second line sits under the
      // middle of the first, the way broadcast captions look.
      int lineWidth = textMetrics.stringWidth(lines[lineIndex]);
      int lineX = (captionImageWidth - lineWidth) / 2;
      int lineBaselineY = padding + textMetrics.getAscent() + lineIndex * lineHeight;

      // Stamp the line in black at every offset around it. That is what makes
      // the outline follow the letter shapes instead of drawing a box.
      captionGraphics.setColor(Color.BLACK);
      for (int offsetX = -outlineThickness; offsetX <= outlineThickness; offsetX++) {
        for (int offsetY = -outlineThickness; offsetY <= outlineThickness; offsetY++) {
          captionGraphics.drawString(lines[lineIndex], lineX + offsetX, lineBaselineY + offsetY);
        }
      }

      // The white letters go on top of the black outline.
      captionGraphics.setColor(Color.WHITE);
      captionGraphics.drawString(lines[lineIndex], lineX, lineBaselineY);
    }

    captionGraphics.dispose();
    return captionImage;
  }

  // Paints the caption over the picture, mixing each white letter pixel with
  // whatever colour the video already has underneath it.
  private void drawCaptionOnFrame(ByteBuffer pixels, BufferedImage captionImage) {
    int captionWidth = captionImage.getWidth();
    int captionHeight = captionImage.getHeight();

    // drawing points
    int startX = (videoInfo.imageWidth() - captionWidth) / 2; // centre the caption
    int startY = videoInfo.imageHeight() - BOTTOM_MARGIN - captionHeight;

    // frame too small for this caption
    if (startX < 0 || startY < 0) {
      return;
    }

    for (int row = 0; row < captionHeight; row++) {
      for (int column = 0; column < captionWidth; column++) {
        int captionPixel = captionImage.getRGB(column, row);
        int opacity = (captionPixel >>> 24) & 0xff;

        if (opacity == 0) {
          continue; // fully transparent: nothing to draw here
        }

        // White was used to draw the letters, so one colour channel is enough.
        int captionShade = (captionPixel >>> 16) & 0xff;
        int videoPixelOffset = (startY + row) * videoInfo.imageStride()
            + (startX + column) * videoInfo.imageChannels();

        for (int channel = 0; channel < videoInfo.imageChannels(); channel++) {
          int videoShade = pixels.get(videoPixelOffset + channel) & 0xff;

          // 0 = keep the video colour, 255 = use the caption colour. Values in
          // between blend the two, which keeps the picture visible through the
          // soft edges of the letters.
          pixels.put(videoPixelOffset + channel,
              (byte) ((captionShade * opacity + videoShade * (255 - opacity)) / 255));
        }
      }
    }
  }

  // Turns the server's sentences into short captions. The start of this piece
  // of sound is added to every time, because the server numbers each piece from
  // zero while the video runs from beginning to end.
  static List<Cue> buildCues(List<SubtitleSegment> segments, double chunkStartSeconds) {
    List<Cue> builtCues = new ArrayList<>();

    if (segments == null) {
      return builtCues;
    }

    for (SubtitleSegment segment : segments) {
      addCuesForSegment(segment, chunkStartSeconds, builtCues);
    }

    extendShortCuesIntoGaps(builtCues);
    return builtCues;
  }

  private static void addCuesForSegment(SubtitleSegment segment, double chunkStartSeconds,
      List<Cue> builtCues) {
    String transcribedText = segment.outputText() == null ? "" : segment.outputText().trim();

    double segmentStartSeconds = chunkStartSeconds + segment.start();
    double segmentEndSeconds = chunkStartSeconds + segment.end();
    double segmentLastingSeconds = segmentEndSeconds - segmentStartSeconds;

    if (transcribedText.isEmpty() || segmentLastingSeconds <= 0) {
      return; // nothing was said, or the times make no sense
    }

    // how many captions the text needs, given the screen width
    int cuesRequiredForTextLength = Math.ceilDiv(transcribedText.length(), MAX_CUE_CHARACTERS);

    // how many captions the time needs, so none stays longer than seven seconds
    int cuesRequiredForSegmentDuration = (int) Math.ceil(segmentLastingSeconds / MAX_CUE_SECONDS);

    // we'll take the one that covers both requirements (by text length AND by
    // segment duration) at the same time preventing creating empty cues
    int totalCuesToCreate = Math.min(
        Math.max(cuesRequiredForTextLength, cuesRequiredForSegmentDuration),
        transcribedText.length());

    int cueStartIndex = 0; // first character of the caption being built

    for (int cueNumber = 0; cueNumber < totalCuesToCreate; cueNumber++) {
      boolean isLastCue = cueNumber == totalCuesToCreate - 1; // don't accidentally drop the last word

      // divide the remaining characters evenly among the remaining captions,
      // rounding to a whole number and ensuring at least one character is used
      int charactersToAdd = Math.max(1,
          Math.round((transcribedText.length() - cueStartIndex) / (float) (totalCuesToCreate - cueNumber)));

      int desiredEndingIndex = cueStartIndex + charactersToAdd;

      int cueEndIndex = isLastCue ? transcribedText.length()
          : findNaturalCueSplit(transcribedText, cueStartIndex, desiredEndingIndex);

      if (cueEndIndex <= cueStartIndex) {
        break; // the text ran out; never show an empty caption
      }

      // we need the elapsed time to decide when the next caption should appear
      double elapsedTime = segmentLastingSeconds * cueStartIndex / transcribedText.length();

      double cueStartSeconds = segmentStartSeconds + elapsedTime;
      double cueEndSeconds = isLastCue ? segmentEndSeconds
          : segmentStartSeconds + segmentLastingSeconds * cueEndIndex / transcribedText.length();

      String cueText = transcribedText.substring(cueStartIndex, cueEndIndex).trim();
      builtCues.add(new Cue(cueStartSeconds, cueEndSeconds, wrapCueIntoTwoLines(cueText)));

      cueStartIndex = cueEndIndex;
    }
  }

  // Looks near the ideal place for a break and prefers a space. A break just
  // after a full stop, question mark or exclamation mark reads best, so it wins
  // over a slightly nearer mid-sentence space.
  private static int findNaturalCueSplit(String text, int startIndex, int desiredIndex) {
    int searchRadius = 12; // randomly chosen, may fix if visually looking bad

    // the current caption must get at least one character, and the method may
    // search a little before the ideal split point
    int earliestValidSplitIndex = Math.max(startIndex + 1, desiredIndex - searchRadius);

    // the search may also look a little to the right, but never past the screen
    // width or the end of the text
    int latestValidSplitIndex = Math.min(
        Math.min(desiredIndex + searchRadius, startIndex + MAX_CUE_CHARACTERS - 1),
        text.length() - 1);

    int bestIndex = -1;
    int bestScore = Integer.MAX_VALUE;

    for (int index = earliestValidSplitIndex; index <= latestValidSplitIndex; index++) {
      if (text.charAt(index) != ' ') {
        continue;
      }

      int distanceFromDesiredLocation = Math.abs(index - desiredIndex);

      // A cut right after . ! ? ends a thought, so it beats a nearer
      // mid-sentence cut. The 13-character bonus is one more than the whole
      // search radius, which means any punctuation break inside the window wins.
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

  // Folds a caption into at most two lines, preferring a balanced break and,
  // when possible, one after punctuation. The caller guarantees at most 84
  // characters, which is why two lines are always enough.
  private static String wrapCueIntoTwoLines(String cueText) {
    if (cueText.length() <= MAX_CHARACTERS_PER_LINE) {
      return cueText; // fits on one line
    }

    // Both halves must fit, so only breaks in this range are legal. The +1/-1
    // account for the space that is removed at the break.
    int firstPossibleBreak = Math.max(1, cueText.length() - MAX_CHARACTERS_PER_LINE - 1);
    int lastPossibleBreak = Math.min(MAX_CHARACTERS_PER_LINE, cueText.length() - 2);

    int bestBreak = -1;
    int bestDistanceFromCentre = Integer.MAX_VALUE;

    for (int index = firstPossibleBreak; index <= lastPossibleBreak; index++) {
      if (cueText.charAt(index) != ' ') {
        continue;
      }

      // Doubling avoids fractions; only the comparison matters, not the value.
      int distanceFromCentre = Math.abs(index * 2 - cueText.length());

      // A comma or full stop makes the break read naturally, so it beats any
      // even split.
      boolean afterPunctuation = ".!?,".indexOf(cueText.charAt(index - 1)) >= 0;
      if (afterPunctuation) {
        distanceFromCentre -= MAX_CHARACTERS_PER_LINE;
      }

      if (distanceFromCentre < bestDistanceFromCentre) {
        bestDistanceFromCentre = distanceFromCentre;
        bestBreak = index;
      }
    }

    if (bestBreak < 0) {
      // One unbreakable word longer than a line. A hard cut is the only way to
      // keep the two-line limit; it cannot happen with ordinary speech.
      return cueText.substring(0, MAX_CHARACTERS_PER_LINE) + "\n"
          + cueText.substring(MAX_CHARACTERS_PER_LINE);
    }

    return cueText.substring(0, bestBreak) + "\n" + cueText.substring(bestBreak + 1);
  }

  // The spec wants each caption visible for at least one second, and Whisper
  // sometimes returns a few tenths for a short word. Borrow time from the
  // silence before the next caption, but never overlap it.
  private static void extendShortCuesIntoGaps(List<Cue> builtCues) {
    for (int index = 0; index + 1 < builtCues.size(); index++) {
      Cue cue = builtCues.get(index);
      double earliestAcceptableEnd = cue.startSeconds() + MIN_CUE_SECONDS;
      double nextCueStart = builtCues.get(index + 1).startSeconds();

      if (cue.endSeconds() < earliestAcceptableEnd && earliestAcceptableEnd <= nextCueStart) {
        builtCues.set(index, new Cue(cue.startSeconds(), earliestAcceptableEnd, cue.displayText()));
      }
    }
  }

  // Reads the server address from subtitle.properties, so a different machine
  // can be pointed at a different server without recompiling.
  private static String readSubtitleUrl() {
    // use any class here, as Java only needs a reference to find the file
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
