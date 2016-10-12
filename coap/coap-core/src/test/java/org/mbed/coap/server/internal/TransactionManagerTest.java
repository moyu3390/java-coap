/*
 * Copyright (C) 2011-2016 ARM Limited. All rights reserved.
 */
package org.mbed.coap.server.internal;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;
import static protocolTests.utils.CoapPacketBuilder.*;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.mbed.coap.exception.TooManyRequestsForEndpointException;
import org.mbed.coap.packet.CoapPacket;
import org.mbed.coap.packet.Code;
import org.mbed.coap.server.CoapServerObserve;
import org.mbed.coap.transport.InMemoryTransport;
import org.mbed.coap.transport.TransportContext;
import org.mbed.coap.utils.Callback;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Created by szymon.
 */
public class TransactionManagerTest {
    private static final InetSocketAddress REMOTE_ADR = InMemoryTransport.createAddress(5683);
    private static final InetSocketAddress REMOTE_ADR2 = InMemoryTransport.createAddress(5685);
    private TransactionManager transMgr;

    @BeforeMethod
    public void setUp() throws Exception {
        transMgr = new TransactionManager();
    }

    @Test
    public void test_findMatchForSeparateResponse() throws Exception {
        CoapPacket request = newCoapPacket(REMOTE_ADR).mid(1).con().get().token(123).uriPath("/test").build();
        CoapPacket requestInactive = newCoapPacket(REMOTE_ADR).mid(2).con().get().token(456).uriPath("/test").build();
        // active
        CoapTransaction activeTrans = new CoapTransaction(mock(Callback.class), request, mock(CoapServerObserve.class), TransportContext.NULL).makeActiveForTests();
        assertTrue(transMgr.addTransactionAndGetReadyToSend(activeTrans));

        CoapTransaction inactiveTrans = new CoapTransaction(mock(Callback.class), requestInactive, mock(CoapServerObserve.class), TransportContext.NULL);
        assertFalse(transMgr.addTransactionAndGetReadyToSend(inactiveTrans));

        // active match
        assertMatch(newCoapPacket(REMOTE_ADR).mid(12).con(Code.C205_CONTENT).token(123).build(), activeTrans);
        assertNoMatch(newCoapPacket(REMOTE_ADR).mid(12).non(Code.C205_CONTENT).token(123).build());

        // inactive match
        assertNoMatch(newCoapPacket(REMOTE_ADR).mid(13).con(Code.C205_CONTENT).token(456).build());

        //failures

        //message type: ACK
        assertNoMatch(newCoapPacket(REMOTE_ADR).mid(12).ack(Code.C205_CONTENT).token(123).build());

        //wrong token
        assertNoMatch(newCoapPacket(REMOTE_ADR).mid(12).con(Code.C205_CONTENT).token(12).build());

        //wrong address
        assertNoMatch(newCoapPacket(InMemoryTransport.createAddress(61616)).mid(12).con(Code.C205_CONTENT).token(123).build());

        //another request
        assertNoMatch(newCoapPacket(REMOTE_ADR).mid(12).con().get().token(123).build());
    }

    @Test
    public void test_findMatchForSeparateResponse_emptyToken() throws Exception {
        CoapPacket request = newCoapPacket(REMOTE_ADR).mid(1).con().get().uriPath("/test").build();
        assertTrue(transMgr.addTransactionAndGetReadyToSend(new CoapTransaction(mock(Callback.class), request, mock(CoapServerObserve.class), TransportContext.NULL)));

        assertNoMatch(newCoapPacket(REMOTE_ADR).mid(12).con(Code.C205_CONTENT).build());
    }

    @Test
    public void test_addMoreThanOneTransactionForEndpoint() throws Exception {
        CoapPacket ep1Request1 = newCoapPacket(REMOTE_ADR).mid(11).con().get().uriPath("/test1").build();
        CoapPacket ep1Request2 = newCoapPacket(REMOTE_ADR).mid(12).con().get().uriPath("/test1").build();
        CoapPacket ep2Request1 = newCoapPacket(REMOTE_ADR2).mid(13).con().get().uriPath("/test2").build();
        CoapPacket ep2Request2 = newCoapPacket(REMOTE_ADR2).mid(14).con().get().uriPath("/test2").build();

        assertTrue(transMgr.addTransactionAndGetReadyToSend(new CoapTransaction(mock(Callback.class), ep1Request1, mock(CoapServerObserve.class), TransportContext.NULL)));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(new CoapTransaction(mock(Callback.class), ep1Request2, mock(CoapServerObserve.class), TransportContext.NULL)));

