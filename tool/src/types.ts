export interface SearchSource {
  title: string;
  url: string;
}

export interface VoiceSearchItem {
  id: string;
  timestamp: string;
  query: string;
  answer: string;
  sources?: SearchSource[];
  isMock?: boolean;
}

export type DisplayTheme = 'dark' | 'light';

export interface QueryHistoryItem {
  id: string;
  timestamp: string;
  prompt: string;
  response: string;
  isMock?: boolean;
}

export type SimulatorScreen = 'ask' | 'voice' | 'history' | 'settings';

export interface DataStorePreferences {
  geminiApiKey: string;
  conciseMode: boolean;
  maxTokens: number;
  selectedModel: string;
}

export interface CodeFile {
  name: string;
  path: string;
  language: string;
  description: string;
  content: string;
}
