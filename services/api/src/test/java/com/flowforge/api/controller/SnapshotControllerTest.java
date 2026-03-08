package com.flowforge.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowforge.api.service.JobDispatcher;
import com.flowforge.common.service.MetadataService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SnapshotController.class)
@org.springframework.test.context.ContextConfiguration(classes = WebMvcTestConfig.class)
class SnapshotControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MetadataService metadataService;
    @MockitoBean private JobDispatcher jobDispatcher;

    @Test
    void createBaselineSnapshotReturns202WithJobId() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(metadataService.createJob(eq("SNAPSHOT"), any())).thenReturn(jobId);

        mockMvc.perform(post("/api/v1/snapshots/master")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repoUrl": "https://github.com/org/repo", "githubToken": "ghp_xxx"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"));

        verify(jobDispatcher).dispatch(eq("SNAPSHOT"), eq(jobId), any());
    }

    @Test
    void createBaselineSnapshotRejects400WhenRepoUrlBlank() throws Exception {
        mockMvc.perform(post("/api/v1/snapshots/master")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repoUrl": "", "githubToken": "ghp_xxx"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void refreshSnapshotReturns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(metadataService.createJob(eq("SNAPSHOT_REFRESH"), any())).thenReturn(jobId);

        mockMvc.perform(post("/api/v1/snapshots/refresh"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }
}