        assertEquals(transMgr.getNumberOfTransactions(), 2);

        //second ep
        assertTrue(transMgr.addTransactionAndGetReadyToSend(new CoapTransaction(mock(Callback.class), ep2Request1, mock(CoapServerObserve.class), TransportContext.NULL)));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(new CoapTransaction(mock(Callback.class), ep2Request2, mock(CoapServerObserve.class), TransportContext.NULL)));

        assertEquals(transMgr.getNumberOfTransactions(), 4);
    }

    @Test
    public void test_removeExistingActiveTransactionNoOtherTransactionsOngoing() throws Exception {
        CoapPacket ep1Request1 = newCoapPacket(REMOTE_ADR).mid(11).con().get().uriPath("/test1").build();
        CoapTransaction trans = new CoapTransaction(mock(Callback.class), ep1Request1, mock(CoapServerObserve.class), TransportContext.NULL);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(trans));
        assertEquals(transMgr.getNumberOfTransactions(), 1);

        trans.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(trans.getTransactionId()));
        assertEquals(transMgr.getNumberOfTransactions(), 0);
    }

    @Test
    public void test_removeExistingInactiveTransactionNoOtherTransactionOngoing() throws Exception {
        CoapPacket ep1Request1 = newCoapPacket(REMOTE_ADR).mid(11).con().get().uriPath("/test1").build();
        CoapTransaction trans = new CoapTransaction(mock(Callback.class), ep1Request1, mock(CoapServerObserve.class), TransportContext.NULL);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(trans));
        assertEquals(transMgr.getNumberOfTransactions(), 1);

        assertEmpty(transMgr.removeAndLock(trans.getTransactionId()));
        assertEquals(transMgr.getNumberOfTransactions(), 1);

        trans.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(trans.getTransactionId()));
        assertEquals(transMgr.getNumberOfTransactions(), 0);
    }

    @Test
    public void test_removeExistingTransactionOneTransactionOngoing() throws Exception {
        CoapPacket ep1Request1 = newCoapPacket(REMOTE_ADR).mid(11).con().get().uriPath("/test1").build();
        CoapPacket ep1Request2 = newCoapPacket(REMOTE_ADR).mid(12).con().get().uriPath("/test1").build();
        CoapPacket ep2Request1 = newCoapPacket(REMOTE_ADR2).mid(13).con().get().uriPath("/test2").build();
        CoapPacket ep2Request2 = newCoapPacket(REMOTE_ADR2).mid(14).con().get().uriPath("/test2").build();

        CoapTransaction ep1Trans1 = new CoapTransaction(mock(Callback.class), ep1Request1, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep1Trans2 = new CoapTransaction(mock(Callback.class), ep1Request2, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep2Trans1 = new CoapTransaction(mock(Callback.class), ep2Request1, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep2Trans2 = new CoapTransaction(mock(Callback.class), ep2Request2, mock(CoapServerObserve.class), TransportContext.NULL);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(ep1Trans1));
        assertTrue(transMgr.addTransactionAndGetReadyToSend(ep2Trans1));

        assertEquals(transMgr.getNumberOfTransactions(), 2);

        assertFalse(transMgr.addTransactionAndGetReadyToSend(ep2Trans2));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(ep1Trans2));

        assertEquals(transMgr.getNumberOfTransactions(), 4);

        ep2Trans1.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(ep2Trans1.getTransactionId()));
        CoapTransaction next = transMgr.unlockOrRemoveAndGetNext(ep2Trans1.getTransactionId()).get();
        assertNotNull(next);
        assertEquals(next, ep2Trans2);
        assertEquals(transMgr.getNumberOfTransactions(), 3);

        next.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(ep2Trans2.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(ep2Trans2.getTransactionId()));
        assertEquals(transMgr.getNumberOfTransactions(), 2);

        ep1Trans1.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(ep1Trans1.getTransactionId()));
        next = transMgr.unlockOrRemoveAndGetNext(ep1Trans1.getTransactionId()).get();
        assertNotNull(next);
        assertEquals(next, ep1Trans2);
        assertEquals(transMgr.getNumberOfTransactions(), 1);

        next.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(ep1Trans2.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(ep1Trans2.getTransactionId()));
        assertEquals(transMgr.getNumberOfTransactions(), 0);
    }

    @Test
    public void removeNotFoundTransaction() throws Exception {
        CoapPacket ep1Request1 = newCoapPacket(REMOTE_ADR).mid(11).con().get().uriPath("/test1").build();
        CoapPacket ep1Request2 = newCoapPacket(REMOTE_ADR).mid(12).con().get().uriPath("/test1").build();
        CoapPacket ep2Request1 = newCoapPacket(REMOTE_ADR2).mid(13).con().get().uriPath("/test2").build();
        CoapPacket ep2Request2 = newCoapPacket(REMOTE_ADR2).mid(14).con().get().uriPath("/test2").build();
        CoapPacket ep2Request3 = newCoapPacket(REMOTE_ADR2).mid(15).con().get().uriPath("/test2").build();

        CoapTransaction ep1Trans1 = new CoapTransaction(mock(Callback.class), ep1Request1, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep1Trans2 = new CoapTransaction(mock(Callback.class), ep1Request2, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep2Trans1 = new CoapTransaction(mock(Callback.class), ep2Request1, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep2Trans2 = new CoapTransaction(mock(Callback.class), ep2Request2, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep2Trans3 = new CoapTransaction(mock(Callback.class), ep2Request3, mock(CoapServerObserve.class), TransportContext.NULL);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(ep1Trans1));
        assertEmpty(transMgr.removeAndLock(ep1Trans2.getTransactionId()));
        CoapTransaction next = transMgr.unlockOrRemoveAndGetNext(ep1Trans2.getTransactionId()).get(); // not added
        assertEquals(next, ep1Trans1); // return first for this endpoint

        ep1Trans1.makeActiveForTests(); // emulate sending

        assertTrue(transMgr.addTransactionAndGetReadyToSend(ep2Trans1));
        ep2Trans1.makeActiveForTests();
        assertFalse(transMgr.addTransactionAndGetReadyToSend(ep2Trans2));

        assertEquals(transMgr.getNumberOfTransactions(), 3);

        assertEmpty(transMgr.removeAndLock(ep2Trans3.getTransactionId()));
        next = transMgr.unlockOrRemoveAndGetNext(ep2Trans3.getTransactionId()).get(); // not added
        assertEquals(next, ep2Trans1); // return first for this endpoint
        assertEquals(transMgr.getNumberOfTransactions(), 3);


        assertNotEmpty(transMgr.removeAndLock(ep2Trans1.getTransactionId()));
        next = transMgr.unlockOrRemoveAndGetNext(ep2Trans1.getTransactionId()).get();
        assertNotNull(next);
        assertEquals(next, ep2Trans2);
        assertEquals(transMgr.getNumberOfTransactions(), 2);

        ep2Trans2.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(ep2Trans2.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(ep2Trans2.getTransactionId()));
        assertEquals(transMgr.getNumberOfTransactions(), 1);

        assertNotEmpty(transMgr.removeAndLock(ep1Trans1.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(ep1Trans1.getTransactionId()));
        assertEquals(transMgr.getNumberOfTransactions(), 0);
    }

    @Test(expectedExceptions = TooManyRequestsForEndpointException.class)
    public void test_endpointQueueOverflow() throws Exception {
        transMgr.setMaximumEndpointQueueSize(2);

        CoapPacket ep2Request1 = newCoapPacket(REMOTE_ADR2).mid(13).con().get().uriPath("/test2").build();
        CoapPacket ep2Request2 = newCoapPacket(REMOTE_ADR2).mid(14).con().get().uriPath("/test2").build();
        CoapPacket ep2Request3 = newCoapPacket(REMOTE_ADR2).mid(15).con().get().uriPath("/test2").build();

        CoapTransaction ep2Trans1 = new CoapTransaction(mock(Callback.class), ep2Request1, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep2Trans2 = new CoapTransaction(mock(Callback.class), ep2Request2, mock(CoapServerObserve.class), TransportContext.NULL);
        CoapTransaction ep2Trans3 = new CoapTransaction(mock(Callback.class), ep2Request3, mock(CoapServerObserve.class), TransportContext.NULL);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(ep2Trans1));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(ep2Trans2));

        // should throw TooManyRequestsForEndpointException
        transMgr.addTransactionAndGetReadyToSend(ep2Trans3);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void test_tooSmallQueueValue() {
        transMgr.setMaximumEndpointQueueSize(0);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void test_tooBigQueueValue() {
        transMgr.setMaximumEndpointQueueSize(65537);
    }

    @Test
    public void test_normalQueueValue() {
        transMgr.setMaximumEndpointQueueSize(1);
        transMgr.setMaximumEndpointQueueSize(100);
        transMgr.setMaximumEndpointQueueSize(65536);
    }

    @Test
    public void test_queueSorting() throws TooManyRequestsForEndpointException {
        CoapTransaction transLow1 = createTransaction(REMOTE_ADR, 1, CoapTransaction.Priority.LOW);
        CoapTransaction transLow2 = createTransaction(REMOTE_ADR, 2, CoapTransaction.Priority.LOW);
        CoapTransaction transNorm1 = createTransaction(REMOTE_ADR, 3, CoapTransaction.Priority.NORMAL);
        CoapTransaction transNorm2 = createTransaction(REMOTE_ADR, 4, CoapTransaction.Priority.NORMAL);
        CoapTransaction transHi1 = createTransaction(REMOTE_ADR, 5, CoapTransaction.Priority.HIGH);
        CoapTransaction transHi2 = createTransaction(REMOTE_ADR, 6, CoapTransaction.Priority.HIGH);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(transLow1));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transLow2));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transNorm1));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transNorm2));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transHi1));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transHi2));

        assertEquals(6, transMgr.getNumberOfTransactions());

        transLow1.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(transLow1.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transLow1.getTransactionId()).get().makeActiveForTests(), transHi1);

        assertNotEmpty(transMgr.removeAndLock(transHi1.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transHi1.getTransactionId()).get().makeActiveForTests(), transHi2);

        assertNotEmpty(transMgr.removeAndLock(transHi2.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transHi2.getTransactionId()).get().makeActiveForTests(), transNorm1);

        assertNotEmpty(transMgr.removeAndLock(transNorm1.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transNorm1.getTransactionId()).get().makeActiveForTests(), transNorm2);

        assertNotEmpty(transMgr.removeAndLock(transNorm2.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transNorm2.getTransactionId()).get().makeActiveForTests(), transLow2);

        assertNotEmpty(transMgr.removeAndLock(transLow2.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(transLow2.getTransactionId()));

        assertEquals(0, transMgr.getNumberOfTransactions());
    }

    @Test
    public void test_samePriorityInOrder() throws TooManyRequestsForEndpointException {
        CoapTransaction transHi1 = createTransaction(REMOTE_ADR, 1, CoapTransaction.Priority.HIGH);
        CoapTransaction transHi2 = createTransaction(REMOTE_ADR, 2, CoapTransaction.Priority.HIGH);
        CoapTransaction transHi3 = createTransaction(REMOTE_ADR, 3, CoapTransaction.Priority.NORMAL);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(transHi3));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transHi2));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transHi1));

        transHi3.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(transHi3.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transHi3.getTransactionId()).get().makeActiveForTests(), transHi2);

        assertNotEmpty(transMgr.removeAndLock(transHi2.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transHi2.getTransactionId()).get().makeActiveForTests(), transHi1);

        assertNotEmpty(transMgr.removeAndLock(transHi1.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(transHi1.getTransactionId()));

        assertEquals(transMgr.getNumberOfTransactions(), 0);
    }

    @Test
    public void test_nonExistingTransId() throws TooManyRequestsForEndpointException {
        CoapTransaction transLow1 = createTransaction(REMOTE_ADR, 1, CoapTransaction.Priority.LOW);
        CoapTransaction transLow2 = createTransaction(REMOTE_ADR, 2, CoapTransaction.Priority.LOW);
        CoapTransaction transNorm1 = createTransaction(REMOTE_ADR, 3, CoapTransaction.Priority.NORMAL);
        CoapTransaction transNorm2 = createTransaction(REMOTE_ADR, 4, CoapTransaction.Priority.NORMAL);
        CoapTransaction transHi_NOT_ADDED = createTransaction(REMOTE_ADR, 5, CoapTransaction.Priority.HIGH);
        CoapTransaction transHi2 = createTransaction(REMOTE_ADR, 6, CoapTransaction.Priority.HIGH);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(transLow1));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transNorm1));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transLow2));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transNorm2));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(transHi2));

        assertEquals(5, transMgr.getNumberOfTransactions());

        assertEmpty(transMgr.removeAndLock(transHi_NOT_ADDED.getTransactionId())); // not added transHi1

        transLow1.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(transLow1.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transLow1.getTransactionId()).get(), transHi2);

        transHi2.makeActiveForTests(); // emulate sending

        assertNotEmpty(transMgr.removeAndLock(transHi2.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transHi2.getTransactionId()).get(), transNorm1);

        transNorm1.makeActiveForTests();


        assertFalse(transMgr.addTransactionAndGetReadyToSend(transHi_NOT_ADDED));                      // add not added trans

        assertNotEmpty(transMgr.removeAndLock(transNorm1.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transNorm1.getTransactionId()).get(), transHi_NOT_ADDED);

        transHi_NOT_ADDED.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(transHi_NOT_ADDED.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transHi_NOT_ADDED.getTransactionId()).get(), transNorm2);

        transNorm2.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(transNorm2.getTransactionId()));
        assertEquals(transMgr.unlockOrRemoveAndGetNext(transNorm2.getTransactionId()).get(), transLow2);

        transLow2.makeActiveForTests();

        assertNotEmpty(transMgr.removeAndLock(transLow2.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(transLow2.getTransactionId()));

        assertEquals(0, transMgr.getNumberOfTransactions());
    }

    @Test
    public void test_noQueueOverflowOnBlockTransferContinue() throws TooManyRequestsForEndpointException {
        transMgr.setMaximumEndpointQueueSize(2);
        CoapTransaction trans1 = createTransaction(REMOTE_ADR, 1, CoapTransaction.Priority.LOW);
        CoapTransaction trans2 = createTransaction(REMOTE_ADR, 2, CoapTransaction.Priority.LOW);
        CoapTransaction trans3 = createTransaction(REMOTE_ADR, 3, CoapTransaction.Priority.LOW);
        CoapTransaction trans4 = createTransaction(REMOTE_ADR, 4, CoapTransaction.Priority.LOW);

        assertTrue(transMgr.addTransactionAndGetReadyToSend(trans1));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(trans2));
        assertFalse(transMgr.addTransactionAndGetReadyToSend(trans3, true));
        try {
            assertFalse(transMgr.addTransactionAndGetReadyToSend(trans4));
            fail("should be throwh TooManyRequestsForEndpointException");
        } catch (TooManyRequestsForEndpointException e) {
        }
    }

    @Test
    public void test_noQueueRemoveTransaction() {
        CoapTransaction trans1 = createTransaction(REMOTE_ADR, 1, CoapTransaction.Priority.LOW);

        assertEmpty(transMgr.removeAndLock(trans1.getTransactionId()));
        assertEmpty(transMgr.unlockOrRemoveAndGetNext(trans1.getTransactionId()));
    }

    private CoapTransaction createTransaction(InetSocketAddress remote, int mid, CoapTransaction.Priority priority) {
        CoapPacket packet = newCoapPacket(remote).mid(mid).con().get().uriPath("/").build();
        return new CoapTransaction(mock(Callback.class), packet, mock(CoapServerObserve.class), TransportContext.NULL, priority);
    }

    private void assertNoMatch(CoapPacket packet) {
        assertEmpty(transMgr.findMatchAndRemoveForSeparateResponse(packet));
    }

    private void assertMatch(CoapPacket packet, CoapTransaction expected) {
        Optional<CoapTransaction> actual = transMgr.findMatchAndRemoveForSeparateResponse(packet);
        assertNotEmpty(actual);
        assertEquals(actual.get(), expected);
    }

    public static void assertPresent(Optional val) {
        assertTrue(val.isPresent());
    }

    public static void assertEmpty(Optional val) {
        assertFalse(val.isPresent());
    }

    public static void assertNotEmpty(Optional val) {
        assertTrue(val.isPresent());
    }
}