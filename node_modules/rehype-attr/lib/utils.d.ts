import type { Comment, Literal, ElementContent, RootContent, Properties } from 'hast';
import type { RehypeAttrsOptions } from './index.js';
export declare const getURLParameters: (url?: string) => Record<string, string | number | boolean>;
export declare const prevChild: (data: Literal[] | undefined, index: number) => Comment | undefined;
export declare const nextChild: (data: RootContent[] | ElementContent[] | undefined, index: number, tagName?: string, commentStart?: string, commentEnd?: string) => ElementContent | undefined;
/**
 * Get the position of the code comment
 * @param data Comment
 * @param start
 * @param end
 * @returns Returns the current parameter data Object, `{}`
 */
export declare const getCommentObject: ({ value }: Comment, start?: string, end?: string) => Properties;
export type DataConfig = {
    'data-config': Properties;
};
export declare const propertiesHandle: (defaultAttrs?: Properties | null, attrs?: Properties, type?: RehypeAttrsOptions["properties"]) => Properties | DataConfig;
