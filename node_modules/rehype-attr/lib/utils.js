export const getURLParameters = (url = '') => (url.match(/([^?=&]+)(=([^&]*))/g) || []).reduce((a, v) => ((a[v.slice(0, v.indexOf('='))] = v.slice(v.indexOf('=') + 1)), a), {});
export const prevChild = (data = [], index) => {
    let i = index;
    while (i > -1) {
        i--;
        if (!data[i])
            return;
        if ((data[i] && data[i].value && data[i].value.replace(/(\n|\s)/g, '') !== '') || data[i].type !== 'text') {
            if (!/^rehype:/.test(data[i].value) || data[i].type !== 'comment')
                return;
            return data[i];
        }
    }
    return;
};
export const nextChild = (data = [], index, tagName, commentStart = "<!--", commentEnd = "-->") => {
    let i = index;
    while (i < data.length) {
        i++;
        if (tagName) {
            const element = data[i];
            if (element && element.value && element.value.replace(/(\n|\s)/g, '') !== '' || data[i] && data[i].type === 'element') {
                return element.tagName === tagName ? element : undefined;
            }
        }
        else {
            const element = data[i];
            if (!element || element.type === 'element')
                return;
            if (element.type === 'text') {
                const nextNode = nextChild(data, i, undefined);
                if (nextNode)
                    return nextNode;
            }
            ;
            if (element.type && /^(comment|raw|text)$/ig.test(element.type)) {
                const regx = new RegExp(`^(\s+)?${commentStart}(.*?)${commentEnd}`);
                if (element.value && !/^rehype:/.test(element.value.replace(/^(\n|\s)+/, '').replace(regx, '$2') || '')) {
                    return;
                }
                ;
                let comment = element.value.replace(/^(\n|\s)+/, '');
                element.value = comment;
                return element;
            }
        }
    }
    return;
};
/**
 * Get the position of the code comment
 * @param data Comment
 * @param start
 * @param end
 * @returns Returns the current parameter data Object, `{}`
 */
export const getCommentObject = ({ value = '' }, start = "<!--", end = "-->") => {
    let regx;
    try {
        // Construct a regular expression to match the comment content
        regx = new RegExp(`^${start}(.*?)${end}`);
    }
    catch (error) {
        return {};
    }
    const match = value.match(regx);
    const content = (match ? match[1] : value);
    // Extract the comment content if it matches the regular expression
    const commentContent = content.replace(/^rehype:/, '');
    // Extract the comment content and parse it into a parameter object
    const param = getURLParameters(commentContent);
    // Iterate over the key-value pairs of the parameter object and perform type conversion
    Object.keys(param).forEach((keyName) => {
        if (param[keyName] === 'true') {
            param[keyName] = true;
        }
        else if (param[keyName] === 'false') {
            param[keyName] = false;
        }
        else if (typeof param[keyName] === 'string' && !/^0/.test(param[keyName]) && !isNaN(+param[keyName])) {
            param[keyName] = +param[keyName];
        }
    });
    // Return the processed parameter object
    return param;
};
export const propertiesHandle = (defaultAttrs, attrs, type) => {
    if (type === 'string') {
        return { ...defaultAttrs, 'data-config': JSON.stringify({ ...attrs, rehyp: true }) };
    }
    else if (type === 'attr') {
        return { ...defaultAttrs, ...attrs };
    }
    return { ...defaultAttrs, 'data-config': { ...attrs, rehyp: true } };
};
//# sourceMappingURL=utils.js.map