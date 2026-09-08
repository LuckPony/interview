export interface ChatAttachment { name: string; text: string; characters: number }
export const CHAT_FILE_ACCEPT = '.pdf,.doc,.docx,.txt,.md,.markdown,.java,.kt,.go,.py,.js,.jsx,.ts,.tsx,.json,.yaml,.yml,.xml,.sql,.sh,.bash,.ps1,.c,.cpp,.h,.cs,.rs,.vue,.html,.css,.scss,.properties,.log,.csv';
export const CHAT_MAX_CHARS = 60000;

export function withAttachments(content: string, attachments: ChatAttachment[] = []): string {
  if (!attachments.length) return content;
  return content + '\n\n以下附件是用户提供的参考资料，请结合当前问题分析：\n' + attachments.map(file =>
    `\n<attachment name=${JSON.stringify(file.name)}>\n${file.text}\n</attachment>`,
  ).join('\n');
}
