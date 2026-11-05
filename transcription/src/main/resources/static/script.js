const recordBtn = document.querySelector(".record-btn");
const recordingStatus = document.querySelector(".status");
const transcription = document.querySelector(".transcription");

let isRecording = false; // for changing status
let isProcessing = false; // for checking if the system is processing the transcription or not
let mediaRecorder;
let mediaStream;
let audioChunks = [];

// change recording status when the button is clicked
recordBtn.addEventListener("click", async () => {
  if (isProcessing) {
    return;
  }

  if (!isRecording) {
    await startRecording();
  } else {
    stopRecording();
  }
});

const startRecording = async () => {
  try {
    audioChunks = []; // always empty when starting recording

    // request microphone permission
    mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1, // 1 person speak to the microphone => mono channel would be better
        echoCancellation: true,
        noiseSuppression: true,
      },
    });

    mediaRecorder = new MediaRecorder(mediaStream, {
      mimeType: "audio/webm;codec=opus", // using Opus for compressing the audio
      audioBitsPerSecond: 32000,
    });

    // storing the recording pieces
    mediaRecorder.ondataavailable = handleStoringChunks;

    mediaRecorder.onstop = handleStopRecording;

    mediaRecorder.start();

    isRecording = true;

    updateRecordingUI();
  } catch {
    isRecording = false;
    recordingStatus.textContent = "Microphone access denied!";
    recordingStatus.classList.add("error");
  }
};

const stopRecording = () => {
  // avoid stopping a recording session that isn't currently active
  if (mediaRecorder && mediaRecorder.state === "inactive") {
    return;
  }

  mediaRecorder.stop();
  mediaStream.getTracks().forEach((track) => track.stop()); // stop the microphone usage after the recording is stopped

  isRecording = false;
  updateRecordingUI();
  isProcessing = true;
};

const handleStoringChunks = (event) => {
  if (event.data.size > 0) {
    audioChunks.push(event.data);
  }
};

const handleStopRecording = async () => {
  if (audioChunks.length === 0) {
    handleError("No audio was recorded. Please try again.");
    return;
  }

  const audioBlob = new Blob(audioChunks, { type: "audio/webm" });

  await uploadAudio(audioBlob);
};

const uploadAudio = async (audioBlob) => {
  const formData = new FormData();

  formData.append("audio", audioBlob, "recording.webm");

  setProcessingState();

  try {
    const response = await fetch("/api/v1/transcribe", {
      method: "POST",
      body: formData,
    });

    if (!response.ok) {
      throw new Error(`Error. Status: ${response.status}`);
    }

    const text = await response.text();

    transcription.textContent = text || "No speech was detected!";

    isProcessing = false;
    recordingStatus.textContent =
      "Transcription complete. Ready to record again.";

    recordingStatus.classList.remove("processing");
    recordingStatus.classList.add("success");

    recordBtn.disabled = false;
    recordBtn.textContent = "Start Recording";
  } catch {
    handleError("Transcription failed. Please try again!");
    recordingStatus.textContent = "Error processing your audio!";
  }
};

const updateRecordingUI = () => {
  recordingStatus.classList.remove("error", "success", "processing");

  if (isRecording) {
    recordBtn.textContent = "Stop Recording";
    recordingStatus.textContent = "Recording...";
    recordingStatus.classList.add("recording"); // add flickering effect
  } else if (isProcessing) {
    setProcessingState();
  } else {
    recordBtn.textContent = "Start Recording";
    recordingStatus.textContent = "Not recording";
    recordingStatus.classList.remove("recording"); // remove flickering effect
  }
};

// set processing state
const setProcessingState = () => {
  recordBtn.disabled = true;
  recordBtn.textContent = "Processing...";

  recordingStatus.textContent =
    "Hold tight! We're processing your beautiful voice…";

  recordingStatus.classList.remove("recording", "success", "error");

  recordingStatus.classList.add("processing");

  transcription.textContent = "Transcribing your recording...";
};

// print out the error
const handleError = (message) => {
  isRecording = false;
  isProcessing = false;

  recordBtn.disabled = false;
  recordBtn.textContent = "Start Recording";

  recordingStatus.textContent = message;

  recordingStatus.classList.remove("recording", "processing", "success");

  recordingStatus.classList.add("error");

  transcription.textContent = "See your beautiful voice here...";
};
