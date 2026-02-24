import React, { useState, useCallback, useRef, useEffect } from 'react';
import { search as apiSearch } from '../api/client';
import type { SearchResult } from '../types';

interface Props {
  onClose: () => void;
  onNavigate: (className: string, line: number) => void;
}

export function SearchDialog({ onClose, onNavigate }: Props) {
  const [query, setQuery] = useState('');
  const [searchType, setSearchType] = useState('code');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [searched, setSearched] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    inputRef.current?.focus();
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [onClose]);

  const doSearch = useCallback(async (q: string, type: string) => {
    if (!q.trim()) {
      setResults([]);
      setSearched(false);
      return;
    }
    setSearching(true);
    try {
      const resp = await apiSearch(q, type);
      setResults(resp.results);
      setSearched(true);
    } catch (e) {
      console.error('Search failed', e);
    } finally {
      setSearching(false);
    }
  }, []);

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setQuery(val);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => doSearch(val, searchType), 300);
  }, [searchType, doSearch]);

  const handleTypeChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const type = e.target.value;
    setSearchType(type);
    if (query.trim()) {
      doSearch(query, type);
    }
  }, [query, doSearch]);

  return (
    <div className="search-overlay" onClick={onClose}>
      <div className="search-dialog" onClick={e => e.stopPropagation()}>
        <div className="search-header">
          <input
            ref={inputRef}
            className="search-input"
            placeholder="Search..."
            value={query}
            onChange={handleInputChange}
          />
          <select className="search-select" value={searchType} onChange={handleTypeChange}>
            <option value="code">Code</option>
            <option value="class">Class</option>
            <option value="method">Method</option>
            <option value="field">Field</option>
          </select>
        </div>
        <div className="search-results">
          {searching && <div className="search-status">Searching...</div>}
          {!searching && searched && results.length === 0 && (
            <div className="search-status">No results found</div>
          )}
          {results.map((r, i) => (
            <div
              key={i}
              className="search-result-item"
              onClick={() => onNavigate(r.className, r.line)}
            >
              <div className="search-result-class">
                {r.className}
                {r.line > 0 && <span className="search-result-line"> :{r.line}</span>}
              </div>
              <div className="search-result-match">{r.match}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
