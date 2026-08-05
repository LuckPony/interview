package interview.homegrown.modules.resume.controller;


import interview.homegrown.common.result.Result;
import interview.homegrown.modules.resume.model.ResumeDetailDTO;
import interview.homegrown.modules.resume.model.ResumeListItemDTO;
import interview.homegrown.modules.resume.service.ResumeDeleteService;
import interview.homegrown.modules.resume.service.ResumeQueryService;
import interview.homegrown.modules.resume.service.ResumeUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

//简历管理接口
@RestController
@RequestMapping("/api/resumes")
@Tag(name = "简历管理",description = "上传简历/AI分析/列表与详情")
public class ResumeController {

    private final ResumeUploadService resumeUploadService;
    private final ResumeQueryService resumeQueryService;
    private final ResumeDeleteService resumeDeleteService;

    public ResumeController(ResumeUploadService resumeUploadService, ResumeQueryService resumeQueryService, ResumeDeleteService resumeDeleteService) {
        this.resumeUploadService = resumeUploadService;
        this.resumeQueryService = resumeQueryService;
        this.resumeDeleteService = resumeDeleteService;
    }

    //上传简历并分析简历
    @PostMapping(value = "/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传并分析简历",description =  "上传 PDF/DOCX/TXT -> 校验 -> 去重 -> S3 存储 -> 解析 -> AI 分析（同步）")
    public Result<ResumeDetailDTO> upload(
            @Parameter(description = "简历文件（PDF/DOCX/TXT/DEC")
            @RequestParam("file")MultipartFile file
            ) throws IOException {
        ResumeDetailDTO detail = resumeUploadService.upload(file.getBytes(),file.getOriginalFilename());
        return Result.success(detail);
    }

    //查看所有简历列表
    @GetMapping
    @Operation(summary = "简历列表", description = "按照创建时间倒序返回所有简历及分析得分")
    public Result<List<ResumeListItemDTO>> list(){
        return Result.success(resumeQueryService.list());
    }

    //查看简历详情
    @GetMapping("/{id}")
    @Operation(summary = "简历详情", description = "返回简历原文与AI分析报告")
    public Result<ResumeDetailDTO> detail(@PathVariable Long id){
        return Result.success(resumeQueryService.getDetail(id));
    }

    //删除简历
    @DeleteMapping (value = "/{id}")
    @Operation(summary = "删除简历",description = "根据简历id删除指定简历")
    public Result<Void> delete(
            @Parameter(description = "id")
            @PathVariable Long id) {

        resumeDeleteService.delete(id);
        return Result.success();
    }

}
