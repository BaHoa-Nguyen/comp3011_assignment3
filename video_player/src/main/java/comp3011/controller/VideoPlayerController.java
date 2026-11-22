/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Ba Hoa Nguyen - a1938499, in collaboration with MiMo-V2.6-Flash Free
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.controller;

import comp3011.model.VideoPlayerModel;
import comp3011.view.VideoPlayerView;
import comp3011.effects.FrameProcessor;
import comp3011.effects.FrameSubtitler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * The controller part of the video player model-view-controller architecture.
 *
 * <p>
 * Wires up events from the {@link VideoPlayerView} to the
 * {@link VideoPlayerModel}.
 * This includes button actions, keyboard shortcuts and display resizing.
 * </p>
 */
public class VideoPlayerController {
  private final VideoPlayerModel model;
  private final VideoPlayerView view;
  private final BiConsumer<Integer, Integer> videoSizeChangedHandler;

  private final FrameSubtitler frameSubtitler;

  public VideoPlayerController(
      boolean audioEnabled,
      List<FrameProcessor> frameProcessors,
      BiConsumer<Integer, Integer> videoSizeChangedHandler) {
    this.view = new VideoPlayerView();
    this.videoSizeChangedHandler = videoSizeChangedHandler;

    // The S key and the subtitle button must work even when the player was
    // started without -S. So a subtitle processor is always present. Without
    // -S it simply begins switched off: nothing is sent and nothing is paid
    // for until the viewer asks for captions.
    List<FrameProcessor> pipeline = new ArrayList<>(frameProcessors);

    // since subtitle is the only frame that can be toggled, finding it in the list
    // of frame is necessary
    frameSubtitler = pipeline.stream().filter(FrameSubtitler.class::isInstance).map(FrameSubtitler.class::cast)
        .findFirst().orElseGet(() -> {
          FrameSubtitler hiddenSubtitler = new FrameSubtitler();
          hiddenSubtitler.setSubtitlesVisible(false);
          pipeline.add(hiddenSubtitler); // last, so captions sit on top of other effects
          return hiddenSubtitler;
        });

    model = new VideoPlayerModel(
        audioEnabled,
        pipeline,
        this::onVideoSizeChanged,
        this::onFrameReady,
        this::onStatusChanged,
        this::onPlaybackStateChanged,
        this::onAudioOutputStateChanged);

    view.getSubtitleButton().setOnAction(_ -> toggleSubtitles());
    view.showSubtitleEnabled(frameSubtitler.areSubtitlesVisible());
    view.getStartOverButton().setOnAction(_ -> startOver());
    view.getBackFiveButton().setOnAction(_ -> backFiveSeconds());
    view.getPausePlayButton().setOnAction(_ -> togglePause());
    view.getForwardFiveButton().setOnAction(_ -> forwardFiveSeconds());
    view.getStopButton().setOnAction(_ -> stopAndExit());
    view.getAudioButton().setOnAction(_ -> toggleAudioOutput());
    view.showAudioEnabled(model.isAudioOutputEnabled());

  }

  public void installKeyboardShortcuts(Scene scene) {
    scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
      if (event.getCode() == KeyCode.ESCAPE) {
        stopAndExit();
        event.consume();
      } else if (event.getCode() == KeyCode.SPACE) {
        togglePause();
        event.consume();
      } else if (event.getCode() == KeyCode.LEFT) {
        backFiveSeconds();
        event.consume();
      } else if (event.getCode() == KeyCode.RIGHT) {
        forwardFiveSeconds();
        event.consume();
      } else if (event.getCode() == KeyCode.HOME) {
        startOver();
        event.consume();
      } else if (event.getCode() == KeyCode.A) {
        toggleAudioOutput();
        event.consume();
      } else if (event.getCode() == KeyCode.S) {
        toggleSubtitles();
        event.consume();
      }
    });
  }

  public VideoPlayerView getView() {
    return view;
  }

  public void play(File file) {
    view.setPlaybackControlsDisabled(false);
    frameSubtitler.startSubtitlesFor(file);
    model.play(file);
  }

  public void shutdown() {
    model.shutdown();
  }

  private void startOver() {
    model.startOver();
  }

  private void backFiveSeconds() {
    model.backFiveSeconds();
  }

  private void togglePause() {
    model.togglePause();
  }

  private void forwardFiveSeconds() {
    model.forwardFiveSeconds();
  }

  private void toggleAudioOutput() {
    model.toggleAudioOutput();
  }

  private void toggleSubtitles() {
    boolean showCaptions = !frameSubtitler.areSubtitlesVisible();
    frameSubtitler.setSubtitlesVisible(showCaptions);
    view.showSubtitleEnabled(showCaptions);
  }

  private void stopAndExit() {
    model.stopPlayback();
    javafx.application.Platform.exit();
  }

  private void onVideoSizeChanged(int width, int height) {
    videoSizeChangedHandler.accept(width, height);
  }

  private void onFrameReady(Image image) {
    view.setImage(image);
  }

  private void onStatusChanged(String status) {
    view.setStatus(status);
  }

  private void onPlaybackStateChanged(boolean playing, boolean paused) {
    if (playing && !paused) {
      view.showPauseAction();
    } else {
      view.showPlayAction();
    }
  }

  private void onAudioOutputStateChanged(boolean audioOutputEnabled) {
    view.showAudioEnabled(audioOutputEnabled);
  }
}
