package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.port.ContractQueryPort;
import com.fuquan.audit.contract.application.port.ContractReviewSessionQueryPort;
import com.fuquan.audit.contract.domain.ReviewSessionStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContractReadQueryObservabilityTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void contractListLogsOnlyPresenceAndResultMetadata() {
        ContractQueryPort port = mock(ContractQueryPort.class);
        FlowDebugLogger flowDebug = mock(FlowDebugLogger.class);
        ContractListQuery query = new ContractListQuery(2, 20, "READY_FOR_REVIEW", "sensitive name");
        when(port.count(query)).thenReturn(0L);

        ContractPage result = new ContractQueryService(port, flowDebug).list(query);

        assertThat(result.items()).isEmpty();
        verify(port, never()).findPage(query);
        ArgumentCaptor<Map<String, ?>> fields = ArgumentCaptor.forClass((Class) Map.class);
        verify(flowDebug).log(
                eq("CONTRACT_LIST_QUERY"),
                eq("CONTRACT"),
                eq("APPLICATION"),
                eq("listContracts"),
                fields.capture());
        assertSafeQueryFields(fields.getValue(), 2, 20, true, true);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reviewSessionTaskListLogsOnlyPresenceAndResultMetadata() {
        ContractReviewSessionQueryPort port = mock(ContractReviewSessionQueryPort.class);
        FlowDebugLogger flowDebug = mock(FlowDebugLogger.class);
        ReviewSessionTaskQuery query = new ReviewSessionTaskQuery(
                1, 10, ReviewSessionStatus.READY, "sensitive supplier");
        when(port.count(query)).thenReturn(0L);

        ReviewSessionTaskPage result =
                new ContractReviewSessionQueryService(port, flowDebug).list(query);

        assertThat(result.items()).isEmpty();
        verify(port, never()).findPage(query);
        ArgumentCaptor<Map<String, ?>> fields = ArgumentCaptor.forClass((Class) Map.class);
        verify(flowDebug).log(
                eq("REVIEW_SESSION_TASK_LIST_QUERY"),
                eq("CONTRACT"),
                eq("APPLICATION"),
                eq("listReviewSessionTasks"),
                fields.capture());
        assertSafeQueryFields(fields.getValue(), 1, 10, true, true);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void failedDatabaseQueryStillEmitsSafeDiagnosticMetadata() {
        ContractReviewSessionQueryPort port = mock(ContractReviewSessionQueryPort.class);
        FlowDebugLogger flowDebug = mock(FlowDebugLogger.class);
        ReviewSessionTaskQuery query = new ReviewSessionTaskQuery(1, 20, null, "private contract");
        when(port.count(query)).thenThrow(new IllegalStateException("raw database detail"));

        assertThatThrownBy(() -> new ContractReviewSessionQueryService(port, flowDebug).list(query))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Map<String, ?>> fields = ArgumentCaptor.forClass((Class) Map.class);
        verify(flowDebug).log(
                eq("REVIEW_SESSION_TASK_LIST_QUERY"),
                eq("CONTRACT"),
                eq("APPLICATION"),
                eq("listReviewSessionTasks"),
                fields.capture());
        assertThat(fields.getValue().get("statusPresent")).isEqualTo(false);
        assertThat(fields.getValue().get("keywordPresent")).isEqualTo(true);
        assertThat(fields.getValue().get("resultCount")).isEqualTo(-1);
        assertThat(fields.getValue().get("total")).isEqualTo(-1L);
        assertThat(fields.getValue()).doesNotContainKeys("keyword", "error", "message");
    }

    private void assertSafeQueryFields(
            Map<String, ?> fields,
            int page,
            int size,
            boolean statusPresent,
            boolean keywordPresent) {
        assertThat(fields.get("page")).isEqualTo(page);
        assertThat(fields.get("size")).isEqualTo(size);
        assertThat(fields.get("statusPresent")).isEqualTo(statusPresent);
        assertThat(fields.get("keywordPresent")).isEqualTo(keywordPresent);
        assertThat(fields.get("resultCount")).isEqualTo(0);
        assertThat(fields.get("total")).isEqualTo(0L);
        assertThat(fields).doesNotContainKeys("keyword", "status");
        assertThat((Long) fields.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }
}
