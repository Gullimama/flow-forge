package com.flowforge.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

@WebMvcTest(controllers = LogIngestionController.class)
@org.springframework.test.context.ContextConfiguration(classes = WebMvcTestConfig.class)
class LogIngestionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MetadataService metadataService;
    @MockitoBean private JobDispatcher jobDispatcher;

    @Test
    void ingestLogsReturns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(metadataService.createJob(eq("LOG_INGEST"), any())).thenReturn(jobId);

        mockMvc.perform(post("/api/v1/logs/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "storageAccount": "myaccount",
                      "container": "logs",
                      "prefix": "2024/",
                      "mode": "FULL"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void ingestLogsRejects400WhenModeNull() throws Exception {
        mockMvc.perform(post("/api/v1/logs/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"storageAccount": "a", "container": "c", "prefix": "p"}
                    """))
            .andExpect(status().isBadRequest());
    }
}
