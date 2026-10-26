BYOK (Bring Your Own Key) is the ideal architecture for this tool.
Here is why it’s the best fit for both you and your users:
Why BYOK Is Great:
Zero Server Costs for You:
You don't need to host, monitor, or pay for a cloud server or proxy.
The tool runs autonomously on each user's device directly.
Free for the User:
Google AI Studio offers a free tier for Gemini models (gemini-3.8-flash / gemini-2.5-flash), so regular users incur no monthly costs for voice queries.
Privacy by Design:
No intermediary servers or databases log the user’s voice or queries.
The API key and all settings remain strictly sandboxed in on-device Android storage (DEFAULT_DATASTORE).
When they ask a question, the phone connects directly over encrypted HTTPS to Google’s Generative AI endpoint.
How the User Experience Flows on the Light Phone:
One-Time Setup:
User grabs a free key from aistudio.google.com/app/apikey.
In the tool on their Light Phone, they tap Settings, paste the key once, and hit Save.
It is written to dataStore.edit { preferences[GEMINI_API_KEY] = key }.
Daily Use:
User holds the microphone and asks their question.
Gemini searches with Google Search grounding and returns the concise, factual answer on the e-ink screen (with speech playback if enabled).
User can press and hold the answer at any time to forward the text straight to their email inbox, just like LightOS SMS forwarding.
