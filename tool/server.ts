import express from 'express';
import path from 'path';
import dotenv from 'dotenv';

dotenv.config();

const app = express();
const port = process.env.PORT || 3000;

app.use(express.json());

// API route for Gemini proxy
app.post('/api/gemini', async (req, res) => {
  try {
    const { prompt, mode } = req.body || {};
    const cleanPrompt = (prompt || '').trim();

    if (!cleanPrompt) {
      return res.status(400).json({ error: 'Prompt is required' });
    }

    const apiKey = process.env.GEMINI_API_KEY;

    if (!apiKey || apiKey === 'MY_GEMINI_API_KEY') {
      return res.json({
        text: `Prompt received: "${cleanPrompt}".\n\n(Note: Live Gemini API calls require GEMINI_API_KEY in environment secrets. In production on the Light Phone III, the key is read directly from on-device DataStore<Preferences>.)`,
        isMock: true,
      });
    }

    const { GoogleGenAI } = await import('@google/genai');
    const ai = new GoogleGenAI();

    const systemInstruction =
      'You are the Voice Search companion for the Light Phone III. The user is on a minimalist, distraction-free device. Provide direct, factual, calm, concise answers (1 to 3 short paragraphs maximum). Never use markdown headers, bold bullet lists, emojis, greetings, or conversational filler. Be accurate and helpful.';

    const response = await ai.models.generateContent({
      model: 'gemini-3.8-flash',
      contents: cleanPrompt,
      config: {
        systemInstruction,
        tools: [{ googleSearch: {} }],
      },
    });

    const text = response.text || 'No answer found.';

    const groundingMeta = response.candidates?.[0]?.groundingMetadata;
    const sources = (groundingMeta?.groundingChunks || [])
      .filter((c: any) => c.web?.uri && c.web?.title)
      .map((c: any) => ({
        title: c.web.title,
        url: c.web.uri,
      }))
      .slice(0, 3);

    res.json({
      text,
      sources,
      isMock: false,
    });
  } catch (error: any) {
    console.error('Server Gemini query error:', error);
    const errStr = error?.message || '';
    let userMessage = 'Error connecting to Gemini API.';
    let isQuota = false;

    if (errStr.includes('RESOURCE_EXHAUSTED') || errStr.includes('credits are depleted') || errStr.includes('402')) {
      userMessage = 'Gemini API credits depleted for this developer key. In the Light Phone tool, users supply their own personal key saved in their device DataStore.';
      isQuota = true;
    } else if (errStr.includes('API_KEY_INVALID') || errStr.includes('400')) {
      userMessage = 'Invalid API Key. Please verify key settings.';
    } else {
      userMessage = errStr;
    }

    res.json({
      text: `[Offline / Preview Mode]\n\nPrompt: "${(req.body?.prompt || '').trim()}"\n\nNotice: ${userMessage}\n\nOn the physical Light Phone III, the tool makes direct calls to Google Generative AI using the key stored in the device's DataStore<Preferences>.`,
      isMock: true,
      quotaNotice: isQuota,
    });
  }
});

// Serve static frontend in production
const distPath = path.resolve(import.meta.dirname, 'dist');
app.use(express.static(distPath));

app.get('*', (_req, res) => {
  res.sendFile(path.resolve(distPath, 'index.html'));
});

app.listen(port, () => {
  console.log(`Server listening on port ${port}`);
});
