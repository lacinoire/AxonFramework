/*
 * Copyright (c) 2010-2022. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.modelling.command;

import org.axonframework.common.lock.Lock;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.common.lock.PessimisticLockFactory;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.messaging.GenericMessage;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.modelling.command.inspection.AggregateModel;
import org.axonframework.modelling.command.inspection.AnnotatedAggregate;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
class LockingRepositoryTest {

    private Repository<StubAggregate> testSubject;
    private EventBus mockEventBus;
    private LockFactory lockFactory;
    private Lock lock;
    private static final Message<?> MESSAGE = new GenericMessage<Object>("test");

    @BeforeEach
    void setUp() {
        mockEventBus = mock(EventBus.class);
        lockFactory = spy(PessimisticLockFactory.usingDefaults());
        when(lockFactory.obtainLock(anyString()))
                .thenAnswer(invocation -> lock = spy((Lock) invocation.callRealMethod()));
        testSubject = InMemoryLockingRepository.builder().lockFactory(lockFactory).eventStore(mockEventBus).build();
        testSubject = spy(testSubject);
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @AfterEach
    void tearDown() {
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    void testStoreNewAggregate() throws Exception {
        startAndGetUnitOfWork();
        StubAggregate aggregate = new StubAggregate();
        testSubject.newInstance(() -> aggregate).execute(StubAggregate::doSomething);
        CurrentUnitOfWork.commit();

        verify(lockFactory).obtainLock(aggregate.getIdentifier());
        verify(mockEventBus).publish(isA(EventMessage.class));
    }

    @Test
    public void testLoadOrCreateAggregate() throws Exception {
        startAndGetUnitOfWork();
        Aggregate<StubAggregate> createdAggregate = testSubject.loadOrCreate("newAggregate", StubAggregate::new);
        verify(lockFactory).obtainLock("newAggregate");

        Aggregate<StubAggregate> loadedAggregate = testSubject.loadOrCreate("newAggregate", StubAggregate::new);
        assertEquals(createdAggregate.identifier(), loadedAggregate.identifier());
        CurrentUnitOfWork.commit();
        verify(lock).release();
    }

    @Test
    void testLoadAndStoreAggregate() throws Exception {
        startAndGetUnitOfWork();
        StubAggregate aggregate = new StubAggregate();
        testSubject.newInstance(() -> aggregate).execute(StubAggregate::doSomething);
        verify(lockFactory).obtainLock(aggregate.getIdentifier());
        CurrentUnitOfWork.commit();
        verify(lock).release();
        reset(lockFactory);

        startAndGetUnitOfWork();
        Aggregate<StubAggregate> loadedAggregate = testSubject.load(aggregate.getIdentifier(), 0L);
        verify(lockFactory).obtainLock(aggregate.getIdentifier());

        loadedAggregate.execute(StubAggregate::doSomething);
        CurrentUnitOfWork.commit();

        verify(mockEventBus, times(2)).publish(any(EventMessage.class));
        verify(lock).release();
    }

    @Test
    void testLoadAndStoreAggregate_LockReleasedOnException() throws Exception {
        startAndGetUnitOfWork();
        StubAggregate aggregate = new StubAggregate();

        testSubject.newInstance(() -> aggregate).execute(StubAggregate::doSomething);
        verify(lockFactory).obtainLock(aggregate.getIdentifier());
        CurrentUnitOfWork.commit();
        verify(lock).release();
        reset(lockFactory);

        startAndGetUnitOfWork();
        testSubject.load(aggregate.getIdentifier(), 0L);
        verify(lockFactory).obtainLock(aggregate.getIdentifier());

        CurrentUnitOfWork.get().onPrepareCommit(u -> {
            throw new RuntimeException("Mock Exception");
        });
        try {
            CurrentUnitOfWork.commit();
            fail("Expected exception to be thrown");
        } catch (RuntimeException e) {
            assertEquals("Mock Exception", e.getMessage());
        }

        // make sure the lock is released
        verify(lock).release();
    }

    @Test
    void testLoadAndStoreAggregate_PessimisticLockReleasedOnException() throws Exception {
        lockFactory = spy(PessimisticLockFactory.usingDefaults());
        testSubject = InMemoryLockingRepository.builder().lockFactory(lockFactory).eventStore(mockEventBus).build();
        testSubject = spy(testSubject);

        // we do the same test, but with a pessimistic lock, which has a different way of "re-acquiring" a lost lock
        startAndGetUnitOfWork();
        StubAggregate aggregate = new StubAggregate();
        when(lockFactory.obtainLock(aggregate.getIdentifier()))
                .thenAnswer(invocation -> lock = spy((Lock) invocation.callRealMethod()));
        testSubject.newInstance(() -> aggregate).execute(StubAggregate::doSomething);
        verify(lockFactory).obtainLock(aggregate.getIdentifier());
        CurrentUnitOfWork.commit();
        verify(lock).release();
        reset(lockFactory);

        startAndGetUnitOfWork();
        testSubject.load(aggregate.getIdentifier(), 0L);
        verify(lockFactory).obtainLock(aggregate.getIdentifier());

        CurrentUnitOfWork.get().onPrepareCommit(u -> {
            throw new RuntimeException("Mock Exception");
        });

        try {
            CurrentUnitOfWork.commit();
            fail("Expected exception to be thrown");
        } catch (RuntimeException e) {
            assertEquals("Mock Exception", e.getMessage());
        }

        // make sure the lock is released
        verify(lock).release();
    }

    private UnitOfWork<?> startAndGetUnitOfWork() {
        UnitOfWork<?> uow = DefaultUnitOfWork.startAndGet(MESSAGE);
        return uow;
    }

    private static class InMemoryLockingRepository extends LockingRepository<StubAggregate, Aggregate<StubAggregate>> {

        private final EventBus eventBus;
        private final AggregateModel<StubAggregate> aggregateModel;
        private Map<Object, Aggregate<StubAggregate>> store = new HashMap<>();
        private int saveCount;

        private InMemoryLockingRepository(Builder builder) {
            super(builder);
            this.eventBus = builder.eventStore;
            this.aggregateModel = builder.buildAggregateModel();
        }

        public static Builder builder() {
            return new Builder();
        }

        @Override
        protected void doSaveWithLock(Aggregate<StubAggregate> aggregate) {
            store.put(aggregate.identifierAsString(), aggregate);
            saveCount++;
        }

        @Override
        protected void doDeleteWithLock(Aggregate<StubAggregate> aggregate) {
            store.remove(aggregate.identifierAsString());
            saveCount++;
        }

        @Override
        protected Aggregate<StubAggregate> doLoadWithLock(String aggregateIdentifier, Long expectedVersion) {
            if (!store.containsKey(aggregateIdentifier)) {
                throw new AggregateNotFoundException(aggregateIdentifier,
                                                     "Aggregate not found");
            }
            return store.get(aggregateIdentifier);
        }

        @Override
        protected Aggregate<StubAggregate> doCreateNewForLock(Callable<StubAggregate> factoryMethod) throws Exception {
            return AnnotatedAggregate.initialize(factoryMethod, aggregateModel, eventBus);
        }

        public int getSaveCount() {
            return saveCount;
        }

        public void resetSaveCount() {
            saveCount = 0;
        }

        private static class Builder extends LockingRepository.Builder<StubAggregate> {

            private EventBus eventStore;

            private Builder() {
                super(StubAggregate.class);
            }

            @Override
            public Builder lockFactory(LockFactory lockFactory) {
                super.lockFactory(lockFactory);
                return this;
            }

            public Builder eventStore(EventBus eventStore) {
                this.eventStore = eventStore;
                return this;
            }

            public InMemoryLockingRepository build() {
                return new InMemoryLockingRepository(this);
            }
        }
    }
}
