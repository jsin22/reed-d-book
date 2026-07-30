Task: Modify audiblez to Output Sync Metadata
Instructions for the AI Assistant:
I am modifying the audiblez repository to not only generate .m4b audiobooks but also to output a .json synchronization file. This file will map each text chunk to its exact audio start and end timestamps so I can build a read-along e-reader app.

Please update the core generation loop in the audiblez Python script with the following logic:

1. Intercept the Chunk Generation
Text-to-speech models process text in chunks. Locate the loop where audiblez feeds text chunks (sentences or paragraphs) into the Kokoro TTS engine.

2. Calculate Exact Duration
As the script generates the audio for each chunk, it must record the exact duration of that specific generated audio clip.

The Math: You can calculate the duration in seconds by dividing the length of the generated audio array (the number of frames) by the sample rate (which is usually 24000Hz for Kokoro).

chunk_duration = len(audio_array) / sample_rate

3. Maintain a Running Timestamp Tally
Create a running timestamp tally. Before the loop starts, initialize a variable like current_time = 0.0.

For each chunk:

start_time = current_time

end_time = start_time + chunk_duration

Append a dictionary to a list: {"text": chunk_text, "start": start_time, "end": end_time}

Update the tally: current_time = end_time


Example: If Sentence 1 takes 4.5 seconds, its start time is 0.0 and end time is 4.5. Sentence 2 starts at 4.5.

4. Output the JSON Mapping
After the entire book is processed and the .m4b file is finalized, output a .json file containing this text-to-timestamp mapping.

Save it in the same directory, with the same base filename as the audio file (e.g., book_title.m4b and book_title.json).
