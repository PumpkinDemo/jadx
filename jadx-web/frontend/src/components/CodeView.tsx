import React, { useRef, useEffect } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import type { editor } from 'monaco-editor';

interface Props {
  code: string;
  language?: string;
  line?: number;
  onFindReferences?: (word: string, line: number, column: number) => void;
  onGotoDefinition?: (word: string, line: number, column: number) => void;
}

export function CodeView({ code, language = 'java', line, onFindReferences, onGotoDefinition }: Props) {
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const refsCallbackRef = useRef(onFindReferences);
  refsCallbackRef.current = onFindReferences;
  const gotoDefRef = useRef(onGotoDefinition);
  gotoDefRef.current = onGotoDefinition;
  const decorationsRef = useRef<string[]>([]);
  const highlightRef = useRef<string[]>([]);
  const highlightTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const monacoRef = useRef<typeof import('monaco-editor') | null>(null);

  const flashLine = (ed: editor.IStandaloneCodeEditor, lineNum: number) => {
    const m = monacoRef.current;
    if (!m || lineNum <= 0) return;
    if (highlightTimerRef.current) clearTimeout(highlightTimerRef.current);
    const range = new m.Range(lineNum, 1, lineNum, 1);
    highlightRef.current = ed.deltaDecorations(highlightRef.current, [{
      range,
      options: { isWholeLine: true, className: 'jump-line-highlight' },
    }]);
    highlightTimerRef.current = setTimeout(() => {
      highlightRef.current = ed.deltaDecorations(highlightRef.current, []);
    }, 1800);
  };

  const handleMount: OnMount = (editorInstance, monaco) => {
    editorRef.current = editorInstance;
    monacoRef.current = monaco;

    if (line && line > 0) {
      editorInstance.revealLineInCenter(line);
      editorInstance.setPosition({ lineNumber: line, column: 1 });
      setTimeout(() => flashLine(editorInstance, line), 50);
    }

    editorInstance.addAction({
      id: 'jadx-find-references',
      label: 'Find References',
      keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.F7],
      contextMenuGroupId: 'navigation',
      contextMenuOrder: 1,
      run: (ed) => {
        const position = ed.getPosition();
        if (!position) return;
        const model = ed.getModel();
        if (!model) return;
        const wordInfo = model.getWordAtPosition(position);
        if (wordInfo && refsCallbackRef.current) {
          refsCallbackRef.current(wordInfo.word, position.lineNumber, wordInfo.startColumn);
        }
      },
    });

    editorInstance.addAction({
      id: 'jadx-goto-definition',
      label: 'Go to Definition',
      keybindings: [monaco.KeyCode.F12],
      contextMenuGroupId: 'navigation',
      contextMenuOrder: 0.5,
      run: (ed) => {
        const position = ed.getPosition();
        if (!position) return;
        const model = ed.getModel();
        if (!model) return;
        const wordInfo = model.getWordAtPosition(position);
        if (wordInfo && gotoDefRef.current) {
          gotoDefRef.current(wordInfo.word, position.lineNumber, wordInfo.startColumn);
        }
      },
    });

    // Ctrl/Cmd + Click to go to definition
    editorInstance.onMouseDown((e) => {
      if (!(e.event.ctrlKey || e.event.metaKey)) return;
      if (e.target.type !== monaco.editor.MouseTargetType.CONTENT_TEXT) return;
      const position = e.target.position;
      if (!position) return;
      const model = editorInstance.getModel();
      if (!model) return;
      const wordInfo = model.getWordAtPosition(position);
      if (wordInfo && gotoDefRef.current) {
        e.event.preventDefault();
        e.event.stopPropagation();
        gotoDefRef.current(wordInfo.word, position.lineNumber, wordInfo.startColumn);
      }
    });

    // Underline word on Ctrl/Cmd + Hover
    editorInstance.onMouseMove((e) => {
      if (!(e.event.ctrlKey || e.event.metaKey)) {
        decorationsRef.current = editorInstance.deltaDecorations(decorationsRef.current, []);
        return;
      }
      if (e.target.type !== monaco.editor.MouseTargetType.CONTENT_TEXT) {
        decorationsRef.current = editorInstance.deltaDecorations(decorationsRef.current, []);
        return;
      }
      const position = e.target.position;
      if (!position) return;
      const model = editorInstance.getModel();
      if (!model) return;
      const wordInfo = model.getWordAtPosition(position);
      if (wordInfo) {
        const range = new monaco.Range(
          position.lineNumber, wordInfo.startColumn,
          position.lineNumber, wordInfo.endColumn,
        );
        decorationsRef.current = editorInstance.deltaDecorations(decorationsRef.current, [{
          range,
          options: { inlineClassName: 'goto-def-link' },
        }]);
      } else {
        decorationsRef.current = editorInstance.deltaDecorations(decorationsRef.current, []);
      }
    });

    // Clear underline when Ctrl/Cmd is released
    editorInstance.onKeyUp((e) => {
      if (e.keyCode === monaco.KeyCode.Ctrl || e.keyCode === monaco.KeyCode.Meta) {
        decorationsRef.current = editorInstance.deltaDecorations(decorationsRef.current, []);
      }
    });
  };

  useEffect(() => {
    if (editorRef.current && line && line > 0) {
      editorRef.current.revealLineInCenter(line);
      editorRef.current.setPosition({ lineNumber: line, column: 1 });
      flashLine(editorRef.current, line);
    }
  }, [line]);

  return (
    <Editor
      height="100%"
      language={language}
      value={code}
      theme="vs-dark"
      onMount={handleMount}
      options={{
        readOnly: true,
        minimap: { enabled: true },
        fontSize: 13,
        fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', 'Courier New', monospace",
        scrollBeyondLastLine: false,
        renderLineHighlight: 'all',
        automaticLayout: true,
        wordWrap: 'off',
        smoothScrolling: true,
        contextmenu: true,
      }}
    />
  );
}
