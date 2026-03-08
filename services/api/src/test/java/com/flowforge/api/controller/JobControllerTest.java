package com.flowforge.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowforge.common.entity.JobEntity;
import com.flowforge.common.entity.JobStatusEnum;
import com.flowforge.common.service.MetadataService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = JobController.class)
@org.springframework.test.context.ContextConfiguration(classes = WebMvcTestConfig.class)
class JobControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MetadataService metadataService;

    @Test
    void getJobStatusReturns200() throws Exception {
        UUID jobId = UUID.randomUUID();
        var job = new JobEntity();
        job.setJobId(jobId);
        job.setJobType("SNAPSHOT");
        job.setStatus(JobStatusEnum.RUNNING);
        job.setProgressPct(50.0f);
        job.setCreatedAt(Instant.now());
        when(metadataService.getJob(jobId)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/v1/jobs/{id}", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.progressPct").value(50.0));
    }

    @Test
    void getJobStatusReturns404WhenNotFound() throws Exception {
        when(metadataService.getJob(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }
}
