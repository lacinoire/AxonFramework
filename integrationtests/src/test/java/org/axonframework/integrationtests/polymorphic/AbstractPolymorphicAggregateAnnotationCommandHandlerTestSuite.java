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

package org.axonframework.integrationtests.polymorphic;

import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.commandhandling.NoHandlerForCommandException;
import org.axonframework.commandhandling.SimpleCommandBus;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.common.transaction.Transaction;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.modelling.command.AggregateAnnotationCommandHandler;
import org.axonframework.modelling.command.Repository;
import org.axonframework.modelling.command.inspection.AggregateModel;
import org.axonframework.modelling.command.inspection.AggregateModellingException;
import org.axonframework.modelling.command.inspection.AnnotatedAggregateMetaModelFactory;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Persistence;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests aggregate polymorphism.
 *
 * @author Milan Savic
 */
public abstract class AbstractPolymorphicAggregateAnnotationCommandHandlerTestSuite {

    private CommandBus commandBus;
    private CommandGateway commandGateway;
    private Repository<ParentAggregate> repository;
    private EntityManager entityManager;
    private TransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        Map<String, String> persistenceProperties = new HashMap<>(2);
        persistenceProperties.put("hibernate.connection.url", "jdbc:hsqldb:mem:axontest");
        persistenceProperties.put("hibernate.hbm2ddl.auto", "create-drop");
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("polymorphic", persistenceProperties);
        entityManager = emf.createEntityManager();

        transactionManager = new EntityManagerTransactionManager(entityManager);

        commandBus = SimpleCommandBus.builder()
                                     .transactionManager(transactionManager)
                                     .build();
        commandGateway = DefaultCommandGateway.builder()
                                              .commandBus(commandBus)
                                              .build();

        AggregateModel<ParentAggregate> model = new AnnotatedAggregateMetaModelFactory()
                .createModel(ParentAggregate.class,
                             new HashSet<>(asList(Child1Aggregate.class, Child2Aggregate.class)));

        repository = repository(ParentAggregate.class, model, entityManager);

