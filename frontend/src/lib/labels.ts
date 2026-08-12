// 后端枚举的中文标签 + 语义色类。集中管理，避免散落。
import type { Tone } from '../components/ui';

export const GRADE_LABEL: Record<string, string> = {
  EASY: '精熟',
  GOOD: '达标',
  HARD: '薄弱',
  MISSING: '未掌握',
};

export const VERDICT_LABEL: Record<string, string> = {
  HIT: '命中',
  PARTIAL: '部分',
  MISS: '未中',
};

export const PROBE_LABEL: Record<string, string> = {
  RECALL: '回想',
  CLOZE: '完形',
  REVERSE: '逆向',
  TRAP: '陷阱',
  SCENARIO: '情境',
  CONTRAST: '对比',
  INTEGRATION: '综合',
};

export function gradeClass(grade: string | null | undefined): Tone {
  switch (grade) {
    case 'EASY':
    case 'GOOD':
      return 'good';
    case 'HARD':
    case 'MISSING':
      return 'bad';
    default:
      return 'soft';
  }
}

export function verdictClass(v: string): string {
  switch (v) {
    case 'HIT':
      return 'is-good';
    case 'PARTIAL':
      return 'is-warn';
    case 'MISS':
      return 'is-bad';
    default:
      return 'is-soft';
  }
}
