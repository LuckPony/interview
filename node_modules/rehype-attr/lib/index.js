import { visit } from 'unist-util-visit';
import { propertiesHandle, nextChild, prevChild, getCommentObject } from './utils.js';
const rehypeAttrs = (options = {}) => {
    const { properties = 'data', codeBlockParames = true, commentStart = "<!--", commentEnd = "-->" } = options;
    return (tree) => {
        visit(tree, 'element', (node, index, parent) => {
            if (codeBlockParames && node.tagName === 'pre' && node && Array.isArray(node.children) && parent && Array.isArray(parent.children) && parent.children.length > 1) {
                const firstChild = node.children[0];
                if (firstChild && firstChild.tagName === 'code' && typeof index === 'number') {
                    const child = prevChild(parent.children, index);
                    if (child) {
                        const attr = getCommentObject(child, commentStart, commentEnd);
                        if (Object.keys(attr).length > 0) {
                            node.properties = { ...node.properties, ...{ 'data-type': 'rehyp' } };
                            firstChild.properties = propertiesHandle(firstChild.properties, attr, properties);
                        }
                    }
                }
            }
            let rootnode = parent;
            if ((/^(em|strong|b|a|i|p|pre|kbd|blockquote|h(1|2|3|4|5|6)|code|table|img|del|ul|ol)$/.test(node.tagName) || rootnode.type == "root") && parent && Array.isArray(parent.children) && typeof index === 'number') {
                const child = nextChild(parent.children, index, '', commentStart, commentEnd);
                if (child) {
                    const attr = getCommentObject(child, commentStart, commentEnd);
                    if (Object.keys(attr).length > 0) {
                        node.properties = propertiesHandle(node.properties, attr, properties);
                    }
                }
            }
        });
    };
};
export default rehypeAttrs;
//# sourceMappingURL=index.js.map