package interview.homegrown.infrastructure.captcha;

import interview.homegrown.infrastructure.redis.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 图像拼图滑块验证：后端随机选底图，随机生成目标 X，
 * 实时用 Java2D 裁剪「拼块」并给「底图」挖出同形槽位，
 * 目标 X 只落 Redis，绝不回传前端。验证通过后签发一次性 ticket，
 * 供 register 等敏感动作核销（防止脚本跳过滑块直连业务接口）。
 */
@Service
public class PuzzleCaptchaService {

    private static final Logger log = LoggerFactory.getLogger(PuzzleCaptchaService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 拼块/槽形状参数（凸起向右探出 bbox，整体宽度 = PIECE + TAB*2） */
    private static final int PIECE = 44;      // 拼块主体边长
    private static final int BUMP_R = 8;      // 右侧凸起的圆半径
    private static final int SHADOW = 2;      // 拼块描边留白

    private final RedisService redis;
    private final boolean enabled;
    private final int width;
    private final int height;
    private final int tolerance;
    private final Duration ttl;
    private final Duration ticketTtl;

    private final Resource[] backgrounds;     // classpath:captcha/bg/* 底图

    public PuzzleCaptchaService(RedisService redis,
                                @Value("${app.captcha.enabled:true}") boolean enabled,
                                @Value("${app.captcha.width:320}") int width,
                                @Value("${app.captcha.height:170}") int height,
                                @Value("${app.captcha.tolerance:6}") int tolerance,
                                @Value("${app.captcha.ttl-seconds:120}") long ttlSeconds,
                                @Value("${app.captcha.ticket-ttl-seconds:300}") long ticketTtlSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.width = width;
        this.height = height;
        this.tolerance = tolerance;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.ticketTtl = Duration.ofSeconds(ticketTtlSeconds);
        try {
            this.backgrounds = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:captcha/bg/*.{jpg,jpeg,png}");
        } catch (IOException e) {
            throw new IllegalStateException("扫描滑块底图失败", e);
        }
        if (backgrounds.length == 0) {
            log.warn("captcha/bg 目录下没有底图，将使用程序化渐变底图兜底（建议放入 4~6 张风景图）");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 拼块总宽度（含右侧凸起与描边）。 */
    private int pieceWidth() {
        return PIECE + BUMP_R + SHADOW;
    }

    /** 拼块可到达的最大左移量（= 目标 X 的上限）。 */
    public int sliderMax() {
        return width - pieceWidth();
    }

    /** 在 (x,y) 处构造拼块形状（拼块与槽共用同一形状 → 必然吻合）。 */
    private Area shapeAt(double x, double y) {
        Area area = new Area(new RoundRectangle2D.Double(x, y, PIECE, PIECE, 8, 8));
        // 右侧凸起圆：圆心在主体右缘外侧，半径 BUMP_R
        area.add(new Area(new Ellipse2D.Double(x + PIECE - 2, y + PIECE / 2.0 - BUMP_R, BUMP_R * 2, BUMP_R * 2)));
        return area;
    }

    // ==================== 出题 ====================

    public CaptchaIssue issue() {
        if (!enabled) {
            throw new IllegalArgumentException("滑块验证已关闭");
        }
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        int targetX = randomTargetX();
        int y = 4 + RANDOM.nextInt(Math.max(1, height - PIECE - 8));

        BufferedImage source = pickBackground();
        BufferedImage bg = punchHole(source, targetX, y);     // 挖槽后的底图
        BufferedImage piece = cutPiece(source, targetX, y);   // 裁出的拼块

        String captchaId1 = captchaId;
        // 目标 X 只落 Redis
        redis.set("captcha:issue:" + captchaId1, String.valueOf(targetX), ttl);

        return new CaptchaIssue(captchaId,
                toBase64Png(bg), toBase64Png(piece),
                y, width, height, sliderMax());
    }

    private int randomTargetX() {
        return 6 + RANDOM.nextInt(Math.max(1, sliderMax() - 6));
    }

    /** 底图中心裁剪 + 等比缩放到画布尺寸。 */
    private BufferedImage pickBackground() {
        if (backgrounds.length > 0) {
            try (InputStream in = backgrounds[RANDOM.nextInt(backgrounds.length)].getInputStream()) {
                BufferedImage src = ImageIO.read(in);
                if (src != null) {
                    return centerCropAndScale(src);
                }
            } catch (IOException e) {
                log.warn("读取滑块底图失败，改用程序化底图：{}", e.getMessage());
            }
        }
        return proceduralBackground(RANDOM.nextInt(1_000_000));
    }

    private BufferedImage centerCropAndScale(BufferedImage src) {
        double scale = Math.max((double) width / src.getWidth(), (double) height / src.getHeight());
        int cw = (int) Math.min(src.getWidth(), width / scale);
        int ch = (int) Math.min(src.getHeight(), height / scale);
        int cx = (src.getWidth() - cw) / 2;
        int cy = (src.getHeight() - ch) / 2;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, cx, cy, cx + cw, cy + ch, null);
        g.dispose();
        return out;
    }

    /** 程序化兜底底图：随机配色渐变 + 半透明圆。 */
    private BufferedImage proceduralBackground(int seed) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        java.util.Random r = new java.util.Random(seed);
        Color c1 = Color.getHSBColor(r.nextFloat(), 0.45f, 0.85f);
        Color c2 = Color.getHSBColor(r.nextFloat(), 0.45f, 0.65f);
        g.setPaint(new GradientPaint(0, 0, c1, width, height, c2));
        g.fillRect(0, 0, width, height);
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256), 70));
            int cr = 20 + r.nextInt(60);
            g.fillOval(r.nextInt(width + cr) - cr, r.nextInt(height + cr) - cr, cr * 2, cr * 2);
        }
        g.dispose();
        return out;
    }

    /** 挖槽：把 (x,y) 处形状区域清成透明，再描一圈浅槽边便于人眼定位。 */
    private BufferedImage punchHole(BufferedImage source, int x, int y) {
        BufferedImage bg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, null);

        Area shape = shapeAt(x, y);
        g.setComposite(AlphaComposite.DstOut); // 形状区域内清空 alpha → 透明槽
        g.setColor(Color.WHITE);
        g.fill(shape);

        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(0, 0, 0, 60));    // 槽的淡描边（引导对齐）
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
        g.dispose();
        return bg;
    }

    /** 裁拼块：只把形状区域从底图抠出，四周留描边；返回紧贴的小图。 */
    private BufferedImage cutPiece(BufferedImage source, int x, int y) {
        int pw = pieceWidth();
        int ph = PIECE + SHADOW * 2;
        int left = x - SHADOW;
        int top = y - SHADOW;
        BufferedImage piece = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = piece.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(-left, -top);              // 画布坐标系对齐到底图
        g.setClip(shapeAt(x, y));
        g.drawImage(source, 0, 0, null);
        g.setClip(null);
        // 白描边 + 阴影，让拼块在页面上清晰可辨
        g.translate(left, top);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 255, 255, 220));
        g.draw(shapeAt(x, y));
        g.setColor(new Color(0, 0, 0, 40));
        g.setStroke(new BasicStroke(4f));
        g.draw(shapeAt(x, y + 1));
        g.dispose();
        return piece;
    }

    private String toBase64Png(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("滑块图片编码失败", e);
        }
    }

    // ==================== 校验 ====================

    /**
     * 校验拼块位置。题目一次性：无论对错立即删除，防暴力试 x。
     * 通过则签发一次性 ticket（供 register 核销）。
     */
    public String verify(String captchaId, int x) {
        String key = "captcha:issue:" + captchaId;
        var maybe = redis.get(key);
        redis.delete(key);                       // 先作废，杜绝重放
        if (maybe.isEmpty()) {
            throw new IllegalArgumentException("滑块验证已过期，请重新验证");
        }
        int targetX;
        try {
            targetX = Integer.parseInt(maybe.get());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("滑块验证已过期，请重新验证");
        }
        if (Math.abs(x - targetX) > tolerance) {
            throw new IllegalArgumentException("未对准缺口，请重新滑动");
        }
        String ticket = UUID.randomUUID().toString().replace("-", "") + RANDOM.nextInt(10000);
        redis.set("captcha:ticket:" + ticket, "1", ticketTtl);
        return ticket;
    }

    /** 消费一次性 ticket：存在则删除并返回 true，否则 false。 */
    public boolean consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        String key = "captcha:ticket:" + ticket.trim();
        if (!redis.hasKey(key)) {
            return false;
        }
        redis.delete(key);
        return true;
    }

    public record CaptchaIssue(
            String captchaId,
            String bgImage,      // 挖槽后底图（base64 data URI）
            String pieceImage,   // 拼块（base64 data URI，左上角即拼块左上角）
            int pieceY,          // 拼块应在底图中的垂直位置
            int width,
            int height,
            int sliderMax        // 滑块最大位移（= 目标 X 上限）
    ) {}
}