import { apiFetch, ApiError } from './client';

// ============ 统一解包：后端 Result<{code,message,data}> 包装 ============
interface Envelope<T> { code: number; message: string; data: T }

async function unwrap<T>(p: Promise<Envelope<T>>): Promise<T> {
    const env = await p;
    if (env && typeof env === 'object' && 'code' in env && env.code !== 200) {
        throw new ApiError(env.code, env.message || '操作失败');
    }
    return (env as Envelope<T>).data;
}

// ============ 模拟面试（interview 模块） ============

export type InterviewDifficulty = 'JUNIOR' | 'MIDDLE' | 'SENIOR';
export type InterviewStatus = 'IN_PROGRESS' | 'PENDING_EVALUATION' | 'COMPLETED' | 'TERMINATED';
export type InterviewMode = 'TEXT' | 'VOICE';

export interface InterviewAnswer {
  id: number;
  sessionId: string;
  questionIndex: number;
  questionText: string;
  answerText: string | null;
  score: number | null;
  feedback: string | null;
  isFollowUp: boolean;
  createdAt: string;
}

export interface QuestionEvalution {
  score: number;
  feedback: string;
}

export interface InterviewEvaluation {
  totalScore: number;
  questionEvaluations: QuestionEvalution[];
  strength: string[];
  improvements: string[];
}

export interface InterviewSession {
  id: string;
  skillId: string | null;
  skillName: string;
  difficulty: InterviewDifficulty;
  status: InterviewStatus;
  totalQuestions: number;
  currentQuestionIndex: number;
  totalScore: number | null;
  llmProviders: string | null;
  createdAt: string;
  answers: InterviewAnswer[];
  mode: InterviewMode;
  planIds: string | null;
  evaluation: InterviewEvaluation | null;
  durationMin: number | null;
  remainingSeconds: number;
}

export interface InterviewListItem {
  id: string;
  skillId: string | null;
  skillName: string;
  difficulty: InterviewDifficulty;
  status: InterviewStatus;
  totalQuestions: number;
  answeredCount: number;
  totalScore: number | null;
  createdAt: string;
  mode: InterviewMode;
}

export interface QaHistory {
  question: string;
  answer: string | null;
  followUp: boolean;
}

export interface CurrentQuestion {
  sessionId: string;
  totalQuestions: number;
  baseIndex: number;
  followUpIndex: number;
  totalFollowUps: number;
  finished: boolean;
  remainingSeconds: number;
  question: string | null;
  history: QaHistory[];
}

export const interviewApi = {
  skills: () => unwrap(apiFetch<Envelope<Record<string, string>>>('/interviews/skills')),

  createSession(req: {
    skillId?: string;
    difficulty?: InterviewDifficulty;
    resumeId?: number | null;
    planIds?: number[];
    mode?: InterviewMode;
  }): Promise<InterviewSession> {
    return unwrap(apiFetch<Envelope<InterviewSession>>('/interviews/sessions', {
      method: 'POST',
      body: JSON.stringify(req),
    }));
  },

  currentQuestion(sessionId: string): Promise<CurrentQuestion> {
    return unwrap(apiFetch<Envelope<CurrentQuestion>>(`/interviews/sessions/${sessionId}/current-question`));
  },

  submitAnswer(sessionId: string, questionIndex: number, answerText: string): Promise<InterviewSession> {
    return unwrap(apiFetch<Envelope<InterviewSession>>(`/interviews/sessions/${sessionId}/answers`, {
      method: 'POST',
      body: JSON.stringify({ questionIndex, answerText }),
    }));
  },

  completeAndEvaluate(sessionId: string): Promise<InterviewSession> {
    return unwrap(apiFetch<Envelope<InterviewSession>>(`/interviews/sessions/${sessionId}/complete-evaluate`, {
      method: 'POST',
    }));
  },

  list(): Promise<InterviewListItem[]> {
    return unwrap(apiFetch<Envelope<InterviewListItem[]>>('/interviews/sessions'));
  },

  detail(sessionId: string): Promise<InterviewSession> {
    return unwrap(apiFetch<Envelope<InterviewSession>>(`/interviews/session/${sessionId}`));
  },

  delete(sessionId: string): Promise<void> {
    return apiFetch<void>(`/interviews/sessions/${sessionId}`, { method: 'DELETE' });
  },
};

// ============ 简历 ============

export interface ResumeListItem {
  id: number;
  originalName: string;
  fileType: string;
  fileSize: number;
  status: string;
  overallScore: number | null;
  createdAt: string;
}

export interface ResumeDetail extends ResumeListItem {
  storageKey: string;
  errorMessage: string | null;
  resumeText: string;
  summary: string | null;
  strengths: string[];
  weaknesses: string[];
  suggestions: string[];
}

export const resumeApi = {
  list: () => unwrap(apiFetch<Envelope<ResumeListItem[]>>('/resumes')),
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return unwrap(apiFetch<Envelope<ResumeDetail>>('/resumes/upload', { method: 'POST', body: form }));
  },
  detail: (id: number) => unwrap(apiFetch<Envelope<ResumeDetail>>(`/resumes/${id}`)),
  remove: (id: number) => unwrap(apiFetch<Envelope<void>>(`/resumes/${id}`, { method: 'DELETE' })),
};
