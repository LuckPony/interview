import { apiFetch } from './client';

export interface CaptchaIssue {
    captchaId: string;
    /** 已挖槽的底图（base64 data URI） */
    bgImage: string;
    /** 拼块小图（base64 data URI） */
    pieceImage: string;
    /** 拼块在底图中的垂直位置 */
    pieceY: number;
    width: number;
    height: number;
    /** 滑块最大位移 */
    sliderMax: number;
}

export async function createCaptcha(): Promise<CaptchaIssue> {
    return apiFetch<CaptchaIssue>('/auth/captcha');
}

export async function verifyCaptcha(
    captchaId: string,
    x: number,
): Promise<{ pass: boolean; captchaToken: string }> {
    return apiFetch('/auth/captcha/verify', {
        method: 'POST',
        body: JSON.stringify({ captchaId, x }),
    });
}