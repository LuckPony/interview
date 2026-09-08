import { useEffect, useRef, useState, type DragEvent } from 'react';

/** 只接管文件拖拽，不影响文本选择或将代码拖进输入框。 */
export function useFileDrop(onFiles: (files: File[]) => void, disabled = false) {
  const [dragging, setDragging] = useState(false);
  const depth = useRef(0);
  const isFile = (event: DragEvent) => event.dataTransfer.types.includes('Files');
  useEffect(() => {
    const preventNavigation = (event: globalThis.DragEvent) => {
      if (!event.dataTransfer?.types.includes('Files')) return;
      event.preventDefault();
      if (event.type === 'drop') { depth.current = 0; setDragging(false); }
    };
    window.addEventListener('dragover', preventNavigation);
    window.addEventListener('drop', preventNavigation);
    return () => {
      window.removeEventListener('dragover', preventNavigation);
      window.removeEventListener('drop', preventNavigation);
    };
  }, []);
  return {
    dragging: dragging && !disabled,
    dropProps: {
      onDragEnter(event: DragEvent) {
        if (!isFile(event)) return;
        event.preventDefault();
        depth.current += 1;
        if (!disabled) setDragging(true);
      },
      onDragOver(event: DragEvent) {
        if (!isFile(event)) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = disabled ? 'none' : 'copy';
      },
      onDragLeave(event: DragEvent) {
        if (!isFile(event)) return;
        depth.current = Math.max(0, depth.current - 1);
        if (!depth.current) setDragging(false);
      },
      onDrop(event: DragEvent) {
        if (!isFile(event)) return;
        event.preventDefault();
        event.stopPropagation();
        depth.current = 0;
        setDragging(false);
        if (!disabled) onFiles(Array.from(event.dataTransfer.files));
      },
    },
  };
}
