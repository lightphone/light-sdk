import React, { useState, useRef, useEffect } from 'react';
import {
  Mic,
  ArrowLeft,
  Settings,
  Sparkles,
  RefreshCw,
  Copy,
  Check,
  Send,
  Volume2,
  Trash2,
  Lock,
  Database,
  Cpu,
} from 'lucide-react';
import { SimulatorScreen, DisplayTheme, QueryHistoryItem, DataStorePreferences } from '../types';

interface LightPhoneSimulatorProps {
  theme: DisplayTheme;
  eInkRefresh: boolean;
  triggerRefresh: () => void;
  onOpenDataStoreGuide: () => void;
}

const PRESET_QUERIES = [
  'How does an optical lens bend light?',
  'What is the stoic concept of amor fati?',
  'Why do leaves turn red in autumn?',
  'Brief summary of the Apollo 11 mission',
  'Difference between RAM and ROM',
];

export const LightPhoneSimulator: React.FC<LightPhoneSimulatorProps> = ({
  theme,
  eInkRefresh,
  triggerRefresh,
  onOpenDataStoreGuide,
}) => {
  const [screen, setScreen] = useState<SimulatorScreen>('ask');
  const [prompt, setPrompt] = useState('');
  const [loading, setLoading] = useState(false);
  const [currentResponse, setCurrentResponse] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isMockResponse, setIsMockResponse] = useState(false);
  const [copied, setCopied] = useState(false);
  const [history, setHistory] = useState<QueryHistoryItem[]>([]);
  
  // DataStore state simulated on-device
  const [dataStore, setDataStore] = useState<DataStorePreferences>({
    geminiApiKey: '',
    conciseMode: true,
    maxTokens: 300,
    selectedModel: 'gemini-3.8-flash',
  });
  const [savedNotice, setSavedNotice] = useState(false);
  const [isRecording, setIsRecording] = useState(false);

  const inputRef = useRef<HTMLInputElement>(null);

  // Send query to server endpoint
  const handleSubmitQuery = async (queryText?: string) => {
    const textToQuery = (queryText || prompt).trim();
    if (!textToQuery || loading) return;

    triggerRefresh();
    setLoading(true);
    setErrorMessage(null);
    setCurrentResponse(null);

    try {
      const res = await fetch('/api/gemini', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: textToQuery,
          mode: dataStore.conciseMode ? 'concise' : 'standard',
        }),
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.error || 'Failed to generate response');
      }

      setCurrentResponse(data.text);
      setIsMockResponse(!!data.isMock);

      // Save to local device history
      const newItem: QueryHistoryItem = {
        id: Date.now().toString(),
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        prompt: textToQuery,
        response: data.text,
        isMock: data.isMock,
      };
      setHistory((prev) => [newItem, ...prev.slice(0, 19)]);
    } catch (err: any) {
      setErrorMessage(err.message || 'Network error');
    } finally {
      setLoading(false);
    }
  };

  const handleCopy = () => {
    if (currentResponse) {
      navigator.clipboard.writeText(currentResponse);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleSaveDataStore = () => {
    triggerRefresh();
    setSavedNotice(true);
    setTimeout(() => setSavedNotice(false), 2500);
  };

  // Simulate audio capture
  const handleVoiceToggle = () => {
    if (isRecording) {
      setIsRecording(false);
      setPrompt('What makes the Light Phone screen unique?');
      handleSubmitQuery('What makes the Light Phone screen unique?');
    } else {
      setIsRecording(true);
      setTimeout(() => {
        setIsRecording(false);
        setPrompt('Explain quantum entanglement in two sentences.');
        handleSubmitQuery('Explain quantum entanglement in two sentences.');
      }, 2500);
    }
  };

  const isDark = theme === 'dark';

  return (
    <div className="flex flex-col items-center">
      {/* Device Frame */}
      <div className="relative w-[340px] sm:w-[380px] bg-[#1a1a1a] rounded-[40px] p-4 shadow-[0_25px_60px_-15px_rgba(0,0,0,0.7)] border-4 border-[#2d2d2d] transition-all">
        {/* Top Hardware details: Speaker slit & sensor */}
        <div className="flex items-center justify-center gap-2 mb-3">
          <div className="w-12 h-1 bg-[#333333] rounded-full" />
          <div className="w-2 h-2 rounded-full bg-[#262626]" />
        </div>

        {/* Physical hardware side buttons simulation */}
        <div className="absolute -left-[7px] top-28 w-[3px] h-10 bg-[#3a3a3a] rounded-l" />
        <div className="absolute -left-[7px] top-42 w-[3px] h-10 bg-[#3a3a3a] rounded-l" />
        <div className="absolute -right-[7px] top-32 w-[3px] h-14 bg-[#3a3a3a] rounded-r" />

        {/* The E-Ink Screen (Aspect Ratio ~ 1080x1240) */}
        <div
          className={`relative w-full h-[540px] rounded-[24px] overflow-hidden flex flex-col transition-colors duration-150 select-none ${
            isDark ? 'bg-black text-[#f0f0f0]' : 'bg-[#f4f3ef] text-[#111111]'
          } ${eInkRefresh ? 'animate-pulse' : ''}`}
          style={{
            fontFamily:
              'ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
          }}
        >
          {/* Subtle E-ink Paper Texture Overlay */}
          <div
            className={`absolute inset-0 pointer-events-none opacity-5 mix-blend-multiply ${
              isDark ? 'bg-white' : 'bg-black'
            }`}
          />

          {/* LightOS Status Bar */}
          <div
            className={`flex items-center justify-between px-5 pt-3 pb-2 text-[11px] font-mono tracking-wider border-b ${
              isDark ? 'border-[#222222] text-[#888888]' : 'border-[#e0dfdb] text-[#777777]'
            }`}
          >
            <span>LightOS 3.0</span>
            <span className="font-semibold">
              {new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </span>
            <div className="flex items-center gap-1.5">
              <span>LTE</span>
              <span className="text-[10px]">100%</span>
            </div>
          </div>

          {/* Screen Content Container */}
          <div className="flex-1 overflow-y-auto px-5 py-4 flex flex-col justify-between scrollbar-none">
            {/* SCREEN 1: ASK SCREEN */}
            {screen === 'ask' && (
              <div className="flex flex-col h-full justify-between">
                <div>
                  {/* Top Bar: Tool Title + Settings icon */}
                  <div className="flex items-center justify-between mb-4">
                    <h1 className="text-xl font-light tracking-wide">Ask</h1>
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => setScreen('history')}
                        className={`text-xs uppercase tracking-wider px-1.5 py-0.5 rounded transition ${
                          isDark ? 'hover:bg-[#222] text-[#999]' : 'hover:bg-[#e6e5e0] text-[#666]'
                        }`}
                        title="History"
                      >
                        Log
                      </button>
                      <button
                        onClick={() => setScreen('settings')}
                        className={`p-1 rounded transition ${
                          isDark ? 'hover:bg-[#222] text-[#999]' : 'hover:bg-[#e6e5e0] text-[#666]'
                        }`}
                        title="Settings"
                      >
                        <Settings className="w-4 h-4" />
                      </button>
                    </div>
                  </div>

                  {/* Clean Input Prompt */}
                  <div
                    className={`border-b pb-2 mb-3 flex items-center gap-2 ${
                      isDark ? 'border-[#333333]' : 'border-[#cccccc]'
                    }`}
                  >
                    <input
                      ref={inputRef}
                      type="text"
                      value={prompt}
                      onChange={(e) => setPrompt(e.target.value)}
                      onKeyDown={(e) => e.key === 'Enter' && handleSubmitQuery()}
                      placeholder="What would you like to know?"
                      className={`w-full bg-transparent text-sm focus:outline-none placeholder-opacity-40 ${
                        isDark
                          ? 'text-[#f0f0f0] placeholder-[#777777]'
                          : 'text-[#111111] placeholder-[#999999]'
                      }`}
                    />
                    {prompt && (
                      <button
                        onClick={() => setPrompt('')}
                        className="text-xs opacity-50 hover:opacity-100"
                      >
                        ✕
                      </button>
                    )}
                  </div>

                  {/* Minimalist Action Row */}
                  <div className="flex items-center justify-between text-xs tracking-wider mb-4">
                    <div className="flex items-center gap-4">
                      <button
                        disabled={loading || !prompt.trim()}
                        onClick={() => handleSubmitQuery()}
                        className={`font-medium py-1 px-2.5 rounded transition ${
                          isDark
                            ? 'bg-[#2a2a2a] text-[#ffffff] disabled:opacity-30'
                            : 'bg-[#dddcda] text-[#111111] disabled:opacity-30'
                        }`}
                      >
                        {loading ? 'Thinking...' : 'Query'}
                      </button>

                      <button
                        onClick={handleVoiceToggle}
                        className={`flex items-center gap-1 py-1 px-2 rounded text-[11px] transition ${
                          isRecording
                            ? 'bg-red-900 text-white animate-pulse'
                            : isDark
                            ? 'text-[#888888] hover:text-[#cccccc]'
                            : 'text-[#666666] hover:text-[#111111]'
                        }`}
                      >
                        <Mic className="w-3.5 h-3.5" />
                        <span>{isRecording ? 'Listening...' : 'Voice'}</span>
                      </button>
                    </div>

                    <div className="flex items-center gap-2">
                      {currentResponse && (
                        <button
                          onClick={handleCopy}
                          className="opacity-70 hover:opacity-100 p-1"
                          title="Copy text"
                        >
                          {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                        </button>
                      )}
                      <button
                        onClick={() => {
                          setCurrentResponse(null);
                          setErrorMessage(null);
                          setPrompt('');
                        }}
                        className="text-[11px] opacity-60 hover:opacity-100 underline decoration-dotted"
                      >
                        Clear
                      </button>
                    </div>
                  </div>

                  {/* Query Response Display */}
                  <div className="mt-1">
                    {loading && (
                      <div className="py-6 flex flex-col items-center justify-center gap-2 opacity-80">
                        <RefreshCw className="w-5 h-5 animate-spin" />
                        <span className="text-xs tracking-widest font-mono">RETRIEVING FROM GEMINI</span>
                      </div>
                    )}

                    {errorMessage && (
                      <div
                        className={`p-3 text-xs rounded border ${
                          isDark
                            ? 'bg-[#1e1010] border-[#442222] text-[#ff9999]'
                            : 'bg-[#fff0f0] border-[#ffd0d0] text-[#992222]'
                        }`}
                      >
                        <div className="font-semibold mb-1">Response Notice</div>
                        <div>{errorMessage}</div>
                      </div>
                    )}

                    {currentResponse && (
                      <div className="text-sm leading-relaxed whitespace-pre-wrap animate-fadeIn">
                        {currentResponse}
                        {isMockResponse && (
                          <div
                            className={`mt-3 pt-2 border-t text-[10px] opacity-60 ${
                              isDark ? 'border-[#222]' : 'border-[#ddd]'
                            }`}
                          >
                            * Simulator mode. Add your API key to DataStore to connect directly.
                          </div>
                        )}
                      </div>
                    )}

                    {!loading && !currentResponse && !errorMessage && (
                      <div className="space-y-4">
                        <p className="text-xs opacity-60 leading-relaxed">
                          Intentional inquiry. Direct, distraction-free knowledge without endless feeds.
                        </p>

                        {/* Preset Quick Inquiries */}
                        <div>
                          <div className="text-[10px] uppercase font-mono tracking-wider opacity-50 mb-2">
                            Quick prompts
                          </div>
                          <div className="space-y-1.5">
                            {PRESET_QUERIES.slice(0, 3).map((item, idx) => (
                              <button
                                key={idx}
                                onClick={() => {
                                  setPrompt(item);
                                  handleSubmitQuery(item);
                                }}
                                className={`w-full text-left text-xs p-2 rounded border transition text-ellipsis overflow-hidden whitespace-nowrap ${
                                  isDark
                                    ? 'border-[#222222] hover:bg-[#1a1a1a] text-[#cccccc]'
                                    : 'border-[#e0dfdb] hover:bg-[#eae9e4] text-[#444444]'
                                }`}
                              >
                                &bull; {item}
                              </button>
                            ))}
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                {/* Subtle bottom helper */}
                <div className="pt-2 text-[10px] opacity-40 text-center font-mono">
                  LIGHT PHONE III &bull; 1080x1240
                </div>
              </div>
            )}

            {/* SCREEN 2: SETTINGS & DATASTORE */}
            {screen === 'settings' && (
              <div className="flex flex-col h-full justify-between">
                <div>
                  <div className="flex items-center justify-between mb-4">
                    <button
                      onClick={() => setScreen('ask')}
                      className="flex items-center gap-1 text-xs opacity-70 hover:opacity-100"
                    >
                      <ArrowLeft className="w-3.5 h-3.5" />
                      <span>Back</span>
                    </button>
                    <h2 className="text-sm font-semibold tracking-wide">DataStore Preferences</h2>
                    <div className="w-4" />
                  </div>

                  <div className="space-y-4 text-xs">
                    {/* DataStore Gemini API Key */}
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <span className="font-medium">DataStore: API Key</span>
                        <span className="text-[10px] font-mono opacity-50">stringPreferencesKey</span>
                      </div>
                      <input
                        type="password"
                        value={dataStore.geminiApiKey}
                        onChange={(e) =>
                          setDataStore((prev) => ({ ...prev, geminiApiKey: e.target.value }))
                        }
                        placeholder="AI Studio API key (stored in DataStore)..."
                        className={`w-full p-2 text-xs rounded border font-mono ${
                          isDark
                            ? 'bg-[#141414] border-[#333] text-white placeholder-[#555]'
                            : 'bg-white border-[#ccc] text-black placeholder-[#aaa]'
                        }`}
                      />
                    </div>

                    {/* Concise Mode Toggle */}
                    <div
                      className={`p-2.5 rounded border flex items-center justify-between cursor-pointer ${
                        isDark ? 'border-[#2a2a2a] bg-[#141414]' : 'border-[#e0e0e0] bg-white'
                      }`}
                      onClick={() =>
                        setDataStore((prev) => ({ ...prev, conciseMode: !prev.conciseMode }))
                      }
                    >
                      <div>
                        <div className="font-medium">Ultra-Concise Answers</div>
                        <div className="text-[10px] opacity-60">1-2 sentences for e-ink reading</div>
                      </div>
                      <div
                        className={`w-8 h-4 rounded-full flex items-center p-0.5 transition ${
                          dataStore.conciseMode
                            ? isDark
                              ? 'bg-white'
                              : 'bg-black'
                            : isDark
                            ? 'bg-[#333]'
                            : 'bg-[#ccc]'
                        }`}
                      >
                        <div
                          className={`w-3 h-3 rounded-full transition transform ${
                            dataStore.conciseMode
                              ? isDark
                                ? 'translate-x-4 bg-black'
                                : 'translate-x-4 bg-white'
                              : 'translate-x-0 bg-gray-400'
                          }`}
                        />
                      </div>
                    </div>

                    {/* Active Model */}
                    <div
                      className={`p-2.5 rounded border ${
                        isDark ? 'border-[#2a2a2a] bg-[#141414]' : 'border-[#e0e0e0] bg-white'
                      }`}
                    >
                      <div className="text-[10px] font-mono opacity-50 uppercase">Active Model</div>
                      <div className="font-mono text-xs mt-0.5">gemini-3.8-flash</div>
                    </div>

                    {/* Save to DataStore Button */}
                    <button
                      onClick={handleSaveDataStore}
                      className={`w-full py-2 rounded font-medium text-xs tracking-wider transition ${
                        isDark ? 'bg-white text-black hover:bg-[#e0e0e0]' : 'bg-black text-white hover:bg-[#333]'
                      }`}
                    >
                      {savedNotice ? '✓ Saved to DataStore' : 'Save to DataStore'}
                    </button>

                    <button
                      onClick={onOpenDataStoreGuide}
                      className="w-full text-center text-[11px] underline opacity-70 hover:opacity-100 pt-1"
                    >
                      Read full DataStore setup instructions →
                    </button>
                  </div>
                </div>

                <div className="text-[10px] opacity-50 text-center leading-normal">
                  Preferences are sandboxed inside <code>DEFAULT_DATASTORE</code>.
                </div>
              </div>
            )}

            {/* SCREEN 3: LOCAL HISTORY LOG */}
            {screen === 'history' && (
              <div className="flex flex-col h-full justify-between">
                <div>
                  <div className="flex items-center justify-between mb-4">
                    <button
                      onClick={() => setScreen('ask')}
                      className="flex items-center gap-1 text-xs opacity-70 hover:opacity-100"
                    >
                      <ArrowLeft className="w-3.5 h-3.5" />
                      <span>Back</span>
                    </button>
                    <h2 className="text-sm font-semibold tracking-wide">Recent Inquiries</h2>
                    {history.length > 0 ? (
                      <button
                        onClick={() => setHistory([])}
                        className="text-[11px] opacity-60 hover:opacity-100"
                        title="Clear history"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    ) : (
                      <div className="w-4" />
                    )}
                  </div>

                  {history.length === 0 ? (
                    <div className="text-xs opacity-50 text-center py-12">
                      No recent inquiries stored.
                    </div>
                  ) : (
                    <div className="space-y-3 text-xs max-h-[380px] overflow-y-auto pr-1">
                      {history.map((item) => (
                        <div
                          key={item.id}
                          className={`p-2.5 rounded border ${
                            isDark ? 'border-[#222] bg-[#141414]' : 'border-[#e0dfdb] bg-white'
                          }`}
                        >
                          <div className="flex items-center justify-between text-[10px] opacity-50 mb-1 font-mono">
                            <span>Q</span>
                            <span>{item.timestamp}</span>
                          </div>
                          <div className="font-medium mb-1.5">{item.prompt}</div>
                          <div className="text-[11px] opacity-80 leading-relaxed whitespace-pre-wrap line-clamp-3">
                            {item.response}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                <div className="text-[10px] opacity-40 text-center font-mono">
                  Stored in filesDir/history.json
                </div>
              </div>
            )}
          </div>

          {/* LightOS Bottom Navigation Bar */}
          <div
            className={`flex items-center justify-between px-6 py-2.5 border-t text-xs ${
              isDark ? 'border-[#222222] bg-black text-[#888888]' : 'border-[#e0dfdb] bg-[#f4f3ef] text-[#666666]'
            }`}
          >
            <button
              onClick={() => {
                if (screen !== 'ask') setScreen('ask');
                else triggerRefresh();
              }}
              className="flex items-center gap-1.5 opacity-70 hover:opacity-100 transition"
              title="LightOS Back Button"
            >
              <ArrowLeft className="w-4 h-4" />
              <span className="text-[11px]">Back</span>
            </button>

            <button
              onClick={triggerRefresh}
              className="opacity-70 hover:opacity-100 transition text-[11px] flex items-center gap-1"
              title="Refresh E-Ink screen"
            >
              <RefreshCw className="w-3 h-3" />
              <span>Refresh</span>
            </button>
          </div>
        </div>

        {/* Bottom Hardware Bezel */}
        <div className="flex items-center justify-center mt-3 text-[#444] text-[10px] tracking-widest font-mono">
          LIGHT PHONE
        </div>
      </div>

      {/* Simulator Controls underneath */}
      <div className="mt-4 flex flex-wrap items-center justify-center gap-2 text-xs">
        <button
          onClick={triggerRefresh}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-full border border-neutral-700 bg-neutral-800 text-neutral-300 hover:text-white hover:border-neutral-500 transition shadow-sm"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>Simulate E-Ink Flash</span>
        </button>

        <button
          onClick={() => setScreen(screen === 'settings' ? 'ask' : 'settings')}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-full border border-neutral-700 bg-neutral-800 text-neutral-300 hover:text-white hover:border-neutral-500 transition shadow-sm"
        >
          <Database className="w-3.5 h-3.5" />
          <span>{screen === 'settings' ? 'View Ask Screen' : 'View DataStore Settings'}</span>
        </button>
      </div>
    </div>
  );
};
