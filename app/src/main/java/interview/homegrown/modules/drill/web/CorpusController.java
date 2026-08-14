package interview.homegrown.modules.drill.web;

import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.service.CorpusService;
import interview.homegrown.modules.drill.web.dto.CorpusView;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** 个人资料上传：POST /api/corpus/upload（multipart）。解析后的文本存库，返回 id。 */
@RestController
@RequestMapping("/api/corpus")
public class CorpusController {

    private final CorpusService service;

    public CorpusController(CorpusService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public CorpusView upload(@RequestParam("file") MultipartFile file) {
        Corpus c = service.upload(file, currentUserId());
        return new CorpusView(c.getId(), c.getName(), c.getCharCount());
    }

    /** 桌面端免上传：直接读本地文件 / 文件夹（仅本地部署有意义）。 */
    @PostMapping("/from-path")
    public CorpusView fromPath(@RequestBody FromPathRequest req) {
        Corpus c = service.fromPath(req.path(), currentUserId());
        return new CorpusView(c.getId(), c.getName(), c.getCharCount());
    }

    /** 云端桌面端：Electron 在本机读好文件字节传上来，服务端 Tika 解析合并。 */
    @PostMapping("/from-files")
    public CorpusView fromFiles(@RequestParam("files") MultipartFile[] files,
                                @RequestParam(value = "folderName", required = false) String folderName) {
        Corpus c = service.fromFiles(files, folderName, currentUserId());
        return new CorpusView(c.getId(), c.getName(), c.getCharCount());
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未鉴权");
        }
        return (Long) auth.getPrincipal();
    }

    public record FromPathRequest(String path) {}
}
