package com.pathaopay.miniwallet.service.addmoney;

import com.pathaopay.miniwallet.client.AddMoneyQueueClient;
import com.pathaopay.miniwallet.client.config.properties.AddMoneyMessagingProperties;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddMoneyDispatcherTest {

    private final AddMoneyRequestRepository repository = mock(AddMoneyRequestRepository.class);
    private final AddMoneyQueueClient queue = mock(AddMoneyQueueClient.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();
    private AddMoneyDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new AddMoneyDispatcher(repository, queue, new AddMoneyMessagingProperties(null, 0, null, null),
                new TransactionTemplate(transactionManager));
        when(repository.lockDispatchable(anyInt(), any(), any())).thenReturn(List.of(first, second, third));
    }

    @Test
    void claimsTheBatchAndCommitsBeforeTalkingToTheBroker() {
        assertEquals(3, dispatcher.dispatch());

        InOrder order = inOrder(repository, transactionManager, queue);
        order.verify(repository).markPublished(List.of(first, second, third));
        order.verify(transactionManager).commit(any());
        order.verify(queue).publish(first);
        order.verify(queue).publish(second);
        order.verify(queue).publish(third);
        verify(repository, never()).markUnpublished(any());
    }

    @Test
    void releasesTheRequestsItCouldNotPublishWhenTheBrokerFails() {
        doThrow(new IllegalStateException("broker down")).when(queue).publish(second);

        assertThrows(IllegalStateException.class, dispatcher::dispatch);

        verify(queue).publish(first);
        verify(queue, never()).publish(third);
        verify(repository).markUnpublished(List.of(second, third));
    }

    @Test
    void stillReportsTheBrokerErrorWhenTheReleaseFailsToo() {
        doThrow(new IllegalStateException("broker down")).when(queue).publish(first);
        doThrow(new IllegalStateException("database down")).when(repository).markUnpublished(any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, dispatcher::dispatch);

        assertEquals("broker down", thrown.getMessage());
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        when(repository.lockDispatchable(anyInt(), any(), any())).thenReturn(List.of());

        assertEquals(0, dispatcher.dispatch());

        verify(queue, never()).publish(any());
    }
}
