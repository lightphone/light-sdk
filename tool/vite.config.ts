import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import { defineConfig, Plugin } from 'vite';
import dotenv from 'dotenv';

dotenv.config();

function geminiApiPlugin(): Plugin {
  return {
    name: 'gemini-api-plugin',
    configureServer(server) {
      server.middlewares.use('/api/gemini', async (req, res) => {
        if (req.method !== 'POST') {
          res.statusCode = 405;
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify({ error: 'Method not allowed' }));
          return;
        }

        let body = '';
        req.on('data', (chunk) => {
          body += chunk;
        });

        req.on('end', async () => {
          let data: any = {};
          try {
            data = JSON.parse(body || '{}');
            const prompt = (data.prompt || '').trim();
            const mode = data.mode || 'standard';

            if (!prompt) {
              res.statusCode = 400;
              res.setHeader('Content-Type', 'application/json');
              res.end(JSON.stringify({ error: 'Prompt is required' }));
              return;
            }

            const apiKey = process.env.GEMINI_API_KEY;

            if (!apiKey || apiKey === 'MY_GEMINI_API_KEY') {
              // Return helpful simulator response
              res.setHeader('Content-Type', 'application/json');
              res.end(JSON.stringify({
                text: `Prompt received: "${prompt}".\n\n(Note: Live Gemini API calls require GEMINI_API_KEY to be set in the environment secrets. In production on the Light Phone III, the key is read directly from the device's DataStore<Preferences>.)`,
                isMock: true
              }));
              return;
            }

            const { GoogleGenAI } = await import('@google/genai');
            const ai = new GoogleGenAI();

            const systemInstruction =
              'You are the Voice Search companion for the Light Phone III. The user is on a minimalist, distraction-free device. Provide direct, factual, calm, concise answers (1 to 3 short paragraphs maximum). Never use markdown headers, bold bullet lists, emojis, greetings, or conversational filler. Be accurate and helpful.';

            const response = await ai.models.generateContent({
              model: 'gemini-3.8-flash',
              contents: prompt,
              config: {
                systemInstruction,
                tools: [{ googleSearch: {} }],
              },
            });

            const text = response.text || 'No answer found.';

            // Extract grounding metadata / search sources if available
            const groundingMeta = response.candidates?.[0]?.groundingMetadata;
            const sources = (groundingMeta?.groundingChunks || [])
              .filter((c: any) => c.web?.uri && c.web?.title)
              .map((c: any) => ({
                title: c.web.title,
                url: c.web.uri,
              }))
              .slice(0, 3);

            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify({ text, sources, isMock: false }));
          } catch (error: any) {
            console.error('Gemini query error:', error);
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

            // Return a readable response or simulated fallback
            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify({
              text: `[Offline / Preview Mode]\n\nPrompt: "${(data?.prompt || '').trim()}"\n\nNotice: ${userMessage}\n\nOn the physical Light Phone III, the tool makes direct calls to Google Generative AI using the key stored in the device's DataStore<Preferences>.`,
              isMock: true,
              quotaNotice: isQuota
            }));
          }
        });
      });
    },
  };
}

export default defineConfig(() => {
  return {
    plugins: [react(), tailwindcss(), geminiApiPlugin()],
    resolve: {
      alias: {
        '@': path.resolve(import.meta.dirname, '.'),
      },
    },
    server: {
      hmr: process.env.DISABLE_HMR !== 'true',
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
    },
  };
});
