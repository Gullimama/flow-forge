package com.flowforge.api.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowforge.common.entity.JobStatusEnum;
import com.flowforge.common.service.MetadataService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
class InProcessJobDispatcherTest {

    @Mock private MetadataService metadataService;
    @Mock private ApplicationContext applicationContext;

    @Test
    void dispatchExecutesSnapshotWorkerOnVirtualThread() throws Exception {
        var worker = mock(Runnable.class);
        when(applicationContext.getBean("snapshotWorker", Runnable.class)).thenReturn(worker);
        var dispatcher = new InProcessJobDispatcher(applicationContext, metadataService);
        UUID jobId = UUID.randomUUID();

        dispatcher.dispatch("SNAPSHOT", jobId, Map.of("repoUrl", "https://github.com/org/repo"));

        Thread.sleep(500);
        verify(metadataService).updateJobStatus(jobId, JobStatusEnum.RUNNING, 0.0f);
        verify(worker).run();
        verify(metadataService).updateJobStatus(jobId, JobStatusEnum.COMPLETED, 100.0f);
    }

    @Test
    void dispatchMarksJobFailedForUnknownType() throws Exception {
        var dispatcher = new InProcessJobDispatcher(applicationContext, metadataService);
        UUID jobId = UUID.randomUUID();

        dispatcher.dispatch("UNKNOWN_TYPE", jobId, Map.of());

        Thread.sleep(500);
        verify(metadataService).updateJobStatus(jobId, JobStatusEnum.FAILED, -1.0f);
    }

    @Test
    void dispatchMarksJobFailedOnWorkerException() throws Exception {
        var worker = mock(Runnable.class);
        doThrow(new RuntimeException("boom")).when(worker).run();
        when(applicationContext.getBean("snapshotWorker", Runnable.class)).thenReturn(worker);
        var dispatcher = new InProcessJobDispatcher(applicationContext, metadataService);
        UUID jobId = UUID.randomUUID();

        dispatcher.dispatch("SNAPSHOT", jobId, Map.of());

        Thread.sleep(500);
        verify(metadataService).updateJobStatus(jobId, JobStatusEnum.FAILED, -1.0f);
    }
}
