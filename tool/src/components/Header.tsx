import React from 'react';
import { Smartphone, Code, Key, Moon, Sun, ExternalLink, Sparkles } from 'lucide-react';
import { DisplayTheme } from '../types';

interface HeaderProps {
  activeTab: 'simulator' | 'code' | 'datastore';
  setActiveTab: (tab: 'simulator' | 'code' | 'datastore') => void;
  theme: DisplayTheme;
  setTheme: (theme: DisplayTheme) => void;
}

export const Header: React.FC<HeaderProps> = ({
  activeTab,
  setActiveTab,
  theme,
  setTheme,
}) => {
  return (
    <header className="border-b border-[#222222] bg-[#0c0c0c] sticky top-0 z-30">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-3.5 flex flex-wrap items-center justify-between gap-4">
        {/* Logo / Brand */}
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-lg bg-neutral-900 border border-neutral-700 flex items-center justify-center font-mono text-xs font-bold text-white">
            LP
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="font-semibold text-white tracking-tight text-sm sm:text-base">
                Light Phone III
              </span>
              <span className="text-xs px-2 py-0.5 rounded-full bg-neutral-800 text-neutral-300 border border-neutral-700 font-mono">
                Gemini Tool
              </span>
            </div>
            <p className="text-[11px] text-neutral-400">
              Quiet, intentional knowledge companion
            </p>
          </div>
        </div>

        {/* View Switcher Tabs */}
        <div className="flex items-center bg-[#161616] border border-[#2a2a2a] p-1 rounded-xl text-xs">
          <button
            onClick={() => setActiveTab('simulator')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg transition font-medium ${
              activeTab === 'simulator'
                ? 'bg-neutral-800 text-white shadow-sm'
                : 'text-neutral-400 hover:text-neutral-200'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5" />
            <span>Device Simulator</span>
          </button>

          <button
            onClick={() => setActiveTab('code')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg transition font-medium ${
              activeTab === 'code'
                ? 'bg-neutral-800 text-white shadow-sm'
                : 'text-neutral-400 hover:text-neutral-200'
            }`}
          >
            <Code className="w-3.5 h-3.5" />
            <span>Kotlin SDK Code</span>
          </button>

          <button
            onClick={() => setActiveTab('datastore')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg transition font-medium ${
              activeTab === 'datastore'
                ? 'bg-neutral-800 text-white shadow-sm'
                : 'text-neutral-400 hover:text-neutral-200'
            }`}
          >
            <Key className="w-3.5 h-3.5" />
            <span>DataStore Guide</span>
          </button>
        </div>

        {/* Display Controls & GitHub Link */}
        <div className="flex items-center gap-2">
          {/* Theme Toggle for Device Simulator */}
          <button
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
            className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-lg bg-[#181818] border border-[#2a2a2a] text-neutral-300 hover:text-white transition"
            title={`Switch to ${theme === 'dark' ? 'Light E-Ink' : 'Dark E-Ink'}`}
          >
            {theme === 'dark' ? <Sun className="w-3.5 h-3.5" /> : <Moon className="w-3.5 h-3.5" />}
            <span className="hidden sm:inline">
              {theme === 'dark' ? 'Light E-Ink' : 'Dark E-Ink'}
            </span>
          </button>

          {/* GitHub Repo Link */}
          <a
            href="https://github.com/lightphone/light-sdk/tree/main/sdk/client"
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded-lg bg-[#181818] border border-[#2a2a2a] text-neutral-300 hover:text-white transition"
          >
            <span className="hidden sm:inline">Light SDK</span>
            <ExternalLink className="w-3 h-3" />
          </a>
        </div>
      </div>
    </header>
  );
};
