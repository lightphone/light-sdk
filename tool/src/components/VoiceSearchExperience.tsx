import React, { useState, useEffect, useRef } from 'react';
import {
  Mic,
  MicOff,
  Volume2,
  VolumeX,
  Search,
  ExternalLink,
  RotateCcw,
  Sparkles,
  ArrowRight,
  Info,
  Check,
  Globe,
  Radio,
  Mail,
  Copy,
  MoreVertical,
  Send,
  Package,
} from 'lucide-react';
import { VoiceSearchItem, SearchSource, DisplayTheme } from '../types';

interface VoiceSearchExperienceProps {
  theme: DisplayTheme;
  setTheme: (t: DisplayTheme) => void;
}

const SAMPLE_QUERIES = [
  'What is the weather like in New York today?',
  'Who won the World Series last year?',
  'What is the meaning of the word sonder?',
  'How far is Mars from Earth right now?',
  'What time does the sunrise in Tokyo tomorrow?',
];

export const VoiceSearchExperience: React.FC<VoiceSearchExperienceProps> = ({
  theme,
  setTheme,
}) => {
  const [isListening, setIsListening] = useState(false);
  const [interimTranscript, setInterimTranscript] = useState('');
  const [finalQuery, setFinalQuery] = useState('');
  const [isSearching, setIsSearching] = useState(false);
  const [currentResult, setCurrentResult] = useState<VoiceSearchItem | null>(null);
  const [history, setHistory] = useState<VoiceSearchItem[]>([]);
  const [errorNotice, setErrorNotice] = useState<string | null>(null);
  const [isSpeakingAnswer, setIsSpeakingAnswer] = useState(false);
  const [autoSpeak, setAutoSpeak] = useState(true);
  const [speechSupported, setSpeechSupported] = useState(true);
  const [manualText, setManualText] = useState('');
  const [showManualInput, setShowManualInput] = useState(false);
  const [showHowItWorks, setShowHowItWorks] = useState(false);
  const [showPackagingGuide, setShowPackagingGuide] = useState(false);

  // Press and hold (long-press) state for message options
  const [isHolding, setIsHolding] = useState(false);
  const [holdProgress, setHoldProgress] = useState(0);
  const [showContextMenu, setShowContextMenu] = useState(false);
  const [emailModalOpen, setEmailModalOpen] = useState(false);
  const [userEmail, setUserEmail] = useState('dcj@vaporveil.com');
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const holdTimerRef = useRef<any>(null);
  const holdIntervalRef = useRef<any>(null);

  const recognitionRef = useRef<any>(null);

  // Initialize Web Speech API
  useEffect(() => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setSpeechSupported(false);
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = 'en-US';

    recognition.onstart = () => {
      setIsListening(true);
      setErrorNotice(null);
      setInterimTranscript('');
    };

    recognition.onresult = (event: any) => {
      let interim = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript;
        if (event.results[i].isFinal) {
          setFinalQuery(transcript);
          handleExecuteSearch(transcript);
        } else {
          interim += transcript;
        }
      }
      setInterimTranscript(interim);
    };

    recognition.onerror = (event: any) => {
      console.warn('Speech recognition notice:', event.error);
      setIsListening(false);
      if (event.error === 'not-allowed') {
        setErrorNotice('Microphone access was denied. You can tap any sample query or type below.');
      } else if (event.error !== 'no-speech') {
        setErrorNotice(`Speech error: ${event.error}`);
      }
    };

    recognition.onend = () => {
      setIsListening(false);
    };

    recognitionRef.current = recognition;

    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.abort();
      }
      if ('speechSynthesis' in window) {
        window.speechSynthesis.cancel();
      }
    };
  }, []);

  const startVoiceSearch = () => {
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
      setIsSpeakingAnswer(false);
    }
    setErrorNotice(null);
    setInterimTranscript('');
    setFinalQuery('');

    if (recognitionRef.current) {
      try {
        recognitionRef.current.start();
      } catch (e) {
        // Recognition might already be running
        recognitionRef.current.stop();
      }
    } else {
      // Fallback simulation for unsupported browsers
      simulateVoiceInput('What is the weather forecast for today?');
    }
  };

  const stopVoiceSearch = () => {
    if (recognitionRef.current) {
      recognitionRef.current.stop();
    }
    setIsListening(false);
  };

  const simulateVoiceInput = (sample: string) => {
    setIsListening(true);
    setInterimTranscript('Listening...');
    setTimeout(() => {
      setInterimTranscript(sample);
      setTimeout(() => {
        setIsListening(false);
        setFinalQuery(sample);
        handleExecuteSearch(sample);
      }, 1000);
    }, 1200);
  };

  // Perform grounded search with Gemini + Google Search
  const handleExecuteSearch = async (queryText: string) => {
    const query = queryText.trim();
    if (!query || isSearching) return;

    setIsSearching(true);
    setErrorNotice(null);
    setCurrentResult(null);

    try {
      const res = await fetch('/api/gemini', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: query }),
      });

      const data = await res.json();

      const item: VoiceSearchItem = {
        id: Date.now().toString(),
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        query,
        answer: data.text || 'No response returned.',
        sources: data.sources || [],
        isMock: !!data.isMock,
      };

      setCurrentResult(item);
      setHistory((prev) => [item, ...prev.slice(0, 9)]);

      // Read aloud automatically if autoSpeak is enabled
      if (autoSpeak) {
        speakText(data.text);
      }
    } catch (err: any) {
      setErrorNotice(err.message || 'Error conducting voice search');
    } finally {
      setIsSearching(false);
    }
  };

  // Text to speech playback
  const speakText = (text: string) => {
    if (!('speechSynthesis' in window) || !text) return;
    window.speechSynthesis.cancel();

    // Clean text of technical brackets for speech
    const cleanSpeech = text
      .replace(/\[.*?\]/g, '')
      .replace(/http\S+/g, '')
      .trim();

    if (!cleanSpeech) return;

    const utterance = new SpeechSynthesisUtterance(cleanSpeech);
    utterance.rate = 0.95;
    utterance.pitch = 1.0;

    utterance.onstart = () => setIsSpeakingAnswer(true);
    utterance.onend = () => setIsSpeakingAnswer(false);
    utterance.onerror = () => setIsSpeakingAnswer(false);

    window.speechSynthesis.speak(utterance);
  };

  const stopSpeaking = () => {
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    setIsSpeakingAnswer(false);
  };

  // Press and hold logic (500ms threshold) matching LightOS SMS press-and-hold
  const handleStartHold = () => {
    setIsHolding(true);
    setHoldProgress(0);
    const startTime = Date.now();
    const duration = 500;

    holdIntervalRef.current = setInterval(() => {
      const elapsed = Date.now() - startTime;
      const pct = Math.min(100, (elapsed / duration) * 100);
      setHoldProgress(pct);
    }, 20);

    holdTimerRef.current = setTimeout(() => {
      clearInterval(holdIntervalRef.current);
      setIsHolding(false);
      setHoldProgress(0);
      setShowContextMenu(true);
      if ('vibrate' in navigator) {
        try {
          navigator.vibrate(50);
        } catch (_) {}
      }
    }, duration);
  };

  const handleCancelHold = () => {
    clearTimeout(holdTimerRef.current);
    clearInterval(holdIntervalRef.current);
    setIsHolding(false);
    setHoldProgress(0);
  };

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3000);
  };

  const handleSendToEmail = () => {
    if (!currentResult) return;
    setShowContextMenu(false);

    const subject = encodeURIComponent(`Light Phone: ${currentResult.query}`);
    const body = encodeURIComponent(
      `Question: ${currentResult.query}\n\nAnswer:\n${currentResult.answer}\n\n--\nForwarded from Light Phone III Voice Search`
    );
    const mailtoUrl = `mailto:${userEmail}?subject=${subject}&body=${body}`;

    window.location.href = mailtoUrl;
    showToast(`Forwarded to ${userEmail}`);
  };

  const handleCopyText = () => {
    if (!currentResult) return;
    navigator.clipboard.writeText(currentResult.answer);
    setShowContextMenu(false);
    showToast('Text copied to clipboard');
  };

  const isDark = theme === 'dark';

  return (
    <div className="max-w-2xl mx-auto flex flex-col items-center">
      {/* Light Phone Header Bar */}
      <div className="w-full flex items-center justify-between py-3 mb-6 border-b border-[#222]">
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs uppercase tracking-widest text-neutral-400">
            Light Phone III
          </span>
          <span className="text-[10px] px-2 py-0.5 rounded-full bg-neutral-900 border border-neutral-800 text-neutral-300 font-mono">
            Voice AI Search
          </span>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={() => setAutoSpeak(!autoSpeak)}
            className={`flex items-center gap-1.5 text-xs font-mono px-2 py-1 rounded-md border transition ${
              autoSpeak
                ? 'border-neutral-700 bg-neutral-900 text-white'
                : 'border-neutral-800 text-neutral-500 hover:text-neutral-300'
            }`}
            title="Toggle whether Gemini speaks back or displays text only"
          >
            {autoSpeak ? <Volume2 className="w-3.5 h-3.5 text-white" /> : <VolumeX className="w-3.5 h-3.5" />}
            <span className="hidden sm:inline">{autoSpeak ? 'Speaks & Text' : 'Text Only'}</span>
          </button>

          <button
            onClick={() => setTheme(isDark ? 'light' : 'dark')}
            className="text-xs font-mono text-neutral-400 hover:text-white transition"
          >
            {isDark ? 'Light' : 'Dark'}
          </button>
          <button
            onClick={() => setShowHowItWorks(!showHowItWorks)}
            className="text-xs text-neutral-400 hover:text-white flex items-center gap-1 transition"
          >
            <Info className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">How it works</span>
          </button>
          <button
            onClick={() => setShowPackagingGuide(true)}
            className="text-xs text-neutral-400 hover:text-white flex items-center gap-1 transition"
            title="Packaging instructions for Light Tool Library"
          >
            <Package className="w-3.5 h-3.5 text-amber-400" />
            <span className="hidden sm:inline">Tool Library</span>
          </button>
        </div>
      </div>

      {/* Main E-Ink Device Chassis */}
      <div
        className={`w-full rounded-3xl p-6 sm:p-8 transition-colors duration-200 border-2 select-none shadow-2xl ${
          isDark
            ? 'bg-black text-[#ededed] border-[#262626]'
            : 'bg-[#f7f6f2] text-[#141414] border-[#d8d6cf]'
        }`}
        style={{
          fontFamily:
            'ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        }}
      >
        {/* Device Status Bar */}
        <div className="flex items-center justify-between text-[11px] font-mono tracking-wider opacity-60 pb-5 border-b border-dashed border-current/20">
          <span>VOICE SEARCH</span>
          <span>{new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
          <span>ONLINE</span>
        </div>

        {/* Content Area */}
        <div className="py-8 min-h-[360px] flex flex-col justify-between items-center text-center">
          {/* STATE 1: IDLE / WAITING TO SPEAK */}
          {!isListening && !isSearching && !currentResult && (
            <div className="flex flex-col items-center justify-center flex-1 my-auto">
              {/* Tactile Voice Action Ring */}
              <button
                onClick={startVoiceSearch}
                className={`relative group w-28 h-28 rounded-full flex flex-col items-center justify-center transition-all duration-300 active:scale-95 border-2 ${
                  isDark
                    ? 'border-white bg-[#111] hover:bg-[#1a1a1a] text-white shadow-[0_0_30px_rgba(255,255,255,0.08)]'
                    : 'border-black bg-white hover:bg-[#eae8e1] text-black shadow-[0_0_30px_rgba(0,0,0,0.06)]'
                }`}
                title="Tap to speak your question"
              >
                <Mic className="w-9 h-9 stroke-[1.6] mb-1 group-hover:scale-110 transition-transform" />
                <span className="text-[10px] uppercase font-mono tracking-widest">Speak</span>
              </button>

              <p className="mt-6 text-sm opacity-70 max-w-xs leading-relaxed font-light">
                Tap the button and ask anything.
                <br />
                <span className="text-xs opacity-60">
                  Real-time web search powered by Google &amp; Gemini.
                </span>
              </p>

              {/* Sample Queries */}
              <div className="mt-8 w-full max-w-sm">
                <div className="text-[10px] uppercase font-mono tracking-wider opacity-40 mb-2">
                  Sample voice inquiries
                </div>
                <div className="flex flex-wrap justify-center gap-1.5">
                  {SAMPLE_QUERIES.map((q, idx) => (
                    <button
                      key={idx}
                      onClick={() => {
                        setFinalQuery(q);
                        handleExecuteSearch(q);
                      }}
                      className={`text-xs px-3 py-1.5 rounded-full border transition text-left ${
                        isDark
                          ? 'border-[#2a2a2a] bg-[#111] hover:bg-[#222] text-[#ccc]'
                          : 'border-[#ddd] bg-white hover:bg-[#e8e6df] text-[#444]'
                      }`}
                    >
                      &ldquo;{q}&rdquo;
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* STATE 2: LISTENING TO VOICE */}
          {isListening && (
            <div className="flex flex-col items-center justify-center flex-1 my-auto animate-fadeIn">
              {/* Animated Listening Waves */}
              <div className="relative w-32 h-32 flex items-center justify-center">
                <div
                  className={`absolute inset-0 rounded-full animate-ping opacity-25 border-2 ${
                    isDark ? 'border-white' : 'border-black'
                  }`}
                />
                <button
                  onClick={stopVoiceSearch}
                  className={`relative z-10 w-24 h-24 rounded-full flex flex-col items-center justify-center border-2 transition ${
                    isDark
                      ? 'border-white bg-white text-black'
                      : 'border-black bg-black text-white'
                  }`}
                >
                  <Radio className="w-8 h-8 animate-pulse mb-1" />
                  <span className="text-[10px] font-mono uppercase tracking-widest">Done</span>
                </button>
              </div>

              <div className="mt-6 text-xs font-mono uppercase tracking-widest opacity-60">
                Listening...
              </div>

              <div className="mt-3 text-base font-light italic max-w-md px-4 min-h-[48px]">
                {interimTranscript || 'Speak your question clearly...'}
              </div>
            </div>
          )}

          {/* STATE 3: SEARCHING GOOGLE & GEMINI */}
          {isSearching && (
            <div className="flex flex-col items-center justify-center flex-1 my-auto animate-fadeIn">
              <div
                className={`w-16 h-16 rounded-full border-2 border-t-transparent animate-spin mb-6 ${
                  isDark ? 'border-white' : 'border-black'
                }`}
              />
              <div className="text-xs font-mono uppercase tracking-widest opacity-60 mb-2">
                Searching Google Grounding...
              </div>
              <div className="text-sm font-medium opacity-90 max-w-sm px-4">
                &ldquo;{finalQuery}&rdquo;
              </div>
            </div>
          )}

          {/* STATE 4: ANSWER RESULT */}
          {currentResult && !isSearching && (
            <div className="w-full text-left flex flex-col justify-between flex-1 animate-fadeIn">
              {/* Query bubble */}
              <div className="mb-4 pb-3 border-b border-current/15 flex items-center justify-between">
                <div>
                  <div className="text-[10px] uppercase font-mono opacity-50 tracking-wider">
                    Query
                  </div>
                  <div className="text-sm font-medium mt-0.5">&ldquo;{currentResult.query}&rdquo;</div>
                </div>

                <div className="flex items-center gap-2">
                  {/* Read Aloud Button */}
                  <button
                    onClick={() =>
                      isSpeakingAnswer ? stopSpeaking() : speakText(currentResult.answer)
                    }
                    className={`p-2 rounded-full border transition flex items-center gap-1.5 text-xs ${
                      isSpeakingAnswer
                        ? isDark
                          ? 'bg-white text-black border-white'
                          : 'bg-black text-white border-black'
                        : isDark
                        ? 'border-[#333] hover:bg-[#222]'
                        : 'border-[#ccc] hover:bg-[#eae8e1]'
                    }`}
                    title={isSpeakingAnswer ? 'Stop listening' : 'Read aloud'}
                  >
                    {isSpeakingAnswer ? (
                      <VolumeX className="w-4 h-4" />
                    ) : (
                      <Volume2 className="w-4 h-4" />
                    )}
                    <span className="text-[11px] font-mono">
                      {isSpeakingAnswer ? 'Mute' : 'Read'}
                    </span>
                  </button>

                  {/* More options / Send to Email trigger button */}
                  <button
                    onClick={() => setShowContextMenu(true)}
                    className={`p-2 rounded-full border transition text-xs opacity-70 hover:opacity-100 ${
                      isDark ? 'border-[#333] hover:bg-[#222]' : 'border-[#ccc] hover:bg-[#eae8e1]'
                    }`}
                    title="Press & hold text, or tap for options (Send to Email)"
                  >
                    <MoreVertical className="w-4 h-4" />
                  </button>
                </div>
              </div>

              {/* Crisp Plain-Text Answer with Press & Hold Interaction */}
              <div className="relative my-2">
                <div
                  onMouseDown={handleStartHold}
                  onMouseUp={handleCancelHold}
                  onMouseLeave={handleCancelHold}
                  onTouchStart={handleStartHold}
                  onTouchEnd={handleCancelHold}
                  onTouchCancel={handleCancelHold}
                  className={`p-4 rounded-2xl border-2 transition-all duration-150 cursor-pointer select-none text-sm sm:text-base leading-relaxed whitespace-pre-wrap font-light tracking-wide ${
                    isHolding
                      ? isDark
                        ? 'border-white bg-[#1a1a1a] scale-[0.99]'
                        : 'border-black bg-[#eae8e1] scale-[0.99]'
                      : isDark
                      ? 'border-[#222] bg-[#0d0d0d] hover:border-[#333]'
                      : 'border-[#e4e2db] bg-white hover:border-[#ccc]'
                  }`}
                  title="Press and hold to send to email (like Light Phone SMS)"
                >
                  {currentResult.answer}

                  {/* Press and hold progress bar */}
                  {isHolding && (
                    <div className="mt-3 w-full h-1 rounded-full bg-current/20 overflow-hidden">
                      <div
                        className="h-full bg-current transition-all"
                        style={{ width: `${holdProgress}%` }}
                      />
                    </div>
                  )}
                </div>

                {/* Subtle helper note */}
                <div className="mt-1.5 flex items-center justify-between text-[11px] opacity-50 px-1 font-mono">
                  <span>Press &amp; hold text to forward to email</span>
                  <button
                    onClick={() => setShowContextMenu(true)}
                    className="underline hover:opacity-100"
                  >
                    Options
                  </button>
                </div>
              </div>

              {/* Grounded Sources if available */}
              {currentResult.sources && currentResult.sources.length > 0 && (
                <div className="mt-3 pt-3 border-t border-current/15 text-xs">
                  <div className="text-[10px] uppercase font-mono opacity-50 tracking-wider mb-1.5 flex items-center gap-1">
                    <Globe className="w-3 h-3" />
                    <span>Search Grounding Sources</span>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {currentResult.sources.map((src, i) => (
                      <a
                        key={i}
                        href={src.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className={`text-[11px] font-mono px-2 py-0.5 rounded border flex items-center gap-1 opacity-80 hover:opacity-100 transition truncate max-w-[200px] ${
                          isDark
                            ? 'border-[#333] hover:bg-[#1a1a1a]'
                            : 'border-[#ccc] hover:bg-[#eae8e1]'
                        }`}
                        title={src.title}
                      >
                        <span className="truncate">{src.title}</span>
                        <ExternalLink className="w-2.5 h-2.5 flex-shrink-0" />
                      </a>
                    ))}
                  </div>
                </div>
              )}

              {/* Action row */}
              <div className="mt-6 pt-3 flex items-center justify-between text-xs">
                <button
                  onClick={startVoiceSearch}
                  className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full border transition font-medium ${
                    isDark
                      ? 'border-white bg-white text-black hover:bg-[#e0e0e0]'
                      : 'border-black bg-black text-white hover:bg-[#333]'
                  }`}
                >
                  <Mic className="w-3.5 h-3.5" />
                  <span>Ask Another Question</span>
                </button>

                <button
                  onClick={() => {
                    setCurrentResult(null);
                    setFinalQuery('');
                    stopSpeaking();
                  }}
                  className="opacity-60 hover:opacity-100 underline decoration-dotted text-[11px]"
                >
                  Clear Screen
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Error Notification */}
        {errorNotice && (
          <div
            className={`mt-4 p-3 rounded-lg text-xs border ${
              isDark
                ? 'bg-[#1e1313] border-[#442222] text-[#ff9999]'
                : 'bg-[#fff2f2] border-[#ffd0d0] text-[#992222]'
            }`}
          >
            {errorNotice}
          </div>
        )}

        {/* Manual Keyboard Input Toggle */}
        <div className="mt-4 pt-4 border-t border-current/15 flex flex-col gap-2">
          {!showManualInput ? (
            <button
              onClick={() => setShowManualInput(true)}
              className="text-xs opacity-50 hover:opacity-100 underline text-center"
            >
              Or type a query using keyboard
            </button>
          ) : (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (manualText.trim()) {
                  setFinalQuery(manualText);
                  handleExecuteSearch(manualText);
                  setManualText('');
                }
              }}
              className="flex items-center gap-2"
            >
              <input
                type="text"
                value={manualText}
                onChange={(e) => setManualText(e.target.value)}
                placeholder="Type your question..."
                className={`flex-1 p-2 text-xs rounded border bg-transparent focus:outline-none ${
                  isDark ? 'border-[#333]' : 'border-[#ccc]'
                }`}
              />
              <button
                type="submit"
                disabled={!manualText.trim() || isSearching}
                className={`p-2 rounded border text-xs font-mono ${
                  isDark
                    ? 'border-white bg-white text-black'
                    : 'border-black bg-black text-white'
                }`}
              >
                Search
              </button>
              <button
                type="button"
                onClick={() => setShowManualInput(false)}
                className="text-xs opacity-50 hover:opacity-100 px-1"
              >
                Cancel
              </button>
            </form>
          )}
        </div>
      </div>

      {/* "How It Works on Light Phone III" Slideout / Explainer */}
      {showHowItWorks && (
        <div className="w-full mt-6 p-5 sm:p-6 rounded-2xl bg-[#141414] border border-[#2a2a2a] text-xs text-neutral-300 animate-fadeIn">
          <div className="flex items-center justify-between mb-4 pb-2 border-b border-[#262626]">
            <div>
              <h3 className="font-semibold text-white tracking-wide text-sm">
                How BYOK (Bring Your Own Key) Operates on Light Phone III
              </h3>
              <p className="text-[11px] text-neutral-500 font-mono mt-0.5">
                Zero server costs &bull; Total user privacy &bull; Sandboxed on-device storage
              </p>
            </div>
            <button
              onClick={() => setShowHowItWorks(false)}
              className="text-neutral-500 hover:text-white p-1"
            >
              ✕
            </button>
          </div>

          <div className="space-y-3.5 text-neutral-400 leading-relaxed">
            <div className="p-3 rounded-xl bg-[#181818] border border-[#262626]">
              <span className="font-semibold text-white">Why BYOK is ideal for Light Phone:</span>
              <p className="mt-1 text-[11px]">
                You don't need to pay for or host a backend server. Each Light Phone user brings their own free Gemini API key from Google AI Studio. The tool runs autonomously on their device forever.
              </p>
            </div>

            <p>
              1. <strong>Getting the Key:</strong> The user grabs a free API key from{' '}
              <a
                href="https://aistudio.google.com/app/apikey"
                target="_blank"
                rel="noopener noreferrer"
                className="text-white underline"
              >
                aistudio.google.com
              </a>
              . Google provides a generous free tier for Gemini models.
            </p>

            <p>
              2. <strong>On-Device DataStore:</strong> On their phone, the user opens <em>Settings</em> in the tool and pastes their key. The Light SDK stores it locally in{' '}
              <code className="text-white font-mono bg-[#222] px-1 py-0.5 rounded">
                DEFAULT_DATASTORE
              </code>{' '}
              via Android's Jetpack DataStore. The key is sandboxed and never leaves the phone.
            </p>

            <p>
              3. <strong>Direct REST Calls with Google Search:</strong> When the user holds the mic and asks a question, the tool calls the Gemini API directly from the phone over HTTPS, with Google Search grounding enabled for real-time facts.
            </p>

            <p>
              4. <strong>Forwarding to Email:</strong> Pressing and holding the answer opens the LightOS action menu to forward the response directly to their personal inbox, identical to Light Phone SMS forwarding.
            </p>
          </div>
        </div>
      )}

      {/* Recent Inquiries List */}
      {history.length > 0 && (
        <div className="w-full mt-8">
          <div className="text-[11px] font-mono uppercase tracking-wider text-neutral-500 mb-3">
            Recent Voice Inquiries
          </div>
          <div className="space-y-2">
            {history.slice(0, 4).map((item) => (
              <div
                key={item.id}
                onClick={() => {
                  setCurrentResult(item);
                  speakText(item.answer);
                }}
                className="p-3 rounded-xl bg-[#121212] hover:bg-[#181818] border border-[#222] cursor-pointer transition flex items-center justify-between gap-3 text-xs"
              >
                <div className="truncate">
                  <span className="text-white font-medium">&ldquo;{item.query}&rdquo;</span>
                  <div className="text-neutral-500 text-[11px] truncate mt-0.5">
                    {item.answer}
                  </div>
                </div>
                <span className="text-[10px] font-mono text-neutral-600 flex-shrink-0">
                  {item.timestamp}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
      {/* LightOS Toast Notification */}
      {toastMessage && (
        <div className="fixed bottom-8 z-50 px-4 py-2.5 rounded-full bg-white text-black text-xs font-mono tracking-wide shadow-2xl flex items-center gap-2 animate-bounce">
          <Check className="w-4 h-4 text-emerald-600 stroke-[2.5]" />
          <span>{toastMessage}</span>
        </div>
      )}

      {/* LightOS Context Menu Modal (Triggered by Press & Hold) */}
      {showContextMenu && currentResult && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-xs flex items-end sm:items-center justify-center p-4 animate-fadeIn">
          <div
            className={`w-full max-w-sm rounded-3xl p-6 border-2 shadow-2xl ${
              isDark ? 'bg-[#111] text-[#ededed] border-[#333]' : 'bg-[#f7f6f2] text-[#141414] border-[#d8d6cf]'
            }`}
          >
            <div className="flex items-center justify-between pb-3 border-b border-current/20 mb-4">
              <span className="font-mono text-xs uppercase tracking-wider opacity-60">Message Options</span>
              <button
                onClick={() => setShowContextMenu(false)}
                className="text-xs opacity-60 hover:opacity-100 p-1"
              >
                ✕
              </button>
            </div>

            <div className="text-xs opacity-60 mb-4 line-clamp-2 italic">
              &ldquo;{currentResult.answer}&rdquo;
            </div>

            <div className="space-y-2">
              <button
                onClick={handleSendToEmail}
                className={`w-full p-3.5 rounded-xl border-2 flex items-center justify-between text-xs font-medium transition ${
                  isDark
                    ? 'border-white bg-white text-black hover:bg-[#e0e0e0]'
                    : 'border-black bg-black text-white hover:bg-[#333]'
                }`}
              >
                <div className="flex items-center gap-2.5">
                  <Mail className="w-4 h-4" />
                  <span>Send to Email</span>
                </div>
                <span className="text-[10px] font-mono opacity-70 truncate max-w-[140px]">{userEmail}</span>
              </button>

              <button
                onClick={() => {
                  setShowContextMenu(false);
                  setEmailModalOpen(true);
                }}
                className={`w-full p-3 rounded-xl border flex items-center gap-2.5 text-xs transition ${
                  isDark ? 'border-[#333] hover:bg-[#1c1c1c]' : 'border-[#ccc] hover:bg-[#eae8e1]'
                }`}
              >
                <Send className="w-3.5 h-3.5 opacity-60" />
                <span>Change Destination Email</span>
              </button>

              <button
                onClick={handleCopyText}
                className={`w-full p-3 rounded-xl border flex items-center gap-2.5 text-xs transition ${
                  isDark ? 'border-[#333] hover:bg-[#1c1c1c]' : 'border-[#ccc] hover:bg-[#eae8e1]'
                }`}
              >
                <Copy className="w-3.5 h-3.5 opacity-60" />
                <span>Copy Text</span>
              </button>

              <button
                onClick={() => {
                  setShowContextMenu(false);
                  speakText(currentResult.answer);
                }}
                className={`w-full p-3 rounded-xl border flex items-center gap-2.5 text-xs transition ${
                  isDark ? 'border-[#333] hover:bg-[#1c1c1c]' : 'border-[#ccc] hover:bg-[#eae8e1]'
                }`}
              >
                <Volume2 className="w-3.5 h-3.5 opacity-60" />
                <span>Read Aloud</span>
              </button>

              <button
                onClick={() => setShowContextMenu(false)}
                className="w-full py-2.5 text-center text-xs opacity-60 hover:opacity-100 transition mt-1"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Change Destination Email Modal */}
      {emailModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-xs flex items-center justify-center p-4 animate-fadeIn">
          <div
            className={`w-full max-w-sm rounded-3xl p-6 border-2 shadow-2xl ${
              isDark ? 'bg-[#111] text-[#ededed] border-[#333]' : 'bg-[#f7f6f2] text-[#141414] border-[#d8d6cf]'
            }`}
          >
            <div className="flex items-center justify-between pb-3 border-b border-current/20 mb-4">
              <span className="font-mono text-xs uppercase tracking-wider opacity-60">Email Forwarding</span>
              <button onClick={() => setEmailModalOpen(false)} className="text-xs opacity-60 hover:opacity-100 p-1">
                ✕
              </button>
            </div>

            <p className="text-xs opacity-70 mb-3 leading-relaxed">
              Just like Light Phone SMS forwarding, long-pressing an AI response forwards the note to your personal inbox.
            </p>

            <input
              type="email"
              value={userEmail}
              onChange={(e) => setUserEmail(e.target.value)}
              placeholder="name@example.com"
              className={`w-full p-2.5 text-xs rounded-xl border font-mono mb-4 focus:outline-none ${
                isDark ? 'bg-black border-[#333] text-white' : 'bg-white border-[#ccc] text-black'
              }`}
            />

            <div className="flex items-center gap-2">
              <button
                onClick={() => {
                  setEmailModalOpen(false);
                  showToast(`Saved email: ${userEmail}`);
                }}
                className={`flex-1 py-2.5 rounded-xl border text-xs font-medium font-mono ${
                  isDark ? 'border-white bg-white text-black' : 'border-black bg-black text-white'
                }`}
              >
                Save Address
              </button>
              <button
                onClick={() => setEmailModalOpen(false)}
                className="px-4 py-2.5 text-xs opacity-60 hover:opacity-100"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Light Tool Library Packaging Guide Modal */}
      {showPackagingGuide && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-xs flex items-center justify-center p-4 animate-fadeIn">
          <div
            className={`w-full max-w-xl max-h-[90vh] overflow-y-auto rounded-3xl p-6 sm:p-8 border-2 shadow-2xl ${
              isDark ? 'bg-[#111] text-[#ededed] border-[#333]' : 'bg-[#f7f6f2] text-[#141414] border-[#d8d6cf]'
            }`}
          >
            <div className="flex items-center justify-between pb-3 border-b border-current/20 mb-5">
              <div>
                <h3 className="font-semibold text-base tracking-wide flex items-center gap-2">
                  <Package className="w-5 h-5 text-amber-400" />
                  <span>Packaging for the Light Tool Library</span>
                </h3>
                <p className="text-[11px] font-mono opacity-50 mt-0.5">
                  Official submission workflow for Light Phone III
                </p>
              </div>
              <button
                onClick={() => setShowPackagingGuide(false)}
                className="text-xs opacity-60 hover:opacity-100 p-1"
              >
                ✕
              </button>
            </div>

            <div className="space-y-5 text-xs text-neutral-300 leading-relaxed">
              {/* Step 1 */}
              <div>
                <div className="font-semibold text-white mb-1 flex items-center gap-2">
                  <span className="w-5 h-5 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-[10px] font-mono">1</span>
                  Create an Open-Source GitHub Repository
                </div>
                <p className="text-neutral-400 ml-7">
                  Light requires all submitted tools to be public and open-source. Fork or clone{' '}
                  <code className="text-white font-mono bg-[#222] px-1 py-0.5 rounded">
                    lightphone/light-sdk
                  </code>{' '}
                  and place your tool code in the <code className="text-white font-mono">tool/</code> directory.
                </p>
              </div>

              {/* Step 2 */}
              <div>
                <div className="font-semibold text-white mb-1 flex items-center gap-2">
                  <span className="w-5 h-5 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-[10px] font-mono">2</span>
                  Add Tool Manifest (<code className="font-mono text-amber-300">lighttool.toml</code>)
                </div>
                <div className="ml-7 p-3 rounded-xl bg-[#0b0b0b] border border-[#262626] font-mono text-[11px] text-neutral-300">
                  [tool]<br />
                  name = "Gemini Search"<br />
                  package = "com.yourname.gemini"<br />
                  description = "Voice-first AI search powered by Gemini."<br />
                  version = "1.0.0"<br />
                  capabilities = ["network"]
                </div>
              </div>

              {/* Step 3 */}
              <div>
                <div className="font-semibold text-white mb-1 flex items-center gap-2">
                  <span className="w-5 h-5 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-[10px] font-mono">3</span>
                  Register Tool on Light Developer Dashboard
                </div>
                <ol className="list-decimal list-inside ml-7 space-y-1.5 text-neutral-400">
                  <li>Log in to <strong className="text-white">dashboard.thelightphone.com</strong>.</li>
                  <li>Go to <strong>Settings</strong> &rarr; <strong>Account</strong> &rarr; <strong>Developer Account</strong>.</li>
                  <li>Click <strong>Manage Custom Tools</strong> &rarr; <strong>Submit New Tool</strong>.</li>
                  <li>Provide your public GitHub repository URL and upload at least one screenshot (1080&times;1240).</li>
                </ol>
              </div>

              {/* Step 4 */}
              <div>
                <div className="font-semibold text-white mb-1 flex items-center gap-2">
                  <span className="w-5 h-5 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-[10px] font-mono">4</span>
                  Submit Build for Signing
                </div>
                <p className="text-neutral-400 ml-7">
                  Click <strong>Submit Build</strong> and specify the git tag/ref (e.g. <code className="text-white font-mono">main</code>). Light's automated build system compiles and digitally signs the APK so it can run securely on LightOS.
                </p>
              </div>

              {/* Step 5 */}
              <div>
                <div className="font-semibold text-white mb-1 flex items-center gap-2">
                  <span className="w-5 h-5 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center text-[10px] font-mono">5</span>
                  Approval &amp; Tool Library Allow-List
                </div>
                <p className="text-neutral-400 ml-7">
                  Once approved by the Light team, the tool is added to the official LightOS allow-list. Users can then find and install your tool with a single tap in the Light Phone Tool Library!
                </p>
              </div>
            </div>

            <div className="mt-6 pt-4 border-t border-current/20 flex justify-end">
              <button
                onClick={() => setShowPackagingGuide(false)}
                className={`px-5 py-2 rounded-xl text-xs font-mono font-medium ${
                  isDark ? 'bg-white text-black' : 'bg-black text-white'
                }`}
              >
                Close Guide
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
