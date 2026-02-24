export interface TreeNode {
  id: string;
  name: string;
  fullName: string;
  type: 'package' | 'class' | 'interface' | 'enum' | 'method' | 'field' | 'folder' | 'resource';
  hasChildren?: boolean;
  children?: TreeNode[];
  childrenLoaded?: boolean;
  resType?: string;
  fieldType?: string;
  returnType?: string;
}

export interface ProjectInfo {
  version: string;
  loaded: boolean;
  loading: boolean;
  fileName?: string;
  classCount?: number;
  errorsCount?: number;
}

export interface ClassCode {
  className: string;
  code: string;
}

export interface SearchResult {
  className: string;
  match: string;
  type: string;
  line: number;
  location: string;
}

export interface SearchResponse {
  query: string;
  type: string;
  count: number;
  results: SearchResult[];
}

export interface TabItem {
  id: string;
  name: string;
  fullName: string;
  code?: string;
  line?: number;
  language?: string;
}

export interface ReferenceEntry {
  className: string;
  name: string;
  line: number;
  lineText: string;
  type: string;
}

export interface ReferencesResponse {
  symbol: string;
  symbolType: string;
  count: number;
  references: ReferenceEntry[];
}
