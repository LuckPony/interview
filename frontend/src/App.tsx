import type { ReactNode } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { AppShell } from './components/AppShell';
import { Login } from './pages/Login';
import { Dashboard } from './pages/Dashboard';
import { Drill } from './pages/Drill';
import { Interview } from './pages/Interview';
import { InterviewHistory } from './pages/InterviewHistory';
import { InterviewDetail } from './pages/InterviewDetail';
import { Profile } from './pages/Profile';
import { Notes } from './pages/Notes';
import { ReviewPage } from './pages/Review';
import { HistoryPage } from './pages/History';
import { PlansPage } from './pages/PlansPage';
import { IntakeChat } from './pages/IntakeChat';
import { Settings } from './pages/Settings';
import {CapturePage} from "./pages/CapturePage.tsx";

function RequireAuth({ children }: { children: ReactNode }) {
  const { userId } = useAuth();
  return userId ? <>{children}</> : <Navigate to="/login" replace />;
}

export function App() {
  const { userId } = useAuth();
  return (
    <Routes>
      <Route
        path="/login"
        element={userId ? <Navigate to="/" replace /> : <Login />}
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <AppShell>
              <Dashboard />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/intake"
        element={
          <RequireAuth>
            <AppShell>
              <IntakeChat />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/drill"
        element={
          <RequireAuth>
            <AppShell>
              <Drill />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/rehearsal"
        element={
          <RequireAuth>
            <AppShell>
              <Interview />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/rehearsal/history"
        element={
          <RequireAuth>
            <AppShell>
              <InterviewHistory />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/rehearsal/history/:id"
        element={
          <RequireAuth>
            <AppShell>
              <InterviewDetail />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/profile"
        element={
          <RequireAuth>
            <AppShell>
              <Profile />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/notes"
        element={
          <RequireAuth>
            <AppShell>
              <Notes />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/notes/review/:runId"
        element={
          <RequireAuth>
            <AppShell>
              <ReviewPage />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/plan"
        element={
          <RequireAuth>
            <AppShell>
              <PlansPage />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/history"
        element={
          <RequireAuth>
            <AppShell>
              <HistoryPage />
            </AppShell>
          </RequireAuth>
        }
      />
      <Route
        path="/settings"
        element={
          <RequireAuth>
            <AppShell>
              <Settings />
            </AppShell>
          </RequireAuth>
        }
      />
        <Route
            path="/capture"
            element={
            <RequireAuth>
                <AppShell>
                    <CapturePage />
                </AppShell>
            </RequireAuth>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
