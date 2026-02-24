import type { TreeNode, ProjectInfo, ClassCode, SearchResponse, ReferencesResponse } from '../types';

const BASE = '/api/v1';

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`HTTP ${res.status}: ${body}`);
  }
  return res.json() as Promise<T>;
}

export async function getProjectInfo(): Promise<ProjectInfo> {
  return fetchJson<ProjectInfo>(`${BASE}/project/info`);
}

export async function uploadFile(file: File): Promise<{ success: boolean; fileName: string; classCount: number }> {
  const form = new FormData();
  form.append('file', file);
  return fetchJson(`${BASE}/project/load`, { method: 'POST', body: form });
}

export async function getTree(): Promise<TreeNode[]> {
  return fetchJson<TreeNode[]>(`${BASE}/tree`);
}

export async function getTreeChildren(nodeId: string): Promise<TreeNode[]> {
  return fetchJson<TreeNode[]>(`${BASE}/tree/children?id=${encodeURIComponent(nodeId)}`);
}

export async function getClassCode(fullName: string): Promise<ClassCode> {
  return fetchJson<ClassCode>(`${BASE}/class/${encodeURIComponent(fullName)}/code`);
}

export async function getResources(): Promise<TreeNode[]> {
  return fetchJson<TreeNode[]>(`${BASE}/resources`);
}

export async function getResourceChildren(folderPath: string): Promise<TreeNode[]> {
  return fetchJson<TreeNode[]>(`${BASE}/resources/children?path=${encodeURIComponent(folderPath)}`);
}

export async function getResourceContent(path: string): Promise<{ name: string; type: string; content: string }> {
  // Don't encode slashes - the backend uses wildcard path matching
  const encoded = path.split('/').map(encodeURIComponent).join('/');
  return fetchJson(`${BASE}/resource/${encoded}`);
}

export async function search(query: string, type: string = 'code', limit: number = 100): Promise<SearchResponse> {
  const params = new URLSearchParams({ query, type, limit: String(limit) });
  return fetchJson<SearchResponse>(`${BASE}/search?${params}`);
}

export async function resolveSymbol(
  word: string,
  contextClass?: string,
  line?: number,
  column?: number,
): Promise<{ className: string; symbol?: string; symbolType: string }> {
  const params = new URLSearchParams({ word });
  if (contextClass) params.set('contextClass', contextClass);
  if (line !== undefined) params.set('line', String(line));
  if (column !== undefined) params.set('column', String(column));
  return fetchJson(`${BASE}/resolve-symbol?${params}`);
}

export async function gotoDefinition(
  word: string,
  contextClass?: string,
  line?: number,
  column?: number,
): Promise<{ className: string; name: string; line: number }> {
  const params = new URLSearchParams({ word });
  if (contextClass) params.set('contextClass', contextClass);
  if (line !== undefined) params.set('line', String(line));
  if (column !== undefined) params.set('column', String(column));
  return fetchJson(`${BASE}/goto-definition?${params}`);
}

export async function findReferences(
  className: string,
  symbol?: string,
  symbolType?: string,
): Promise<ReferencesResponse> {
  const params = new URLSearchParams({ className });
  if (symbol) params.set('symbol', symbol);
  if (symbolType) params.set('symbolType', symbolType);
  return fetchJson<ReferencesResponse>(`${BASE}/references?${params}`);
}
