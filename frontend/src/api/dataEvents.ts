export const DATA_CHANGED = 'yan:data-changed';
export const SESSION_CHANGED = 'yan:session-changed';

export function notifyDataChanged(path: string): void {
  window.dispatchEvent(new CustomEvent<string>(DATA_CHANGED, { detail: path }));
}
