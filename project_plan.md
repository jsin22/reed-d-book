EPUB to Audio Sync E-Reader: Project Plan

Project Objective: Build an Android e-reader app that converts .epub files to .m4b audiobooks using the Kokoro-82M TTS model, and synchronously highlights the text as the audio plays. The intensive audio generation will be offloaded to a local GPD Pocket 4 acting as the backend server.

Phase 1: Local Backend Server Setup (GPD Pocket 4)
The GPD Pocket 4 is a highly capable, ultra-portable PC that will act as the local development server over Wi-Fi.

Install Python and Git on the GPD Pocket 4 (Windows or Linux).

Set up a Python backend using FastAPI.

Implement a task queue using Celery with Redis to handle long-running background tasks.

Create an API endpoint that receives an .epub file from the Android app and immediately returns a task_id.

Create a polling endpoint for the app to check the status of the task_id.

Phase 2: Modifying audiblez and Kokoro TTS
The standard audiblez repository generates an .m4b file but lacks the metadata needed to sync specific sentences with the audio.

Clone the audiblez fork and install necessary packages including kokoro, soundfile, and the espeak-ng system package.

Modify the environment to leverage AMD's ROCm or ONNX Runtime (DirectML) to utilize the Pocket 4's integrated Radeon 890M GPU for faster processing.

Update the Python script to record the exact duration of each generated audio chunk during text-to-speech processing.

Configure the script to create a running timestamp tally, mapping sentence start and end times.

Output a .json or WebVTT (.vtt) file containing this text-to-timestamp mapping alongside the final .m4b file.

Phase 3: Android App Frontend (UI & EPUB Parsing)
The mobile frontend needs to parse the book, communicate with the local server, and handle large file downloads.

Build a modern, reactive UI using Kotlin and Jetpack Compose.

Integrate an established library like Readium (Kotlin) or FolioReader to parse and display the .epub file.

Build an upload flow using Retrofit or OkHttp to send local .epub files to the FastAPI backend.

Implement a robust download manager to retrieve the .m4b audiobook (which can be hundreds of megabytes) and the .json timing file once the backend task is complete.

Handle app state properly so the app can resume if the user closes it while waiting for the backend server.

Phase 4: Audio Playback and UI Synchronization
This phase connects the generated audio with the visual text on the screen.

Integrate ExoPlayer (AndroidX Media3) to handle the .m4b audio playback.

Configure ExoPlayer to support background playback and integrate with Android's media session controls.

Write a function that constantly polls ExoPlayer for its current playback position in milliseconds.

Cross-reference the current playback position with the downloaded JSON timing file.

Update the UI to highlight the corresponding text on the screen when the playback time falls between a chunk's start and end time.

Implement a continuous-scroll view for the text, or add logic to automatically flip the page when the audio reaches the last visible word on the screen.
