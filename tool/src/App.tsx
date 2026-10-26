/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { VoiceSearchExperience } from './components/VoiceSearchExperience';
import { DisplayTheme } from './types';

export default function App() {
  const [theme, setTheme] = useState<DisplayTheme>('dark');

  return (
    <div className="min-h-screen bg-[#080808] text-neutral-200 flex flex-col justify-between selection:bg-neutral-800 selection:text-white px-4 py-8 sm:py-12">
      <main className="w-full flex-1 flex flex-col justify-center items-center">
        <VoiceSearchExperience theme={theme} setTheme={setTheme} />
      </main>

      <footer className="text-center text-[11px] text-neutral-600 font-mono mt-8">
        Light Phone III &bull; Voice AI Search &bull; Powered by Google Gemini
      </footer>
    </div>
  );
}
