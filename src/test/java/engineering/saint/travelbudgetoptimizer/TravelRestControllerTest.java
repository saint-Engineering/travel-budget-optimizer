package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TravelRestControllerTest {
    private TravelOptimizationEngine engine;
    private TravelRestController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        engine = mock(TravelOptimizationEngine.class);
        controller = new TravelRestController(engine, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(engine.getProgress("known")).thenReturn(Optional.of(
                new TravelOptimizationEngine.JobProgress("Fase 1", null, 0)));
    }

    @AfterEach
    void tearDown() {
        controller.shutdown();
    }

    @Test
    void unknownJobsReturn404WithoutReservingStreams() throws Exception {
        mvc.perform(get("/api/viaggi/status/unknown")).andExpect(status().isNotFound());
        assertEquals(TravelRestController.MAX_STREAMS, slots().availablePermits());
        assertTrue(scheduler().getQueue().isEmpty());
    }

    @Test
    void capsConnectionsAndUsesOnlyTwoSchedulerWorkers() throws Exception {
        for (int i = 0; i < TravelRestController.MAX_STREAMS; i++) {
            controller.status("known");
        }
        mvc.perform(get("/api/viaggi/status/known")).andExpect(status().isTooManyRequests());
        assertEquals(0, slots().availablePermits());
        assertEquals(2, scheduler().getCorePoolSize());
        assertTrue(scheduler().getPoolSize() <= 2);
    }

    @Test
    void sendsExistingEventFormatAndReleasesCompletedStream() throws Exception {
        when(engine.getProgress("known")).thenReturn(Optional.of(
                new TravelOptimizationEngine.JobProgress("COMPLETED", "{\"opzioniViaggio\":[]}", 0)));
        MvcResult result = mvc.perform(get("/api/viaggi/status/known"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5_000);
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("event:status\ndata:COMPLETED\n\nevent:result\ndata:{\"opzioniViaggio\":[]}\n\n"));
        assertEquals(TravelRestController.MAX_STREAMS, slots().availablePermits());
        assertTrue(scheduler().getQueue().isEmpty());
    }

    @Test
    void expiryDuringStreamingNotifiesClientAndReleasesSlot() throws Exception {
        when(engine.getProgress("known")).thenReturn(
                Optional.of(new TravelOptimizationEngine.JobProgress("Fase 1", null, 0)),
                Optional.empty());
        MvcResult result = mvc.perform(get("/api/viaggi/status/known"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5_000);
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("event:timeout\ndata:Job scaduto\n\n"));
        assertEquals(TravelRestController.MAX_STREAMS, slots().availablePermits());
        assertTrue(scheduler().getQueue().isEmpty());
    }

    @Test
    void timeoutReleasesSlotExactlyOnce() throws Exception {
        MvcResult result = mvc.perform(get("/api/viaggi/status/known"))
                .andExpect(request().asyncStarted()).andReturn();
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        AsyncEvent event = new AsyncEvent(context);
        for (var listener : context.getListeners()) {
            listener.onTimeout(event);
            listener.onComplete(event);
        }
        assertEquals(TravelRestController.MAX_STREAMS, slots().availablePermits());
        assertTrue(scheduler().getQueue().isEmpty());
    }

    @Test
    void disconnectReleasesSlotAndCancelsPolling() throws Exception {
        MvcResult result = mvc.perform(get("/api/viaggi/status/known"))
                .andExpect(request().asyncStarted()).andReturn();
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        AsyncEvent event = new AsyncEvent(context, new java.io.IOException("disconnected"));
        for (var listener : context.getListeners()) {
            listener.onError(event);
        }
        assertEquals(TravelRestController.MAX_STREAMS, slots().availablePermits());
        assertTrue(scheduler().getQueue().isEmpty());
    }

    @Test
    void schedulerRejectionReleasesReservedSlot() throws Exception {
        controller.shutdown();
        assertTrue(scheduler().awaitTermination(5, TimeUnit.SECONDS));
        assertThrows(ResponseStatusException.class, () -> controller.status("known"));
        assertEquals(TravelRestController.MAX_STREAMS, slots().availablePermits());
    }

    private Semaphore slots() {
        return (Semaphore) ReflectionTestUtils.getField(controller, "streamSlots");
    }

    private ScheduledThreadPoolExecutor scheduler() {
        return (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(controller, "scheduler");
    }
}
