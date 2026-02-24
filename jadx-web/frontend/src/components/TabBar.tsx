import React from 'react';
import type { TabItem } from '../types';

interface Props {
  tabs: TabItem[];
  activeTabId: string | null;
  onSelect: (tabId: string) => void;
  onClose: (tabId: string) => void;
}

export function TabBar({ tabs, activeTabId, onSelect, onClose }: Props) {
  return (
    <div className="tab-bar">
      {tabs.map(tab => (
        <div
          key={tab.id}
          className={`tab-item ${tab.id === activeTabId ? 'active' : ''}`}
          onClick={() => onSelect(tab.id)}
          title={tab.fullName}
        >
          <span>{tab.name}</span>
          <button
            className="tab-close"
            onClick={e => { e.stopPropagation(); onClose(tab.id); }}
            title="Close"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
