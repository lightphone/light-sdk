import React, { useState } from 'react';
import { Key, ShieldCheck, Terminal, Smartphone, Copy, Check, Info, Cpu, ArrowRight } from 'lucide-react';

export const DataStoreGuide: React.FC = () => {
  const [copiedSnippet, setCopiedSnippet] = useState<string | null>(null);

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedSnippet(id);
    setTimeout(() => setCopiedSnippet(null), 2000);
  };

  const adbSnippet = `# 1. Push your API key directly to your Light Phone III via ADB:
adb shell "run-as com.thelightphone.gemini mkdir -p files/datastore"
# Or trigger the tool's built-in Settings screen to store it into DEFAULT_DATASTORE`;

  const kotlinDataStoreSnippet = `// 1. Define preference key in DataStoreKeys.kt
val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")

// 2. Write to the Light Phone's DEFAULT_DATASTORE (SettingsViewModel.kt)
viewModelScope.launch {
    lightContext.dataStore.edit { preferences ->
        preferences[DataStoreKeys.GEMINI_API_KEY] = "AIzaSy..."
    }
}

// 3. Read key when querying (GeminiToolViewModel.kt)
val prefs = lightContext.dataStore.data.first()
val apiKey = prefs[DataStoreKeys.GEMINI_API_KEY] ?: ""`;

  return (
    <div className="bg-[#121212] border border-[#262626] rounded-2xl p-6 sm:p-8 text-neutral-300 shadow-2xl">
      {/* Title & Overview */}
      <div className="flex items-center gap-3 mb-6">
        <div className="p-2.5 rounded-xl bg-neutral-800 text-white border border-neutral-700">
          <Key className="w-5 h-5" />
        </div>
        <div>
          <h2 className="text-xl font-medium text-white tracking-tight">
            How to Input &amp; Store Your Gemini Key in LightOS DataStore
          </h2>
          <p className="text-xs text-neutral-400 mt-0.5">
            Step-by-step directions for configuring your API key on the Light Phone III
          </p>
        </div>
      </div>

      {/* Philosophy Card */}
      <div className="p-4 rounded-xl bg-[#181818] border border-[#2d2d2d] mb-8 flex items-start gap-3">
        <ShieldCheck className="w-5 h-5 text-emerald-400 flex-shrink-0 mt-0.5" />
        <div className="text-xs leading-relaxed">
          <span className="font-semibold text-white">Why DataStore? </span>
          The Light Phone philosophy enforces strict privacy and no data tracking. The Light SDK provides an isolated, sandboxed{' '}
          <code className="text-amber-300 font-mono bg-[#222] px-1 py-0.5 rounded">
            dataStore: DataStore&lt;Preferences&gt;
          </code>{' '}
          for each tool. Your Gemini API key is saved exclusively in this local on-device file (
          <code className="text-neutral-400 font-mono">DEFAULT_DATASTORE</code>
          ). It is never sent to Light's servers or third-party loggers—only directly to Google Generative AI when you issue a query.
        </div>
      </div>

      {/* Steps */}
      <div className="space-y-8">
        {/* Step 1 */}
        <div className="flex items-start gap-4">
          <div className="w-7 h-7 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-xs font-mono font-bold text-white flex-shrink-0">
            1
          </div>
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-white mb-1">
              Generate a Gemini API Key from Google AI Studio
            </h3>
            <p className="text-xs text-neutral-400 mb-2 leading-relaxed">
              If you do not already have one, obtain a free API key from Google AI Studio:
            </p>
            <ol className="text-xs text-neutral-400 space-y-1 list-decimal list-inside ml-1">
              <li>
                Navigate to{' '}
                <a
                  href="https://aistudio.google.com/app/apikey"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-white underline hover:text-neutral-200"
                >
                  aistudio.google.com/app/apikey
                </a>
              </li>
              <li>Sign in with your Google Account and click <strong>Create API Key</strong>.</li>
              <li>Copy the generated key (starts with <code className="text-neutral-300 font-mono">AIzaSy...</code>).</li>
            </ol>
          </div>
        </div>

        {/* Step 2 */}
        <div className="flex items-start gap-4">
          <div className="w-7 h-7 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-xs font-mono font-bold text-white flex-shrink-0">
            2
          </div>
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-white mb-1">
              Method A: Input Key directly in the Phone's Settings Screen (User Friendly)
            </h3>
            <p className="text-xs text-neutral-400 mb-3 leading-relaxed">
              The tool includes a dedicated on-device settings screen designed for LightOS:
            </p>
            <div className="p-3.5 rounded-lg bg-[#161616] border border-[#262626] text-xs space-y-2">
              <div className="flex items-center gap-2 text-white">
                <Smartphone className="w-4 h-4 text-neutral-400" />
                <span>On your Light Phone III:</span>
              </div>
              <ul className="text-neutral-400 space-y-1 list-disc list-inside ml-2">
                <li>Open the <strong>Gemini</strong> tool from your LightOS tool menu.</li>
                <li>Tap <strong>Settings</strong> at the top right of the screen.</li>
                <li>Tap the <strong>Gemini API Key</strong> input field and paste or type your key.</li>
                <li>Tap <strong>Save Key</strong>. The tool executes <code className="text-neutral-300 font-mono">dataStore.edit &#123; ... &#125;</code> to save it locally.</li>
                <li>Press the bottom <strong>Back</strong> arrow to return to the <strong>Ask</strong> screen and start querying.</li>
              </ul>
            </div>
          </div>
        </div>

        {/* Step 3 */}
        <div className="flex items-start gap-4">
          <div className="w-7 h-7 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-xs font-mono font-bold text-white flex-shrink-0">
            3
          </div>
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-white mb-1">
              Method B: Pre-seed via Sideload / Gradle / ADB (Developer Friendly)
            </h3>
            <p className="text-xs text-neutral-400 mb-3 leading-relaxed">
              If sideloading or building from source and you prefer not to type a long API key on the phone's keyboard:
            </p>

            <div className="relative rounded-lg bg-[#0d0d0d] border border-[#222222] p-3 font-mono text-xs text-neutral-300">
              <button
                onClick={() => handleCopy(adbSnippet, 'adb')}
                className="absolute top-2.5 right-2.5 p-1 rounded bg-[#222] hover:bg-[#333] text-neutral-400 hover:text-white transition"
                title="Copy script"
              >
                {copiedSnippet === 'adb' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
              <pre className="overflow-x-auto pr-8">{adbSnippet}</pre>
            </div>
            <p className="text-[11px] text-neutral-500 mt-2">
              Alternatively, pass <code className="text-neutral-400 font-mono">-Pgemini.apiKey="AIzaSy..."</code> to your Gradle build config in <code className="text-neutral-400 font-mono">buildConfigField</code> to bake it into debug builds.
            </p>
          </div>
        </div>

        {/* Step 4 */}
        <div className="flex items-start gap-4">
          <div className="w-7 h-7 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-xs font-mono font-bold text-white flex-shrink-0">
            4
          </div>
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-white mb-1">
              How the Kotlin Code Reads &amp; Writes the Key
            </h3>
            <p className="text-xs text-neutral-400 mb-2 leading-relaxed">
              Here is the exact Light SDK DataStore implementation included in the tool code:
            </p>

            <div className="relative rounded-lg bg-[#0d0d0d] border border-[#222222] p-3 font-mono text-xs text-neutral-300">
              <button
                onClick={() => handleCopy(kotlinDataStoreSnippet, 'kotlin')}
                className="absolute top-2.5 right-2.5 p-1 rounded bg-[#222] hover:bg-[#333] text-neutral-400 hover:text-white transition"
                title="Copy Kotlin snippet"
              >
                {copiedSnippet === 'kotlin' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
              <pre className="overflow-x-auto pr-8">{kotlinDataStoreSnippet}</pre>
            </div>
          </div>
        </div>
      </div>

      {/* Verification checklist */}
      <div className="mt-8 pt-6 border-t border-[#262626]">
        <h4 className="text-xs font-semibold text-neutral-300 uppercase tracking-wider mb-3 font-mono">
          Verification Checklist
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
          <div className="p-3 rounded-lg bg-[#161616] border border-[#262626]">
            <div className="font-medium text-white mb-1">1. Test in Emulator</div>
            <p className="text-neutral-400 text-[11px]">
              Run on LightOS Emulator (API 34, 1080x1240). Verify queries return in under 2 seconds.
            </p>
          </div>
          <div className="p-3 rounded-lg bg-[#161616] border border-[#262626]">
            <div className="font-medium text-white mb-1">2. Sideload to Device</div>
            <p className="text-neutral-400 text-[11px]">
              Enable Developer Mode on Light Phone III settings and run <code>./gradlew uploadTool</code>.
            </p>
          </div>
          <div className="p-3 rounded-lg bg-[#161616] border border-[#262626]">
            <div className="font-medium text-white mb-1">3. Privacy Guarantee</div>
            <p className="text-neutral-400 text-[11px]">
              Only direct calls to Gemini endpoint. No analytics, tracking or persistent user telemetry.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
