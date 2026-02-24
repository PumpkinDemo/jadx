import React, { useState, useCallback } from 'react';
import { getTreeChildren, getResourceChildren } from '../api/client';
import type { TreeNode } from '../types';

interface Props {
  tree: TreeNode[];
  resources: TreeNode[];
  onSelect: (node: TreeNode) => void;
  onFindReferences?: (node: TreeNode) => void;
}

const TYPE_ICONS: Record<string, { label: string; cls: string }> = {
  package:   { label: 'P', cls: 'package' },
  class:     { label: 'C', cls: 'class' },
  interface: { label: 'I', cls: 'interface' },
  enum:      { label: 'E', cls: 'enum' },
  method:    { label: 'M', cls: 'method' },
  field:     { label: 'F', cls: 'field' },
  folder:    { label: 'D', cls: 'folder' },
  resource:  { label: 'R', cls: 'resource' },
};

const REFERABLE_TYPES = new Set(['class', 'interface', 'enum', 'method', 'field']);

function TreeNodeItem({
  node,
  depth,
  onSelect,
  onLoadChildren,
  onFindReferences,
}: {
  node: TreeNode;
  depth: number;
  onSelect: (node: TreeNode) => void;
  onLoadChildren: (node: TreeNode) => Promise<void>;
  onFindReferences?: (node: TreeNode) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const canExpand = node.hasChildren || (node.children && node.children.length > 0);

  const handleToggle = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!canExpand) return;

    if (!expanded && !node.childrenLoaded) {
      setLoading(true);
      try {
        await onLoadChildren(node);
      } finally {
        setLoading(false);
      }
    }
    setExpanded(prev => !prev);
  }, [canExpand, expanded, node, onLoadChildren]);

  const handleClick = useCallback(() => {
    onSelect(node);
  }, [node, onSelect]);

  const handleContextMenu = useCallback((e: React.MouseEvent) => {
    if (!onFindReferences || !REFERABLE_TYPES.has(node.type)) return;
    e.preventDefault();
    onFindReferences(node);
  }, [node, onFindReferences]);

  const icon = TYPE_ICONS[node.type] || { label: '?', cls: '' };

  return (
    <>
      <div
        className="tree-node"
        style={{ paddingLeft: depth * 16 + 8 }}
        onClick={handleClick}
        onDoubleClick={handleToggle}
        onContextMenu={handleContextMenu}
      >
        <span className="tree-toggle" onClick={handleToggle}>
          {canExpand ? (loading ? '…' : expanded ? '▼' : '▶') : ' '}
        </span>
        <span className={`tree-icon ${icon.cls}`}>{icon.label}</span>
        <span className="tree-name" title={node.fullName}>
          {node.name}
        </span>
      </div>
      {expanded && node.children && node.children.map(child => (
        <TreeNodeItem
          key={child.id}
          node={child}
          depth={depth + 1}
          onSelect={onSelect}
          onLoadChildren={onLoadChildren}
          onFindReferences={onFindReferences}
        />
      ))}
    </>
  );
}

function SectionHeader({
  label,
  expanded,
  onToggle,
  count,
}: {
  label: string;
  expanded: boolean;
  onToggle: () => void;
  count: number;
}) {
  return (
    <div className="sidebar-section" onClick={onToggle}>
      <span className="tree-toggle">{expanded ? '▼' : '▶'}</span>
      <span className="sidebar-section-label">{label}</span>
      {count > 0 && <span className="sidebar-section-count">{count}</span>}
    </div>
  );
}

export function TreeView({ tree, resources, onSelect, onFindReferences }: Props) {
  const [sourcesExpanded, setSourcesExpanded] = useState(true);
  const [resourcesExpanded, setResourcesExpanded] = useState(false);

  const handleLoadChildren = useCallback(async (node: TreeNode) => {
    if (node.childrenLoaded) return;
    try {
      let children: TreeNode[];
      if (node.type === 'folder') {
        children = await getResourceChildren(node.fullName);
      } else {
        children = await getTreeChildren(node.id);
      }
      node.children = children;
      node.childrenLoaded = true;
    } catch (e) {
      console.error('Failed to load children for', node.id, e);
    }
  }, []);

  return (
    <div>
      <SectionHeader
        label="Sources"
        expanded={sourcesExpanded}
        onToggle={() => setSourcesExpanded(p => !p)}
        count={tree.length}
      />
      {sourcesExpanded && (
        tree.length === 0 ? (
          <div className="tree-empty">No classes loaded</div>
        ) : (
          tree.map(node => (
            <TreeNodeItem
              key={node.id}
              node={node}
              depth={0}
              onSelect={onSelect}
              onLoadChildren={handleLoadChildren}
              onFindReferences={onFindReferences}
            />
          ))
        )
      )}

      <SectionHeader
        label="Resources"
        expanded={resourcesExpanded}
        onToggle={() => setResourcesExpanded(p => !p)}
        count={resources.length}
      />
      {resourcesExpanded && (
        resources.length === 0 ? (
          <div className="tree-empty">No resources loaded</div>
        ) : (
          resources.map(node => (
            <TreeNodeItem
              key={node.id}
              node={node}
              depth={0}
              onSelect={onSelect}
              onLoadChildren={handleLoadChildren}
            />
          ))
        )
      )}
    </div>
  );
}
