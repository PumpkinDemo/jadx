import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Toolbar } from './components/Toolbar';
import { TreeView } from './components/TreeView';
import { TabBar } from './components/TabBar';
import { CodeView } from './components/CodeView';
import { SearchDialog } from './components/SearchDialog';
import { ReferencesPanel } from './components/ReferencesPanel';
import { Resizer } from './components/Resizer';
import { getProjectInfo, getTree, getClassCode, getResources, getResourceContent, resolveSymbol, gotoDefinition } from './api/client';
import type { TreeNode, ProjectInfo, TabItem } from './types';
import './App.css';

/** Max number of tabs that keep their code in memory */
const MAX_CACHED_TABS = 5;

function guessLanguage(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  const map: Record<string, string> = {
    xml: 'xml', json: 'json', html: 'html', htm: 'html',
    js: 'javascript', ts: 'typescript', kt: 'kotlin',
    java: 'java', properties: 'properties', yaml: 'yaml',
    yml: 'yaml', txt: 'plaintext', md: 'markdown',
  };
  return map[ext] || 'plaintext';
}

export default function App() {
  const [projectInfo, setProjectInfo] = useState<ProjectInfo | null>(null);
  const [tree, setTree] = useState<TreeNode[]>([]);
  const [resources, setResources] = useState<TreeNode[]>([]);
  const [tabs, setTabs] = useState<TabItem[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [refsOpen, setRefsOpen] = useState(false);
  const [refsInitial, setRefsInitial] = useState<{ className: string; symbol?: string; symbolType?: string } | null>(null);
  const refsKeyRef = useRef(0);
  const [loading, setLoading] = useState(false);
  const [sidebarWidth, setSidebarWidth] = useState(280);
  const [refsPanelHeight, setRefsPanelHeight] = useState(220);

  // Track recently-used order for LRU eviction
  const lruRef = useRef<string[]>([]);

  const handleSidebarResize = useCallback((delta: number) => {
    setSidebarWidth(prev => Math.max(150, Math.min(600, prev + delta)));
  }, []);

  const handleRefsPanelResize = useCallback((delta: number) => {
    setRefsPanelHeight(prev => Math.max(100, Math.min(500, prev - delta)));
  }, []);

  const refreshProject = useCallback(async () => {
    try {
      const info = await getProjectInfo();
      setProjectInfo(info);
      if (info.loaded) {
        const [treeData, resData] = await Promise.all([getTree(), getResources()]);
        setTree(treeData);
        setResources(resData);
      }
    } catch (e) {
      console.error('Failed to load project info', e);
    }
  }, []);

  useEffect(() => {
    refreshProject();
  }, [refreshProject]);

  // Global keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'F') {
        e.preventDefault();
        setSearchOpen(prev => !prev);
      }
      if (e.altKey && e.key === 'F7') {
        e.preventDefault();
        setRefsOpen(prev => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // WebSocket connection for real-time events
  useEffect(() => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/events`);
    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'project-loaded') {
          refreshProject();
        }
      } catch (e) {
        console.debug('WS parse error', e);
      }
    };
    ws.onclose = () => {
      console.debug('WebSocket closed');
    };
    return () => ws.close();
  }, [refreshProject]);

  /** Bump a tab to the front of LRU and evict code from old tabs */
  const touchLru = useCallback((tabId: string) => {
    const lru = lruRef.current;
    const idx = lru.indexOf(tabId);
    if (idx !== -1) lru.splice(idx, 1);
    lru.unshift(tabId);

    // Evict code from tabs beyond the cache limit
    if (lru.length > MAX_CACHED_TABS) {
      const toEvict = new Set(lru.slice(MAX_CACHED_TABS));
      setTabs(prev => prev.map(t =>
        toEvict.has(t.id) ? { ...t, code: undefined } : t
      ));
    }
  }, []);

  const openClass = useCallback(async (fullName: string, name: string, line?: number) => {
    // Check if tab already open
    const existing = tabs.find(t => t.fullName === fullName);
    if (existing) {
      // If code was evicted, re-fetch it
      if (!existing.code) {
        setLoading(true);
        try {
          const data = await getClassCode(fullName);
          setTabs(prev => prev.map(t =>
            t.id === existing.id ? { ...t, code: data.code, line } : t
          ));
        } catch (e) {
          console.error('Failed to reload class code', e);
        } finally {
          setLoading(false);
        }
      } else if (line !== undefined) {
        setTabs(prev => prev.map(t =>
          t.id === existing.id ? { ...t, line } : t
        ));
      }
      setActiveTabId(existing.id);
      touchLru(existing.id);
      return;
    }

    setLoading(true);
    try {
      const data = await getClassCode(fullName);
      const tab: TabItem = {
        id: `tab-${fullName}`,
        name,
        fullName,
        code: data.code,
        line,
      };
      setTabs(prev => [...prev, tab]);
      setActiveTabId(tab.id);
      touchLru(tab.id);
    } catch (e) {
      console.error('Failed to load class code', e);
    } finally {
      setLoading(false);
    }
  }, [tabs, touchLru]);

  const closeTab = useCallback((tabId: string) => {
    // Remove from LRU
    const lru = lruRef.current;
    const idx = lru.indexOf(tabId);
    if (idx !== -1) lru.splice(idx, 1);

    setTabs(prev => {
      const next = prev.filter(t => t.id !== tabId);
      if (activeTabId === tabId) {
        setActiveTabId(next.length > 0 ? next[next.length - 1].id : null);
      }
      return next;
    });
  }, [activeTabId]);

  const openResource = useCallback(async (path: string, name: string) => {
    const tabId = `tab-res-${path}`;
    const existing = tabs.find(t => t.id === tabId);
    if (existing) {
      if (!existing.code) {
        setLoading(true);
        try {
          const data = await getResourceContent(path);
          setTabs(prev => prev.map(t =>
            t.id === existing.id ? { ...t, code: data.content } : t
          ));
        } catch (e) {
          console.error('Failed to reload resource', e);
        } finally {
          setLoading(false);
        }
      }
      setActiveTabId(existing.id);
      touchLru(existing.id);
      return;
    }

    setLoading(true);
    try {
      const data = await getResourceContent(path);
      const lang = guessLanguage(name);
      const tab: TabItem = {
        id: tabId,
        name,
        fullName: path,
        code: data.content,
        language: lang,
      };
      setTabs(prev => [...prev, tab]);
      setActiveTabId(tab.id);
      touchLru(tab.id);
    } catch (e) {
      console.error('Failed to load resource', e);
    } finally {
      setLoading(false);
    }
  }, [tabs, touchLru]);

  const handleTreeSelect = useCallback((node: TreeNode) => {
    if (node.type === 'class' || node.type === 'interface' || node.type === 'enum') {
      openClass(node.fullName, node.name);
    } else if (node.type === 'method' || node.type === 'field') {
      // Open the parent class
      const parts = node.fullName.split('.');
      parts.pop();
      const className = parts.join('.');
      openClass(className, parts[parts.length - 1]);
    } else if (node.type === 'resource') {
      openResource(node.fullName, node.name);
    }
  }, [openClass, openResource]);

  const handleSearchNavigate = useCallback((className: string, line: number) => {
    const simpleName = className.split('.').pop() || className;
    openClass(className, simpleName, line);
    setSearchOpen(false);
  }, [openClass]);

  const handleRefsNavigate = useCallback((className: string, line: number) => {
    const simpleName = className.split('.').pop() || className;
    openClass(className, simpleName, line);
  }, [openClass]);

  const openRefs = useCallback((init: { className: string; symbol?: string; symbolType?: string }) => {
    refsKeyRef.current++;
    setRefsInitial(init);
    setRefsOpen(true);
  }, []);

  const handleFindReferences = useCallback((node: TreeNode) => {
    if (node.type === 'class' || node.type === 'interface' || node.type === 'enum') {
      openRefs({ className: node.fullName, symbolType: 'class' });
    } else if (node.type === 'method' || node.type === 'field') {
      const parts = node.fullName.split('.');
      const symName = parts.pop() || '';
      const clsName = parts.join('.');
      openRefs({ className: clsName, symbol: symName, symbolType: node.type });
    }
  }, [openRefs]);

  const handleCodeFindReferences = useCallback(async (word: string, line: number, column: number) => {
    if (!activeTabId) return;
    const tab = tabs.find(t => t.id === activeTabId);
    if (!tab) return;

    const contextClass = tab.id.startsWith('tab-res-') ? undefined : tab.fullName;
    try {
      const resolved = await resolveSymbol(word, contextClass, line, column);
      openRefs({
        className: resolved.className,
        symbol: resolved.symbol,
        symbolType: resolved.symbolType,
      });
    } catch (e: unknown) {
      // Check if the error indicates a local variable
      const msg = e instanceof Error ? e.message : '';
      if (msg.includes('isLocal') || msg.includes('local variable')) {
        return; // Don't open references panel for local variables
      }
      openRefs({ className: word, symbolType: 'class' });
    }
  }, [activeTabId, tabs, openRefs]);

  const handleGotoDefinition = useCallback(async (word: string, line: number, column: number) => {
    if (!activeTabId) return;
    const tab = tabs.find(t => t.id === activeTabId);
    if (!tab || tab.id.startsWith('tab-res-')) return;

    try {
      const result = await gotoDefinition(word, tab.fullName, line, column);
      openClass(result.className, result.name, result.line);
    } catch (e) {
      console.debug('Go to definition failed:', e);
    }
  }, [activeTabId, tabs, openClass]);

  const activeTab = tabs.find(t => t.id === activeTabId) || null;

  return (
    <div className="app">
      <Toolbar
        projectInfo={projectInfo}
        onRefresh={refreshProject}
        onSearch={() => setSearchOpen(true)}
        onReferences={() => setRefsOpen(prev => !prev)}
      />
      <div className="main-content">
        <div className="sidebar" style={{ width: sidebarWidth }}>
          <TreeView tree={tree} resources={resources} onSelect={handleTreeSelect} onFindReferences={handleFindReferences} />
        </div>
        <Resizer direction="horizontal" onResize={handleSidebarResize} />
        <div className="editor-area">
          {tabs.length > 0 && (
            <TabBar
              tabs={tabs}
              activeTabId={activeTabId}
              onSelect={(id) => { setActiveTabId(id); touchLru(id); }}
              onClose={closeTab}
            />
          )}
          <div className="code-container">
            {loading && <div className="loading-overlay">Loading...</div>}
            {activeTab ? (
              activeTab.code ? (
                <CodeView code={activeTab.code} language={activeTab.language || 'java'} line={activeTab.line} onFindReferences={handleCodeFindReferences} onGotoDefinition={handleGotoDefinition} />
              ) : (
                <div className="loading-overlay">Loading code...</div>
              )
            ) : (
              <div className="welcome">
                {projectInfo?.loaded ? (
                  <div className="welcome-text">
                    <h2>jadx-web</h2>
                    <p>Select a class from the tree to view decompiled code.</p>
                    <p className="shortcut-hint">Ctrl+Click / F12 go to definition &middot; Ctrl+Shift+F search &middot; Alt+F7 references</p>
                  </div>
                ) : (
                  <div className="welcome-text">
                    <h2>jadx-web</h2>
                    <p>No project loaded. Upload a file using the toolbar or start the server with an input file.</p>
                  </div>
                )}
              </div>
            )}
          </div>
          {refsOpen && (
            <>
              <Resizer direction="vertical" onResize={handleRefsPanelResize} />
              <ReferencesPanel
                key={refsKeyRef.current}
                style={{ height: refsPanelHeight }}
                initialClassName={refsInitial?.className}
                initialSymbol={refsInitial?.symbol}
                initialSymbolType={refsInitial?.symbolType}
                onNavigate={handleRefsNavigate}
                onClose={() => { setRefsOpen(false); setRefsInitial(null); }}
              />
            </>
          )}
        </div>
      </div>
      {searchOpen && (
        <SearchDialog
          onClose={() => setSearchOpen(false)}
          onNavigate={handleSearchNavigate}
        />
      )}
    </div>
  );
}
