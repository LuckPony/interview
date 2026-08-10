package interview.homegrown.modules.drill.web;

import interview.homegrown.modules.drill.security.JwtUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 演示用登录端点。真实产品应接入用户系统后再签发 token。
 * 返回 JWT，userId 写入 token 的 sub，后续 drill 端点凭此 token 鉴权。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtUtil jwtUtil;

    public AuthController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestParam Long userId) {
        String token = jwtUtil.generateToken(userId);
        return Map.of("token", token, "userId", String.valueOf(userId));
    }
}
