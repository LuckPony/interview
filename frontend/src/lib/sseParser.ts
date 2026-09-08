export interface SseEvent { event: string; data: string }

/** SSE 按空行分帧；保留数据中的空格/换行，兼容 CRLF、跨网络块和末帧缺少空行。 */
export function createSseParser(onEvent: (event: SseEvent) => void) {
  let buffer = '';
  let event = 'message';
  let data: string[] = [];
  const dispatch = () => {
    if (data.length) onEvent({ event, data: data.join('\n') });
    event = 'message';
    data = [];
  };
  const line = (value: string) => {
    if (!value) { dispatch(); return; }
    if (value.startsWith(':')) return;
    const colon = value.indexOf(':');
    const field = colon < 0 ? value : value.slice(0, colon);
    const content = colon < 0 ? '' : value.slice(colon + 1).replace(/^ /, '');
    if (field === 'event') event = content;
    if (field === 'data') data.push(content);
  };
  return {
    push(chunk: string) {
      buffer += chunk;
      let index: number;
      while ((index = buffer.indexOf('\n')) >= 0) {
        line(buffer.slice(0, index).replace(/\r$/, ''));
        buffer = buffer.slice(index + 1);
      }
    },
    finish() {
      if (buffer) line(buffer.replace(/\r$/, ''));
      buffer = '';
      dispatch();
    },
  };
}
