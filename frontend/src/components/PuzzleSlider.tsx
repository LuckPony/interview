import { useEffect, useRef, useState } from 'react';
import { Loader2, RefreshCw, ShieldCheck } from 'lucide-react';
import { ApiError } from '../api/client';
import { createCaptcha, verifyCaptcha, type CaptchaIssue } from '../api/captcha';
import './PuzzleSlider.css';

export function PuzzleSlider({
                                 onPass,
                                 onClose,
                             }: {
    onPass: (captchaToken: string) => void;
    onClose?: () => void;
}) {
    const [captcha, setCaptcha] = useState<CaptchaIssue | null>(null);
    const [drag, setDrag] = useState(0);       // 滑块当前位置 0..sliderMax
    const [err, setErr] = useState('');
    const [passing, setPassing] = useState(false);
    const [passed, setPassed] = useState(false);
    const [loading, setLoading] = useState(true);
    const draggingRef = useRef(false);
    const startXRef = useRef(0);
    const baseDragRef = useRef(0);
    const bgRef = useRef<HTMLDivElement>(null);
    const [scale, setScale] = useState(1); // 底图实际渲染宽度 / captcha.width（防 CSS 缩放错位）

    const load = async () => {
        setLoading(true);
        setErr('');
        setDrag(0);
        setPassing(false);
        setPassed(false);
        try {
            setCaptcha(await createCaptcha());
        } catch (e) {
            setErr(e instanceof ApiError ? e.message : '验证码加载失败，请重试');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        void load();
    }, []);

    // captcha 渲染完成后，量底图实际渲染宽度算缩放系数；正常为 1
    useEffect(() => {
        if (!captcha) return;
        const el = bgRef.current;
        setScale(el && el.clientWidth > 0 ? el.clientWidth / captcha.width : 1);
    }, [captcha]);

    const onPointerDown = (e: React.PointerEvent) => {
        if (passed) return;
        (e.target as HTMLElement).setPointerCapture(e.pointerId);
        draggingRef.current = true;
        startXRef.current = e.clientX;
        baseDragRef.current = drag;
    };

    const onPointerMove = (e: React.PointerEvent) => {
        if (!draggingRef.current) return;
        const dx = e.clientX - startXRef.current;
        const max = (captcha?.sliderMax ?? 0) * scale;
        setDrag(Math.max(0, Math.min(max, baseDragRef.current + dx)));
    };

    const onPointerUp = async () => {
        if (!draggingRef.current) return;
        draggingRef.current = false;
        if (!captcha || passed) return;
        // 没动过或离最左太近就当误触
        if (drag < 4) {
            setErr('请按住滑块向右拖动');
            return;
        }
        setPassing(true);
        try {
            const r = await verifyCaptcha(captcha.captchaId, Math.round(drag / scale));
            setPassed(true);
            setTimeout(() => onPass(r.captchaToken), 350);
        } catch (e) {
            setErr(e instanceof ApiError ? e.message : '验证失败，请重试');
            // 题目已作废 → 自动换新题
            setTimeout(() => void load(), 600);
        } finally {
            setPassing(false);
        }
    };

    return (
        <div className="ps-overlay" onPointerDown={(e) => e.stopPropagation()}>
            <div className="ps-card">
                <div className="ps-head">
                    <span>{passed ? '验证通过' : '安全验证'}</span>
                    <div className="ps-head-actions">
                        {!loading && !passed && (
                            <button className="ps-icon-btn" onClick={() => void load()} title="换一张">
                                <RefreshCw size={15} />
                            </button>
                        )}
                        {onClose && (
                            <button className="ps-icon-btn" onClick={onClose} title="关闭">
                                ×
                            </button>
                        )}
                    </div>
                </div>

                {loading ? (
                    <div className="ps-body ps-loading">
                        <Loader2 size={22} className="spin" />
                    </div>
                ) : captcha && passed ? (
                    <div className="ps-body ps-ok">
                        <ShieldCheck size={30} />
                        <span>验证通过，正在继续…</span>
                    </div>
                ) : captcha ? (
                    <>
                        <div className="ps-body">
                            <div
                                ref={bgRef}
                                className="ps-bg"
                                style={{ width: captcha.width * scale, height: captcha.height * scale }}
                            >
                                <img src={captcha.bgImage} alt="验证图" draggable={false} />
                                {/* 拼块：left = 滑块位移值；坐标与尺寸乘 scale，与底图严格 1:1 */}
                                <img
                                    className="ps-piece"
                                    src={captcha.pieceImage}
                                    alt=""
                                    draggable={false}
                                    style={{
                                        left: drag,
                                        top: captcha.pieceY * scale,
                                        width: captcha.pieceWidth * scale,
                                        height: captcha.pieceHeight * scale,
                                    }}
                                />
                            </div>
                            {err && <div className="ps-err">{err}</div>}
                            <div className="ps-hint">按住滑块向右拖动，使拼图与原图缺口重合</div>
                        </div>

                        <div
                            className="ps-track"
                            style={{ width: captcha.width * scale }}
                            onPointerDown={onPointerDown}
                            onPointerMove={onPointerMove}
                            onPointerUp={onPointerUp}
                        >
                            <div
                                className="ps-progress"
                                style={{ width: `${(drag / (captcha.sliderMax * scale)) * 100}%` }}
                            />
                            <div
                                className="ps-thumb"
                                style={{ left: drag, touchAction: 'none' }}
                            >
                                {passing ? <Loader2 size={18} className="spin" /> : '»'}
                            </div>
                            <span className="ps-track-text">向右拖动滑块完成拼图</span>
                        </div>
                    </>
                ) : (
                    <div className="ps-body">
                        <div className="ps-err">{err}</div>
                        <button className="ps-retry" onClick={() => void load()}>
                            重新加载
                        </button>
                    </div>
                )}
            </div>
        </div>
    );
}