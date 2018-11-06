/*
 * Copyright (c) 2016-2017 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */

package org.saltyrtc.tasks.webrtc.integration;

import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.saltyrtc.client.SaltyRTC;
import org.saltyrtc.client.SaltyRTCBuilder;
import org.saltyrtc.client.events.EventHandler;
import org.saltyrtc.client.events.SignalingStateChangedEvent;
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.client.signaling.state.SignalingState;
import org.saltyrtc.client.tasks.Task;
import org.saltyrtc.tasks.webrtc.Config;
import org.saltyrtc.tasks.webrtc.SSLContextHelper;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ConnectionTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        if (Config.DEBUG) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        }
    }

    private SaltyRTC initiator;
    private WebRTCTask initiatorTask;
    private SaltyRTC responder;
    private WebRTCTask responderTask;
    private Map<String, Boolean> eventsCalled;

    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }
        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
        }
        @Override
        public void onIceConnectionReceivingChange(boolean b) {
        }
        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }
        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
        }
        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        }
        @Override
        public void onAddStream(MediaStream mediaStream) {
        }
        @Override
        public void onRemoveStream(MediaStream mediaStream) {
        }
        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }
        @Override
        public void onRenegotiationNeeded() {
        }
        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        }
    }

    private PeerConnection createPeerConnection() {
        // Initialize
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(null)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        );

        // Create peer connection
        final PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        final PeerConnectionFactory factory = PeerConnectionFactory
            .builder()
            .setOptions(options)
            .createPeerConnectionFactory();
        return factory.createPeerConnection(
            new ArrayList<>(),
            new PeerConnectionObserver()
        );
    }

    @Before
    public void setUp() throws Exception {
        // Get SSL context
        final SSLContext sslContext = SSLContextHelper.getSSLContext();

        // Initialize tasks
        this.initiatorTask = new WebRTCTask();
        this.responderTask = new WebRTCTask();

        // Create SaltyRTC instances for initiator and responder
        this.initiator = new SaltyRTCBuilder()
                .connectTo(Config.SALTYRTC_HOST, Config.SALTYRTC_PORT, sslContext)
                .withKeyStore(new KeyStore())
                .usingTasks(new Task[]{ this.initiatorTask })
                .asInitiator();
        this.responder = new SaltyRTCBuilder()
                .connectTo(Config.SALTYRTC_HOST, Config.SALTYRTC_PORT, sslContext)
                .withKeyStore(new KeyStore())
                .usingTasks(new Task[]{ this.responderTask })
                .initiatorInfo(initiator.getPublicPermanentKey(), initiator.getAuthToken())
                .asResponder();

        // Enable verbose debug mode
        if (Config.VERBOSE) {
            this.initiator.setDebug(true);
            this.responder.setDebug(true);
        }

        // Initiate event registry
        eventsCalled = new HashMap<>();
        final String[] events = new String[] { "Connected", "Error", "Closed" };
        for (String event : events) {
            eventsCalled.put("initiator" + event, false);
            eventsCalled.put("responder" + event, false);
        }

        // Register event handlers
        this.initiator.events.signalingStateChanged.register(event -> {
            //noinspection EnumSwitchStatementWhichMissesCases
            switch (event.getState()) {
                case TASK:
                    eventsCalled.put("initiatorConnected", true);
                    break;
                case ERROR:
                    eventsCalled.put("initiatorError", true);
                    break;
                case CLOSED:
                    eventsCalled.put("initiatorClosed", true);
                    break;
            }
            return false;
        });
        this.responder.events.signalingStateChanged.register(event -> {
            //noinspection EnumSwitchStatementWhichMissesCases
            switch (event.getState()) {
                case TASK:
                    eventsCalled.put("responderConnected", true);
                    break;
                case ERROR:
                    eventsCalled.put("responderError", true);
                    break;
                case CLOSED:
                    eventsCalled.put("responderClosed", true);
                    break;
            }
            return false;
        });
    }

    @Test
    public void testConnectSpeed() throws Exception {
        // Max 1s for handshake
        final int MAX_DURATION = 1800;

        // Latches to test connection state
        final CountDownLatch connectedPeers = new CountDownLatch(2);
        this.initiator.events.signalingStateChanged.register(event -> {
            if (event.getState() == SignalingState.TASK) {
                connectedPeers.countDown();
            }
            return false;
        });
        this.responder.events.signalingStateChanged.register(event -> {
            if (event.getState() == SignalingState.TASK) {
                connectedPeers.countDown();
            }
            return false;
        });

        // Connect server
        final long startTime = System.nanoTime();
        initiator.connect();
        responder.connect();

        // Wait for full handshake
        final boolean bothConnected = connectedPeers.await(2 * MAX_DURATION, TimeUnit.MILLISECONDS);
        final long endTime = System.nanoTime();
        assertTrue(bothConnected);
        assertFalse(eventsCalled.get("responderError"));
        assertFalse(eventsCalled.get("initiatorError"));
        long durationMs = (endTime - startTime) / 1000 / 1000;
        System.out.println("Full handshake took " + durationMs + " milliseconds");

        // Disconnect
        responder.disconnect();
        initiator.disconnect();

        assertTrue("Duration time (" + durationMs + "ms) should be less than " + MAX_DURATION + "ms",
                   durationMs < MAX_DURATION);
    }

    @Test
    public void testHandover() throws Exception {
        // Latches to test connection state
        final CountDownLatch connectedPeers = new CountDownLatch(2);
        initiator.events.signalingStateChanged.register(event -> {
            if (event.getState() == SignalingState.TASK) {
                connectedPeers.countDown();
            }
            return false;
        });
        responder.events.signalingStateChanged.register(event -> {
            if (event.getState() == SignalingState.TASK) {
                connectedPeers.countDown();
            }
            return false;
        });

        // Connect server
        initiator.connect();
        responder.connect();

        // Wait for full handshake
        final boolean bothConnected = connectedPeers.await(5000, TimeUnit.MILLISECONDS);
        assertTrue(bothConnected);
        assertFalse(eventsCalled.get("responderError"));
        assertFalse(eventsCalled.get("initiatorError"));

        // Disconnect
        responder.disconnect();
        initiator.disconnect();

        // TODO: Actual handover
    }

    @After
    public void tearDown() {
        if (this.initiator != null) {
            initiator.disconnect();
        }
        if (this.responder != null) {
            responder.disconnect();
        }
    }

}
