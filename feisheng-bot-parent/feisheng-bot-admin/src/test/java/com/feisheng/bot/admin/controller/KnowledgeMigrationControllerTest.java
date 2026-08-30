package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.service.KnowledgeMigrationJobService;
import com.feisheng.bot.admin.service.KnowledgeMigrationReviewService;
import com.feisheng.bot.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeMigrationControllerTest {
    @Test
    void resolveUsesAuthenticatedReviewerAndReturnsServiceErrorsAsApiResponse() throws Exception {
        KnowledgeMigrationReviewService reviewService = mock(KnowledgeMigrationReviewService.class);
        when(reviewService.resolveConflict(eq(4L), eq(8L), any(), eq(77L))).thenReturn(
            new KnowledgeMigrationReviewService.ConflictResolution(4L, 8L, "RESOLVED", "MERGE", 77L,
                new java.util.Date()));
        MockMvc mvc = mvc(reviewService);

        mvc.perform(post("/api/admin/knowledge/migrations/4/conflicts/8/resolve")
                .principal(new UsernamePasswordAuthenticationToken(77L, "reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"MERGE\",\"note\":\"checked\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.reviewerId").value(77));
        verify(reviewService).resolveConflict(eq(4L), eq(8L), any(), eq(77L));

        when(reviewService.confirmDocument(eq(4L), any(), eq(77L))).thenThrow(
            new KnowledgeMigrationReviewService.ReviewException(409, "门禁未通过"));
        mvc.perform(post("/api/admin/knowledge/migrations/4/review/confirm")
                .principal(new UsernamePasswordAuthenticationToken(77L, "reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"checked\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.msg").value("门禁未通过"));
    }

    @Test
    void switchAndRollbackRemainExplicitlyUnavailableUntilReleaseWorkflowExists() throws Exception {
        MockMvc mvc = mvc(mock(KnowledgeMigrationReviewService.class));

        mvc.perform(post("/api/admin/knowledge/migrations/4/switch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(501));
        mvc.perform(post("/api/admin/knowledge/sets/contracts/rollback"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(501));
    }

    @Test
    void jobLookupReturnsMigrationErrorsInTheApiEnvelope() throws Exception {
        KnowledgeMigrationReviewService reviewService = mock(KnowledgeMigrationReviewService.class);
        KnowledgeMigrationJobService jobService = mock(KnowledgeMigrationJobService.class);
        when(jobService.get(404L)).thenThrow(
            new KnowledgeMigrationJobService.MigrationJobException(404, "迁移任务不存在"));
        BotKnowledgeConflictMapper conflictMapper = mock(BotKnowledgeConflictMapper.class);
        KnowledgeMigrationController controller = new KnowledgeMigrationController(
            jobService, reviewService, conflictMapper);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/admin/knowledge/migrations/404"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.msg").value("迁移任务不存在"));
    }

    @Test
    void conflictListingReturnsMigrationErrorsForMissingJob() throws Exception {
        KnowledgeMigrationJobService jobService = mock(KnowledgeMigrationJobService.class);
        when(jobService.get(404L)).thenThrow(
            new KnowledgeMigrationJobService.MigrationJobException(404, "迁移任务不存在"));
        KnowledgeMigrationController controller = new KnowledgeMigrationController(
            jobService, mock(KnowledgeMigrationReviewService.class), mock(BotKnowledgeConflictMapper.class));

        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build()
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/admin/knowledge/migrations/404/conflicts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.msg").value("迁移任务不存在"));
    }

    private MockMvc mvc(KnowledgeMigrationReviewService reviewService) {
        KnowledgeMigrationJobService jobService = mock(KnowledgeMigrationJobService.class);
        BotKnowledgeConflictMapper conflictMapper = mock(BotKnowledgeConflictMapper.class);
        when(conflictMapper.selectList(any())).thenReturn(List.<BotKnowledgeConflict>of());
        KnowledgeMigrationController controller = new KnowledgeMigrationController(
            jobService, reviewService, conflictMapper);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }
}
