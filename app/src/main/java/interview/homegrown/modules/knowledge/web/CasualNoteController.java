package interview.homegrown.modules.knowledge.web;

import interview.homegrown.common.result.Result;
import interview.homegrown.modules.knowledge.domain.CasualNote;
import interview.homegrown.modules.knowledge.service.CasualNoteService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/notes")
public class CasualNoteController {

    private final CasualNoteService noteService;

    public CasualNoteController(CasualNoteService noteService) {
        this.noteService = noteService;
    }

    private Long uid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }

    @GetMapping
    public Result<List<CasualNote>> list() {
        return Result.success(noteService.list(uid()));
    }

    public static record CreateRequest(String title, String content, Long conceptId, Long chatId) {}

    @PostMapping
    public Result<CasualNote> create(@RequestBody CreateRequest req) {
        return Result.success(noteService.create(uid(), req.title(), req.content(), req.conceptId(), req.chatId()));
    }

    public static record UpdateRequest(String title, String content) {}

    @PutMapping("/{id}")
    public Result<CasualNote> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return Result.success(noteService.update(uid(), id, req.title(), req.content()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noteService.delete(uid(), id);
        return Result.success();
    }
}
