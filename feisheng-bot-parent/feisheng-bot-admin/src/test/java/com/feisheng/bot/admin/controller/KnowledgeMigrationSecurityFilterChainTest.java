package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.config.SecurityConfig;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.filter.JwtAuthFilter;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService;
import com.feisheng.bot.admin.service.KnowledgeMigrationJobService;
import com.feisheng.bot.admin.service.KnowledgeMigrationReviewService;
import com.feisheng.bot.admin.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = KnowledgeMigrationController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class))
@ContextConfiguration(classes = {
    KnowledgeMigrationController.class, SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class
})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class})
@TestPropertySource(properties = "jwt.secret=01234567890123456789012345678901")
class KnowledgeMigrationSecurityFilterChainTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private SysUserMapper userMapper;

    @MockBean
    private KnowledgeMigrationJobService jobService;

    @MockBean
    private KnowledgeMigrationReviewService reviewService;

    @MockBean
    private BotKnowledgeConflictMapper conflictMapper;

    @MockBean
    private KnowledgeDocumentReleaseService releaseService;

    @BeforeEach
    void userCanOnlyViewMigrations() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("viewer");
        user.setStatus(1);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(userMapper.selectRolesByUserId(7L)).thenReturn(List.of());
        when(userMapper.selectPermissionsByUserId(7L)).thenReturn(List.of("knowledge:migration:view"));
    }

    @Test
    void viewOnlyUserCannotConfirmOrSwitchMigration() throws Exception {
        String token = jwtUtil.generateToken(7L, "viewer");

        mvc.perform(post("/api/admin/knowledge/migrations/1/review/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/knowledge/migrations/1/switch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());

        verifyNoInteractions(reviewService, releaseService);
    }
}
