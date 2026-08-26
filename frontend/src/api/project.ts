import { apiFetch } from './client';

export interface ProjectStatus {
  id: number;
  name: string;
  techStack: string | null;
  status: string; // PENDING / ANALYZING / READY / FAILED
  errorMsg: string | null;
  domains: DomainView[];
}

export interface DomainView {
  id: number;
  name: string;
  overview: string | null;
  refFiles: string[];
  subPoints: SubPointView[];
}

export interface SubPointView {
  id: number;
  name: string;
  description: string | null;
  refFiles: string[];
}

export const projectApi = {
  /** 上传 zip 项目文件。 */
  importZip: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return apiFetch<{ id: number; name: string; status: string }>('/project/import-zip', {
      method: 'POST',
      body: fd,
    });
  },

  /** 桌面端：直接读本地路径。 */
  importPath: (path: string) =>
    apiFetch<{ id: number; name: string; status: string }>('/project/import-path', {
      method: 'POST',
      body: JSON.stringify({ path }),
      headers: { 'Content-Type': 'application/json' },
    }),

  /** 查询项目状态与分析结果。 */
  getStatus: (id: number) => apiFetch<ProjectStatus>(`/project/${id}`),

  /** 列出我的所有导入项目。 */
  list: () => apiFetch<ProjectStatus[]>('/project'),

  /** 创建学习计划。 */
  createPlan: (id: number) =>
    apiFetch<{ ok: boolean; planId: number }>(`/project/${id}/create-plan`, {
      method: 'POST',
    }),
};