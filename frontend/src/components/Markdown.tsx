import ReactMarkdown from 'react-markdown';
import type { Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './Markdown.css';

// 安全渲染：react-markdown 默认不渲染原始 HTML（转义），配合下面不放行
// rehype-raw，可天然规避 XSS。链接强制新标签 + noopener，避免反向劫持。
const components: Components = {
  a: ({ node, ...props }) => (
    <a {...props} target="_blank" rel="noopener noreferrer nofollow" />
  ),
};

/**
 * 把 markdown 文本安全渲染为富文本。用于 AI 生成的聊天气泡（题干 / 讲解 / 追问），
 * 让用户看到的代码块、列表、加粗、表格等结构正常呈现。
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
