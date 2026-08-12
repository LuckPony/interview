import type { ButtonHTMLAttributes, HTMLAttributes, ReactNode } from 'react';
import './ui.css';

type Variant = 'primary' | 'ghost' | 'quiet' | 'danger';

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return <button className={`btn btn-${variant} ${className}`} {...props} />;
}

export function Card({
  children,
  className = '',
  ...rest
}: { children: ReactNode } & HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={`card ${className}`} {...rest}>
      {children}
    </div>
  );
}

export type Tone = 'good' | 'warn' | 'bad' | 'soft' | 'accent';

export function Badge({ kind = 'soft', children }: { kind?: Tone; children: ReactNode }) {
  return <span className={`badge is-${kind}`}>{children}</span>;
}

export function Tag({ children }: { children: ReactNode }) {
  return <span className="tag">{children}</span>;
}

export function Loading({ label = '研习中…' }: { label?: string }) {
  return (
    <div className="loading">
      <span className="spinner" aria-hidden />
      {label}
    </div>
  );
}
