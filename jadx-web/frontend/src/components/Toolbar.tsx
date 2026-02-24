import React, { useRef, useState } from 'react';
import { uploadFile } from '../api/client';
import type { ProjectInfo } from '../types';

interface Props {
  projectInfo: ProjectInfo | null;
  onRefresh: () => void;
  onSearch: () => void;
  onReferences: () => void;
}

export function Toolbar({ projectInfo, onRefresh, onSearch, onReferences }: Props) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    try {
      await uploadFile(file);
      onRefresh();
    } catch (err) {
      console.error('Upload failed', err);
      alert('Upload failed: ' + (err as Error).message);
    } finally {
      setUploading(false);
    }
    // Reset input
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  return (
    <div className="toolbar">
      <span className="toolbar-title">jadx-web</span>
      {uploading && (
        <span className="toolbar-info" style={{ color: '#e8a317' }}>
          Loading file...
        </span>
      )}
      {!uploading && projectInfo?.loaded && (
        <span className="toolbar-info">
          {projectInfo.fileName} — {projectInfo.classCount} classes
          {projectInfo.errorsCount ? ` (${projectInfo.errorsCount} errors)` : ''}
        </span>
      )}
      {!uploading && projectInfo && !projectInfo.loaded && !projectInfo.loading && (
        <span className="toolbar-info">No project loaded</span>
      )}
      <span className="toolbar-spacer" />
      <button className="toolbar-btn" onClick={onSearch} title="Search (Ctrl+Shift+F)">
        Search
      </button>
      <button className="toolbar-btn" onClick={onReferences} title="Find References (Alt+F7)">
        References
      </button>
      <button
        className="toolbar-btn"
        onClick={() => fileInputRef.current?.click()}
        disabled={uploading}
      >
        {uploading ? 'Loading...' : 'Upload File'}
      </button>
      <button className="toolbar-btn" onClick={onRefresh} disabled={uploading}>
        Reload
      </button>
      <input
        ref={fileInputRef}
        type="file"
        className="upload-area"
        accept=".apk,.dex,.jar,.class,.zip,.aar,.aab,.xapk,.apkm,.apks,.smali"
        onChange={handleUpload}
      />
    </div>
  );
}
