import React, { useState, useCallback, useEffect, useRef } from 'react';
import { findReferences } from '../api/client';
import type { ReferenceEntry, ReferencesResponse } from '../types';

interface Props {
  initialClassName?: string;
  initialSymbol?: string;
  initialSymbolType?: string;
  style?: React.CSSProperties;
  onNavigate: (className: string, line: number) => void;
  onClose: () => void;
}

export function ReferencesPanel({
  initialClassName,
  initialSymbol,
  initialSymbolType,
  style,
  onNavigate,
  onClose,
}: Props) {
  const [className, setClassName] = useState(initialClassName || '');
  const [symbol, setSymbol] = useState(initialSymbol || '');
  const [symbolType, setSymbolType] = useState(initialSymbolType || '');
  const [result, setResult] = useState<ReferencesResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const didAutoSearch = useRef(false);

  const doSearch = useCallback(async (cls: string, sym: string, symType: string) => {
    if (!cls.trim()) return;
    setLoading(true);
    setError('');
    setResult(null);
    try {
      const data = await findReferences(
        cls.trim(),
        sym.trim() || undefined,
        symType || undefined,
      );
      setResult(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to find references');
    } finally {
      setLoading(false);
    }
  }, []);

  // Auto-search when opened with initial values
  useEffect(() => {
    if (initialClassName && !didAutoSearch.current) {
      didAutoSearch.current = true;
      doSearch(initialClassName, initialSymbol || '', initialSymbolType || '');
    }
  }, [initialClassName, initialSymbol, initialSymbolType, doSearch]);

  const handleSearch = useCallback(() => {
    doSearch(className, symbol, symbolType);
  }, [className, symbol, symbolType, doSearch]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSearch();
    if (e.key === 'Escape') onClose();
  }, [handleSearch, onClose]);

  // Group references by declaring class
  const grouped = new Map<string, ReferenceEntry[]>();
  if (result) {
    for (const ref of result.references) {
      const list = grouped.get(ref.className) || [];
      list.push(ref);
      grouped.set(ref.className, list);
    }
  }

  return (
    <div className="refs-panel" style={style}>
      <div className="refs-header">
        <span className="refs-title">References</span>
        <div className="refs-inputs">
          <input
            className="refs-input"
            placeholder="Class name (e.g. jadx.api.JadxArgs)"
            value={className}
            onChange={e => setClassName(e.target.value)}
            onKeyDown={handleKeyDown}
            autoFocus={!initialClassName}
          />
          <input
            className="refs-input refs-input-symbol"
            placeholder="Symbol (optional)"
            value={symbol}
            onChange={e => setSymbol(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <select
            className="refs-select"
            value={symbolType}
            onChange={e => setSymbolType(e.target.value)}
          >
            <option value="">Auto</option>
            <option value="class">Class</option>
            <option value="method">Method</option>
            <option value="field">Field</option>
          </select>
          <button className="toolbar-btn" onClick={handleSearch} disabled={loading || !className.trim()}>
            {loading ? '...' : 'Find'}
          </button>
        </div>
        <button className="refs-close" onClick={onClose} title="Close (Esc)">&times;</button>
      </div>
      <div className="refs-body">
        {error && <div className="refs-error">{error}</div>}
        {result && result.count === 0 && (
          <div className="refs-empty">No references found for {result.symbol}</div>
        )}
        {result && result.count > 0 && (
          <>
            <div className="refs-summary">
              {result.count} reference{result.count !== 1 ? 's' : ''} to{' '}
              <span className="refs-symbol">{result.symbol}</span>
              <span className="refs-symbol-type"> ({result.symbolType})</span>
            </div>
            {[...grouped.entries()].map(([cls, refs]) => (
              <div key={cls} className="refs-group">
                <div className="refs-group-header">{cls} ({refs.length})</div>
                {refs.map((ref, i) => (
                  <div
                    key={i}
                    className="refs-item"
                    onClick={() => onNavigate(ref.className, ref.line)}
                  >
                    <span className="refs-item-icon">{ref.type === 'method' ? 'M' : ref.type === 'field' ? 'F' : 'C'}</span>
                    <span className="refs-item-name">{ref.name}</span>
                    <span className="refs-item-line">:{ref.line}</span>
                    {ref.lineText && (
                      <span className="refs-item-text">{ref.lineText}</span>
                    )}
                  </div>
                ))}
              </div>
            ))}
          </>
        )}
      </div>
    </div>
  );
}
