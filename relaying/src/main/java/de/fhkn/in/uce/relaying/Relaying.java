/*
 * Copyright (c) 2012 Alexander Diener,
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.fhkn.in.uce.relaying;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fhkn.in.uce.plugininterface.ConnectionNotEstablishedException;
import de.fhkn.in.uce.plugininterface.NATTraversalTechnique;
import de.fhkn.in.uce.plugininterface.NATTraversalTechniqueMetaData;
import de.fhkn.in.uce.relaying.core.RelayingClient;
import de.fhkn.in.uce.stun.attribute.MappedAddress;
import de.fhkn.in.uce.stun.attribute.Username;
import de.fhkn.in.uce.stun.header.STUNMessageClass;
import de.fhkn.in.uce.stun.header.STUNMessageMethod;
import de.fhkn.in.uce.stun.message.Message;
import de.fhkn.in.uce.stun.message.MessageReader;
import de.fhkn.in.uce.stun.message.MessageStaticFactory;

/**
 * Implementation of {@link NATTraversalTechnique} which realizes a indirect
 * connection by using a relay server.
 * 
 * @author Alexander Diener (aldiener@htwg-konstanz.de)
 * 
 */
public final class Relaying implements NATTraversalTechnique {
    private static final Logger logger = LoggerFactory.getLogger(Relaying.class);
    private final NATTraversalTechniqueMetaData metaData;
    private final Socket controlConnection;
    private final InetSocketAddress relayAddress;
    private final ResourceBundle bundle = ResourceBundle.getBundle("de.fhkn.in.uce.relay.traversal.relaying"); //$NON-NLS-1$
    private RelayingClient targetRelayClient = null;
    private volatile boolean isRegistered = false;

    public Relaying() {
        try {
            this.metaData = new RelayingMetaData();
            this.relayAddress = this.getRelayServerAddressFromBundle();
            this.controlConnection = new Socket();
            this.controlConnection.setReuseAddress(true);
        } catch (final Exception e) {
            logger.error("Exception occured while creating relaying connection object.", e); //$NON-NLS-1$
            throw new RuntimeException("Could not create relaying connection object.", e); //$NON-NLS-1$
        }
    }

    public Relaying(final Relaying toCopy) {
        try {
            this.metaData = new RelayingMetaData((RelayingMetaData) toCopy.getMetaData());
            this.relayAddress = toCopy.relayAddress;
            this.controlConnection = new Socket();
            this.controlConnection.setReuseAddress(true);
        } catch (final Exception e) {
            logger.error("Exception occured while creating relaying connection object.", e); //$NON-NLS-1$
            throw new RuntimeException("Could not create relaying connection object.", e); //$NON-NLS-1$
        }
    }

    private InetSocketAddress getRelayServerAddressFromBundle() {
        final String host = this.bundle.getString("relaying.server.ip"); //$NON-NLS-1$
        final String port = this.bundle.getString("relaying.server.port"); //$NON-NLS-1$
        return new InetSocketAddress(host, Integer.valueOf(port));
    }

    @Override
    public Socket createSourceSideConnection(final String targetId, final InetSocketAddress mediatorAddress)
            throws ConnectionNotEstablishedException {
        logger.debug("creating source-side connection via relaying, mediator={}", mediatorAddress); //$NON-NLS-1$
        try {
            this.connectToMediatorIfNotAlreadyConnected(mediatorAddress, 0);
            this.sendConnectionRequest(targetId);
            final Message responseMessage = this.receiveConnectionResponse();
            final InetSocketAddress endpointAtRelayServer = this.getEndpointFromMessage(responseMessage);
            return this.connectToTargetEndpoint(endpointAtRelayServer);
        } catch (final Exception e) {
            logger.error(e.getMessage());
            throw new ConnectionNotEstablishedException(this.metaData.getTraversalTechniqueName(),
                    "Source-side socket could not be created.", e); //$NON-NLS-1$
        }
    }

    private void sendConnectionRequest(final String targetId) throws Exception {
        logger.debug("Sending connection request"); //$NON-NLS-1$
        final Message requestConnectionMessage = MessageStaticFactory.newSTUNMessageInstance(STUNMessageClass.REQUEST,
                STUNMessageMethod.CONNECTION_REQUEST);
        requestConnectionMessage.addAttribute(new Username(targetId));
        requestConnectionMessage.writeTo(this.controlConnection.getOutputStream());
    }

    private Message receiveConnectionResponse() throws IOException {
        final MessageReader messageReader = MessageReader.createMessageReader();
        return messageReader.readSTUNMessage(this.controlConnection.getInputStream());
    }

    private InetSocketAddress getEndpointFromMessage(final Message msg) throws Exception {
        InetSocketAddress result = null;
        if (msg.hasAttribute(MappedAddress.class)) {
            result = msg.getAttribute(MappedAddress.class).getEndpoint();
        } else {
            final String errorMessage = "The target endpoint at relay is not returned by the mediator."; //$NON-NLS-1$
            logger.debug(errorMessage);
            throw new ConnectionNotEstablishedException(this.metaData.getTraversalTechniqueName(), errorMessage, null);
        }
        return result;
    }

