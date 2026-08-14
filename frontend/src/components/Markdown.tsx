import ReactMarkdown from 'react-markdown';
import type { Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { PrismLight as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import './Markdown.css';
// 常用语言显式注册：打包只带这些（不会把 refractor 297 种语言全部打成 chunk），
// 且流式聊天中代码块首帧即可高亮，无需异步加载后再重绘。
import go from 'react-syntax-highlighter/dist/esm/languages/prism/go';
import javascript from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import typescript from 'react-syntax-highlighter/dist/esm/languages/prism/typescript';
import jsx from 'react-syntax-highlighter/dist/esm/languages/prism/jsx';
import tsx from 'react-syntax-highlighter/dist/esm/languages/prism/tsx';
import python from 'react-syntax-highlighter/dist/esm/languages/prism/python';
import bash from 'react-syntax-highlighter/dist/esm/languages/prism/bash';
import json from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import yaml from 'react-syntax-highlighter/dist/esm/languages/prism/yaml';
import markdown from 'react-syntax-highlighter/dist/esm/languages/prism/markdown';
import markup from 'react-syntax-highlighter/dist/esm/languages/prism/markup';
import css from 'react-syntax-highlighter/dist/esm/languages/prism/css';
import scss from 'react-syntax-highlighter/dist/esm/languages/prism/scss';
import java from 'react-syntax-highlighter/dist/esm/languages/prism/java';
import rust from 'react-syntax-highlighter/dist/esm/languages/prism/rust';
import kotlin from 'react-syntax-highlighter/dist/esm/languages/prism/kotlin';
import swift from 'react-syntax-highlighter/dist/esm/languages/prism/swift';
import cpp from 'react-syntax-highlighter/dist/esm/languages/prism/cpp';
import csharp from 'react-syntax-highlighter/dist/esm/languages/prism/csharp';
import php from 'react-syntax-highlighter/dist/esm/languages/prism/php';
import ruby from 'react-syntax-highlighter/dist/esm/languages/prism/ruby';
import diff from 'react-syntax-highlighter/dist/esm/languages/prism/diff';
import docker from 'react-syntax-highlighter/dist/esm/languages/prism/docker';
import graphql from 'react-syntax-highlighter/dist/esm/languages/prism/graphql';
import toml from 'react-syntax-highlighter/dist/esm/languages/prism/toml';
import ini from 'react-syntax-highlighter/dist/esm/languages/prism/ini';
import properties from 'react-syntax-highlighter/dist/esm/languages/prism/properties';
import scala from 'react-syntax-highlighter/dist/esm/languages/prism/scala';
import dart from 'react-syntax-highlighter/dist/esm/languages/prism/dart';
import elixir from 'react-syntax-highlighter/dist/esm/languages/prism/elixir';
import haskell from 'react-syntax-highlighter/dist/esm/languages/prism/haskell';
import lua from 'react-syntax-highlighter/dist/esm/languages/prism/lua';
import perl from 'react-syntax-highlighter/dist/esm/languages/prism/perl';
import r from 'react-syntax-highlighter/dist/esm/languages/prism/r';

const REGISTERED: [string, unknown][] = [
  ['go', go], ['javascript', javascript], ['typescript', typescript],
  ['jsx', jsx], ['tsx', tsx], ['python', python], ['bash', bash],
  ['json', json], ['sql', sql], ['yaml', yaml], ['markdown', markdown],
  ['markup', markup], ['css', css], ['scss', scss], ['java', java],
  ['rust', rust], ['kotlin', kotlin], ['swift', swift], ['cpp', cpp],
  ['csharp', csharp], ['php', php], ['ruby', ruby], ['diff', diff],
  ['docker', docker], ['graphql', graphql], ['toml', toml], ['ini', ini],
  ['properties', properties], ['scala', scala], ['dart', dart],
  ['elixir', elixir], ['haskell', haskell], ['lua', lua], ['perl', perl],
  ['r', r],
];
for (const [name, lang] of REGISTERED) {
  SyntaxHighlighter.registerLanguage(name, lang as Parameters<typeof SyntaxHighlighter.registerLanguage>[1]);
}

// 安全渲染：react-markdown 默认不渲染原始 HTML（转义），配合下面不放行
// rehype-raw，可天然规避 XSS。链接强制新标签 + noopener，避免反向劫持。

// md 里常见语言别名 → refractor（PrismAsyncLight 的语言加载器）真实 ID。
// 只映射"别名 ≠ 加载器 ID"的情况，其余（go / java / python / rust…）原样透传。
const LANG_ALIAS: Record<string, string> = {
  js: 'javascript',
  ts: 'typescript',
  'c++': 'cpp',
  'c#': 'csharp',
  sh: 'bash',
  shell: 'bash',
  py: 'python',
  rb: 'ruby',
  yml: 'yaml',
  md: 'markdown',
  html: 'markup',
  xml: 'markup',
  svg: 'markup',
  k8s: 'yaml',
  dockerfile: 'docker',
};

/** 代码块右上角语言角标（仅对常见语言显示；不认识的不显示，不猜）。 */
const LANG_LABEL: Record<string, string> = {
  go: 'Go',
  javascript: 'JavaScript',
  typescript: 'TypeScript',
  jsx: 'JSX',
  tsx: 'TSX',
  python: 'Python',
  bash: 'Bash',
  json: 'JSON',
  sql: 'SQL',
  yaml: 'YAML',
  markdown: 'Markdown',
  markup: 'HTML',
  css: 'CSS',
  scss: 'SCSS',
  java: 'Java',
  rust: 'Rust',
  kotlin: 'Kotlin',
  swift: 'Swift',
  cpp: 'C++',
  csharp: 'C#',
  php: 'PHP',
  ruby: 'Ruby',
  diff: 'Diff',
  docker: 'Docker',
  graphql: 'GraphQL',
  toml: 'TOML',
  ini: 'INI',
  properties: 'Properties',
  scala: 'Scala',
  dart: 'Dart',
  elixir: 'Elixir',
  haskell: 'Haskell',
  lua: 'Lua',
  perl: 'Perl',
  r: 'R',
};

const components: Components = {
  a: ({ node, ...props }) => (
    <a {...props} target="_blank" rel="noopener noreferrer nofollow" />
  ),
  // react-markdown 默认用 <pre> 包裹代码块；我们的 code 组件已自管结构（高亮 div / 原生 pre），
  // 拆掉外层 pre 避免套出两层方框（.md pre 的背景/边框会再裹一层）。
  pre: ({ children }) => <>{children}</>,
  // 块级代码（```lang …```）→ 语法高亮 + 语言角标；
  // 无语言标注的代码块 → 保持原生 pre（浅色底纹）；行内 code → 原生 code。
  code: ({ className, children, node, ...props }) => {
    const raw = String(children);
    const text = raw.replace(/\n$/, ''); // 去掉 Prism 渲染时多余的尾换行
    const match = /language-([\w+#-]+)/.exec(className ?? '');

    if (match) {
      const lang = (LANG_ALIAS[match[1].toLowerCase()] ?? match[1]).toLowerCase();
      const label = LANG_LABEL[lang];
      return (
        <div className="md-code-block">
          {label && <span className="md-code-lang">{label}</span>}
          <SyntaxHighlighter
            language={lang}
            style={oneLight}
            PreTag="div"
            customStyle={{
              margin: 0,
              padding: 'var(--s-3) var(--s-4)',
              borderRadius: 'var(--r-md)',
              background: 'color-mix(in oklch, var(--ink) 6%, transparent)',
              border: '1px solid color-mix(in oklch, var(--ink) 12%, transparent)',
              fontSize: '0.85em',
              lineHeight: 1.6,
            }}
            codeTagProps={{
              style: {
                fontFamily: 'var(--font-mono)',
                background: 'none',
                border: 'none',
                padding: 0,
                // 未命中 token 的裸文本继承正文色（--ink），避免染上 .md code 的朱砂红
                color: 'inherit',
              },
            }}
          >
            {text}
          </SyntaxHighlighter>
        </div>
      );
    }

    // 无语言标注的多行块：补回 pre 结构，走 .md pre 的浅色底纹样式
    // （用原始文本判断换行，避免去掉尾换行后误判为行内 code）
    if (raw.includes('\n')) {
      return (
        <pre className={className}>
          <code {...props}>{children}</code>
        </pre>
      );
    }
    return <code className={className} {...props}>{children}</code>;
  },
};

/**
 * 把 markdown 文本安全渲染为富文本。用于 AI 生成的聊天气泡（题干 / 讲解 / 追问），
 * 让用户看到的代码块、列表、加粗、表格等结构正常呈现。
 * 代码块带语言时做语法高亮（PrismLight：常用语言已随包注册，未注册的语言自动降级纯文本）。
 */
export function Markdown({ children, className }: { children: string; className?: string }) {
  return (
    <div className={`md${className ? ' ' + className : ''}`}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
