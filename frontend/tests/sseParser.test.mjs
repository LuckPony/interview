import assert from 'node:assert/strict';
import test from 'node:test';
import { createSseParser } from '../src/lib/sseParser.ts';
import { withAttachments } from '../src/lib/chatAttachments.ts';

test('SSE 跨块、CRLF 与心跳不会损坏代码缩进/换行', () => {
  const frames = [];
  const parser = createSseParser(frame => frames.push(frame));
  parser.push(': keepalive\r\n\r\nevent: sta');
  parser.push('tus\r\ndata: {"text":"分析中"}\r\n\r\ndata: {"text":"    return 1;\\n"}\n\n');
  parser.push('event: done\ndata: {}');
  parser.finish();
  assert.deepEqual(frames, [
    { event: 'status', data: '{"text":"分析中"}' },
    { event: 'message', data: '{"text":"    return 1;\\n"}' },
    { event: 'done', data: '{}' },
  ]);
});

test('SSE 多行 data 合并，错误事件与结束事件不作为正文', () => {
  const frames = [];
  const parser = createSseParser(frame => frames.push(frame));
  parser.push('data:   first\ndata: second\n\nevent: error\ndata: {"message":"timeout"}\n\n');
  parser.finish();
  assert.deepEqual(frames, [{ event: 'message', data: '  first\nsecond' }, { event: 'error', data: '{"message":"timeout"}' }]);
});

test('附件不截断代码，保留缩进，并同时进入提问/后续历史/存卡上下文', () => {
  const code = 'func f() {\n\tif ok {\n\t\treturn\n\t}\n}\n';
  const text = withAttachments('这段代码有问题吗？', [{ name: 'main.go', text: code, characters: code.length }]);
  assert.ok(text.startsWith('这段代码有问题吗？'));
  assert.ok(text.includes(code));
  assert.ok(text.includes('main.go'));
  assert.equal(withAttachments('普通问题'), '普通问题');
});