    private Socket connectToTargetEndpoint(final InetSocketAddress endpoint) throws Exception {
        final Socket socket = new Socket();
        socket.setReuseAddress(true);
        socket.connect(endpoint, endpoint.getPort());
        return socket;
    }

    @Override
    public Socket createTargetSideConnection(final String targetId, final InetSocketAddress mediatorAddress)
            throws ConnectionNotEstablishedException {
        if (!this.isRegistered) {
            throw new ConnectionNotEstablishedException(this.metaData.getTraversalTechniqueName(),
                    "Target must be registered before creating target-side connection.", null); //$NON-NLS-1$
        }
        Socket socket = new Socket();
        try {
            socket = this.targetRelayClient.accept();
        } catch (final Exception e) {
            logger.error(e.getMessage());
            throw new ConnectionNotEstablishedException(this.metaData.getTraversalTechniqueName(),
                    "Could not create target-side conenction.", e); //$NON-NLS-1$
        }
        return socket;
    }

    @Override
    public void registerTargetAtMediator(final String targetId, final InetSocketAddress mediatorAddress)
            throws Exception {
        try {
            this.connectToMediatorIfNotAlreadyConnected(mediatorAddress, 0);
            this.targetRelayClient = new RelayingClient(this.relayAddress);
            final InetSocketAddress endpointAtRelay = this.createAllocationAtRelayServer();
            logger.debug("created endpoint at relay server: {}", endpointAtRelay.toString()); //$NON-NLS-1$
            this.registerRelayEndpointAtMediator(targetId, endpointAtRelay);
            this.isRegistered = true;
        } catch (final Exception e) {
            logger.error(e.getMessage());
            throw new ConnectionNotEstablishedException(this.metaData.getTraversalTechniqueName(),
                    "Target could not be registered.", e); //$NON-NLS-1$
        }
    }

    private void connectToMediatorIfNotAlreadyConnected(final InetSocketAddress mediatorAddress, final int localport)
            throws ConnectionNotEstablishedException {
        logger.debug("checking connection with mediator {}", mediatorAddress); //$NON-NLS-1$
        if (!this.controlConnection.isConnected()) {
            logger.debug("control connection not established, trying to connect"); //$NON-NLS-1$
            try {
                logger.debug("trying to connect to {}", mediatorAddress); //$NON-NLS-1$
                this.controlConnection.connect(mediatorAddress);
                logger.debug("control connection established"); //$NON-NLS-1$
            } catch (final Exception e) {
                logger.error("Exception while connecting to mediator", e); //$NON-NLS-1$
                throw new ConnectionNotEstablishedException(this.metaData.getTraversalTechniqueName(),
                        "Control connection could not be established.", e); //$NON-NLS-1$
            }
        } else {
            logger.debug("control connection already established"); //$NON-NLS-1$
        }
    }

    private InetSocketAddress createAllocationAtRelayServer() throws Exception {
        InetSocketAddress result = null;
        result = this.targetRelayClient.createAllocation();
        if (result.getAddress().isAnyLocalAddress()) {
            result = new InetSocketAddress(this.relayAddress.getAddress(), result.getPort());
        }
        return result;
    }

    private void registerRelayEndpointAtMediator(final String targetId, final InetSocketAddress endpointAtRelay)
            throws Exception {
        final Message registerMessage = MessageStaticFactory.newSTUNMessageInstance(STUNMessageClass.REQUEST,
                STUNMessageMethod.REGISTER);
        final Username userName = new Username(targetId);
        registerMessage.addAttribute(userName);
        registerMessage.addAttribute(new MappedAddress(endpointAtRelay));
        registerMessage.writeTo(this.controlConnection.getOutputStream());
    }

    @Override
    public void deregisterTargetAtMediator(final String targetId, final InetSocketAddress mediatorAddress)
            throws Exception {
        try {
            this.deregisterRelayEndpointAtMediator(targetId);
            this.isRegistered = false;
        } catch (final Exception e) {
            logger.error("Exception while deregistering target {}: {}", targetId, e.getMessage()); //$NON-NLS-1$
            throw new Exception("Exception while deregistering target", e); //$NON-NLS-1$
        }
    }

    private void deregisterRelayEndpointAtMediator(final String targetId) throws Exception {
        final Message deregisterMessage = MessageStaticFactory.newSTUNMessageInstance(STUNMessageClass.REQUEST,
                STUNMessageMethod.DEREGISTER);
        deregisterMessage.addAttribute(new Username(targetId));
        deregisterMessage.writeTo(this.controlConnection.getOutputStream());
    }

    @Override
    public NATTraversalTechniqueMetaData getMetaData() {
        return this.metaData;
    }

    @Override
    public NATTraversalTechnique copy() {
        return new Relaying(this);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.metaData == null) ? 0 : this.metaData.hashCode());
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        final Relaying other = (Relaying) obj;
        if (this.metaData == null) {
            if (other.metaData != null) {
                return false;
            }
        } else if (!this.metaData.equals(other.metaData)) {
            return false;
        }
        return true;
    }
}