        AggregateAnnotationCommandHandler<ParentAggregate> ch = AggregateAnnotationCommandHandler.<ParentAggregate>builder()
                .aggregateType(ParentAggregate.class)
                .aggregateModel(model)
                .repository(repository)
                .build();
        ch.subscribe(commandBus);
    }

    public abstract <T> Repository<T> repository(Class<T> aggregateType,
                                                 AggregateModel<T> model,
                                                 EntityManager entityManager);

    @AfterEach
    void tearDown() {
        entityManager.close();
    }

    @Test
    void createChild1() {
        String id = commandGateway.sendAndWait(new CreateChild1Command("123"));
        String result = commandGateway.sendAndWait(new CommonCommand(id));
        assertEquals("Child1Aggregate123", result);
    }

    @Test
    void createChild2() {
        String id = commandGateway.sendAndWait(new CreateChild2Command("123"));
        String result = commandGateway.sendAndWait(new CommonCommand(id));
        assertEquals("Child2Aggregate123", result);
    }

    @Test
    void factoryCreate() {
        String id = commandGateway.sendAndWait(new CreateChildFactoryCommand("123", 1));
        String result = commandGateway.sendAndWait(new CommonCommand(id));
        assertEquals("Child1Aggregate123", result);

        id = commandGateway.sendAndWait(new CreateChildFactoryCommand("456", 2));
        result = commandGateway.sendAndWait(new CommonCommand(id));
        assertEquals("Child2Aggregate456", result);
    }

    @Test
    void child1OnlyCommandOnAggregate2() {
        String c1Id = commandGateway.sendAndWait(new CreateChild1Command("123"));
        String c2Id = commandGateway.sendAndWait(new CreateChild2Command("456"));
        String result = commandGateway.sendAndWait(new Child1OnlyCommand(c1Id));
        assertEquals("Child1Aggregate123", result);
        assertThrows(NoHandlerForCommandException.class, () -> commandGateway.sendAndWait(new Child1OnlyCommand(c2Id)));
    }

    @Test
    void parentEventAppliedFromChild() {
        String id = commandGateway.sendAndWait(new CreateChild1Command("123"));
        commandGateway.sendAndWait(new FireParentEventCommand(id));
        assertAggregateState(id, "parent123");
    }

    @Test
    void childEventAppliedFromParent() {
        String c1Id = commandGateway.sendAndWait(new CreateChild1Command("123"));
        String c2Id = commandGateway.sendAndWait(new CreateChild2Command("456"));
        commandGateway.sendAndWait(new FireChildEventCommand(c1Id));
        commandGateway.sendAndWait(new FireChildEventCommand(c2Id));
        assertAggregateState(c1Id, "child1123");
        assertAggregateState(c2Id, "child2456");
    }

    @Test
    void commandInterceptedByParentHandledByChild() {
        String c1Id = commandGateway.sendAndWait(new CreateChild1Command("123"));
        String c2Id = commandGateway.sendAndWait(new CreateChild2Command("456"));
        String child1Result = commandGateway.sendAndWait(new InterceptedByParentCommand(c1Id, "state"));
        String child2Result = commandGateway.sendAndWait(new InterceptedByParentCommand(c2Id, "state"));
        assertEquals("stateInterceptedByParentHandledByChild1", child1Result);
        assertEquals("stateInterceptedByParentHandledByChild2", child2Result);
    }

    @Test
    void commandInterceptedByChildHandledByParent() {
        String c1Id = commandGateway.sendAndWait(new CreateChild1Command("123"));
        String c2Id = commandGateway.sendAndWait(new CreateChild2Command("456"));
        String child1Result = commandGateway.sendAndWait(new InterceptedByChildCommand(c1Id, "state"));
        String child2Result = commandGateway.sendAndWait(new InterceptedByChildCommand(c2Id, "state"));
        assertEquals("stateInterceptedByChild1HandledByParent", child1Result);
        assertEquals("stateInterceptedByChild2HandledByParent", child2Result);
    }

    @Test
    void abstractCommandHandler() {
        String c1Id = commandGateway.sendAndWait(new CreateChild1Command("123"));
        String c2Id = commandGateway.sendAndWait(new CreateChild2Command("456"));
        String child1Result = commandGateway.sendAndWait(new AbstractCommandHandlerCommand(c1Id));
        String child2Result = commandGateway.sendAndWait(new AbstractCommandHandlerCommand(c2Id));
        assertEquals("handledByChild1", child1Result);
        assertEquals("handledByChild2", child2Result);
    }

    @Test
    void inspectionOfAbstractAggregateWithCommandHandlerOnConstructor() {
        assertThrows(AggregateModellingException.class,
                     () -> new AnnotatedAggregateMetaModelFactory()
                             .createModel(AbstractAggregateWithCommandHandlerConstructor.class));
    }

    @Test
    void inspectionOfPolymorphicAggregateWithSameCreationalCommandHandlers() {
        assertThrows(AggregateModellingException.class,
                     () -> new AnnotatedAggregateMetaModelFactory()
                             .createModel(A.class, new HashSet<>(asList(B.class, C.class))));
    }

    @Test
    void creationOfPolymorphicAggregate() {
        AggregateModel<SimpleAggregate> model = new AnnotatedAggregateMetaModelFactory()
                .createModel(SimpleAggregate.class);

        Repository<SimpleAggregate> repository = repository(SimpleAggregate.class, model, entityManager);

        AggregateAnnotationCommandHandler<SimpleAggregate> ch = AggregateAnnotationCommandHandler.<SimpleAggregate>builder()
                .aggregateType(SimpleAggregate.class)
                .aggregateModel(model)
                .repository(repository)
                .build();
        ch.subscribe(commandBus);

        String simpleAggregateId = "id";
        String child1AggregateId = "child1" + simpleAggregateId;
        commandGateway.sendAndWait(new CreateSimpleAggregateCommand(simpleAggregateId));
        String result = commandGateway.sendAndWait(new CommonCommand(child1AggregateId));
        assertEquals("Child1Aggregate" + child1AggregateId, result);
    }

    private void assertAggregateState(String aggregateId, String expectedState) {
        DefaultUnitOfWork<Message<?>> uow = DefaultUnitOfWork.startAndGet(null);
        uow.attachTransaction(transactionManager);
        String state = uow.executeWithResult(() -> {
            AtomicReference<String> rv = new AtomicReference<>();
            repository.load(aggregateId).execute(a -> rv.set(a.getState()));
            return rv.get();
        }).getPayload();
        assertEquals(expectedState, state);
    }

    private static abstract class AbstractAggregateWithCommandHandlerConstructor {

        @CommandHandler
        public AbstractAggregateWithCommandHandlerConstructor(String cmd) {
        }
    }

    private static abstract class A {

    }

    private static class B extends A {

        @CommandHandler
        public B(String cmd) {
        }
    }

    private static class C extends A {

        @CommandHandler
        public C(String cmd) {
        }
    }

    private static class EntityManagerTransactionManager implements TransactionManager {

        private final EntityManager em;

        public EntityManagerTransactionManager(EntityManager em) {
            this.em = em;
        }

        @Override
        public Transaction startTransaction() {
            EntityTransaction tx = em.getTransaction();
            if (tx.isActive()) {
                return new Transaction() {
                    @Override
                    public void commit() {
                    }

                    @Override
                    public void rollback() {
                    }
                };
            }
            tx.begin();
            return new Transaction() {
                @Override
                public void commit() {
                    tx.commit();
                }

                @Override
                public void rollback() {
                    tx.rollback();
                }
            };
        }
    }
}
