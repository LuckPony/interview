package interview.homegrown.modules.knowledge.service;

import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.knowledge.domain.CasualNote;
import interview.homegrown.modules.knowledge.repository.CasualNoteRepository;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Service
public class CasualNoteService {
    private final CasualNoteRepository noteRepo;
    private final DrillRunRepository runRepo;
    private final QuestionBankRepository qRepo;
    private final ConceptRepository conceptRepo;

    public CasualNoteService(CasualNoteRepository noteRepo,
                            DrillRunRepository runRepo,
                            QuestionBankRepository qRepo,
                            ConceptRepository conceptRepo) {
        this.noteRepo = noteRepo;
        this.runRepo = runRepo;
        this.qRepo = qRepo;
        this.conceptRepo = conceptRepo;
    }

    /** List all notes for a user, newest first */
    @Transactional(readOnly = true)
    public List<CasualNote> list(Long userId) {
        return noteRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Create a new note. If conceptId is null but chatId provided, resolve concept from the run's question. */
    @Transactional
    public CasualNote create(Long userId, String title, String content, Long conceptId, Long chatId) {
        CasualNote note = new CasualNote();
        note.setUserId(userId);
        // Resolve concept if needed
        Long resolvedConceptId = conceptId;
        String resolvedConceptName = null;
        if (resolvedConceptId == null && chatId != null) {
            Optional<DrillRun> runOpt = runRepo.findByUserIdAndId(userId, chatId);
            if (runOpt.isPresent()) {
                DrillRun run = runOpt.get();
                // DrillRun has questionId
                QuestionBank qb = qRepo.findById(run.getQuestionId()).orElse(null);
                // 组合题可跨多个概念（concept_ids[]），随手记挂到第一个概念
                if (qb != null && qb.getConceptIds() != null && qb.getConceptIds().length > 0) {
                    resolvedConceptId = qb.getConceptIds()[0].longValue();
                }
            }
        }
        if (resolvedConceptId != null) {
            // Fetch concept name snapshot via repository
            resolvedConceptName = conceptRepo.findById(resolvedConceptId).map(Concept::getName).orElse(null);
        }
        // If title is blank, derive from first line of content
        String finalTitle = (title != null && !title.isBlank()) ? title.trim() : (content != null ? content.split("\\n")[0].trim() : "随手记");
        note.setTitle(finalTitle);
        note.setContent(content != null ? content : "");
        note.setConceptId(resolvedConceptId);
        note.setConceptName(resolvedConceptName);
        note.setChatId(chatId);
        return noteRepo.save(note);
    }

    @Transactional
    public CasualNote update(Long userId, Long noteId, String title, String content) {
        CasualNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));
        if (!note.getUserId().equals(userId)) {
            throw new RuntimeException("Forbidden");
        }
        if (title != null && !title.isBlank()) {
            note.setTitle(title.trim());
        } else if (content != null && !content.isBlank()) {
            note.setTitle(content.split("\\n")[0].trim());
        }
        if (content != null) {
            note.setContent(content);
        }
        return noteRepo.save(note);
    }

    @Transactional
    public void delete(Long userId, Long noteId) {
        CasualNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));
        if (!note.getUserId().equals(userId)) {
            throw new RuntimeException("Forbidden");
        }
        noteRepo.delete(note);
    }
}
