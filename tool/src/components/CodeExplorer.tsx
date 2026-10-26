import React, { useState } from 'react';
import { Copy, Check, FileCode, Download, ExternalLink, Terminal } from 'lucide-react';
import { LIGHT_SDK_FILES } from '../data/lightPhoneCodeSnippets';

export const CodeExplorer: React.FC = () => {
  const [activeFileIndex, setActiveFileIndex] = useState(0);
  const [copied, setCopied] = useState(false);

  const activeFile = LIGHT_SDK_FILES[activeFileIndex];

  const handleCopy = () => {
    navigator.clipboard.writeText(activeFile.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = () => {
    const element = document.createElement('a');
    const file = new Blob([activeFile.content], { type: 'text/plain' });
    element.href = URL.createObjectURL(file);
    element.download = activeFile.name;
    document.body.appendChild(element);
    element.click();
    document.body.removeChild(element);
  };

  return (
    <div className="bg-[#121212] border border-[#262626] rounded-2xl overflow-hidden shadow-2xl flex flex-col h-full">
      {/* Code Header Bar */}
      <div className="border-b border-[#262626] px-4 py-3 bg-[#181818] flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <FileCode className="w-4 h-4 text-neutral-400" />
          <span className="text-sm font-medium text-neutral-200">Light SDK Tool Files</span>
          <span className="text-xs font-mono bg-[#222] text-neutral-400 px-2 py-0.5 rounded border border-[#333]">
            Kotlin / Jetpack Compose
          </span>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleCopy}
            className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg bg-[#222222] hover:bg-[#2d2d2d] text-neutral-300 hover:text-white transition border border-[#333333]"
            title="Copy code"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copied ? 'Copied' : 'Copy File'}</span>
          </button>

          <button
            onClick={handleDownload}
            className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg bg-[#222222] hover:bg-[#2d2d2d] text-neutral-300 hover:text-white transition border border-[#333333]"
            title="Download file"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Download</span>
          </button>
        </div>
      </div>

      {/* File Tabs */}
      <div className="flex overflow-x-auto border-b border-[#222222] bg-[#141414] px-2 py-1 scrollbar-none">
        {LIGHT_SDK_FILES.map((f, i) => (
          <button
            key={f.name}
            onClick={() => setActiveFileIndex(i)}
            className={`px-3 py-1.5 text-xs font-mono rounded-md whitespace-nowrap transition flex items-center gap-2 ${
              i === activeFileIndex
                ? 'bg-[#222222] text-white border border-[#3a3a3a]'
                : 'text-neutral-400 hover:text-neutral-200 hover:bg-[#1a1a1a]'
            }`}
          >
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                f.name.endsWith('.kt')
                  ? 'bg-amber-400'
                  : f.name.endsWith('.xml')
                  ? 'bg-sky-400'
                  : 'bg-emerald-400'
              }`}
            />
            {f.name}
          </button>
        ))}
      </div>

      {/* File Description */}
      <div className="px-4 py-2 bg-[#161616] border-b border-[#222222] text-xs text-neutral-400 flex items-center justify-between">
        <span className="font-mono text-neutral-500 truncate mr-2">{activeFile.path}</span>
        <span className="text-neutral-300">{activeFile.description}</span>
      </div>

      {/* Syntax Code Body */}
      <div className="flex-1 overflow-auto p-4 bg-[#0d0d0d] font-mono text-xs text-neutral-300 leading-relaxed scrollbar-thin">
        <pre className="whitespace-pre">{activeFile.content}</pre>
      </div>

      {/* Footer Info */}
      <div className="border-t border-[#222222] px-4 py-2 bg-[#141414] text-[11px] text-neutral-500 flex items-center justify-between">
        <span>Target: Light Phone III &bull; API 34 &bull; :sdk:client</span>
        <a
          href="https://github.com/lightphone/light-sdk"
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-1 hover:text-neutral-300 transition"
        >
          <span>lightphone/light-sdk</span>
          <ExternalLink className="w-3 h-3" />
        </a>
      </div>
    </div>
  );
};
